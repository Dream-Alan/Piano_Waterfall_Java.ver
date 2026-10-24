package com.piano.waterfall.render;

import com.piano.waterfall.model.KeyGeometry;
import com.piano.waterfall.model.NoteBlock;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.DropShadow;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.List;

/**
 * 霓虹瀑布流渲染器：主题背景、88 键 3D 键盘、音符、激光光柱、粒子与顶部 HUD。
 */
public class WaterfallRenderer {

    /** 内置四套霓虹主题。 */
    public enum Theme {
        CYBERPUNK("赛博青粉", Color.web("#00F0FF"), Color.web("#FF2A85"),
                Color.web("#07080C"), Color.web("#111522")),
        AURORA("极光翠绿", Color.web("#00FF9D"), Color.web("#7000FF"),
                Color.web("#060B0E"), Color.web("#0D1B22")),
        GOLDEN("鎏金琥珀", Color.web("#FFB800"), Color.web("#FF3D00"),
                Color.web("#0C0906"), Color.web("#1C140D")),
        SAKURA("星光樱粉", Color.web("#FF75B5"), Color.web("#7A88FF"),
                Color.web("#0B070E"), Color.web("#181124"));

        public final String label;
        public final Color whiteKeyNeon;
        public final Color blackKeyNeon;
        public final Color bgTop;
        public final Color bgBottom;

        Theme(String label, Color whiteKeyNeon, Color blackKeyNeon,
              Color bgTop, Color bgBottom) {
            this.label = label;
            this.whiteKeyNeon = whiteKeyNeon;
            this.blackKeyNeon = blackKeyNeon;
            this.bgTop = bgTop;
            this.bgBottom = bgBottom;
        }
    }

    private static final String[] JIANPU_LABELS = {
            "1", "#1", "2", "#2", "3", "4", "#4", "5", "#5", "6", "#6", "7"
    };

    private double fallSpeed = 300.0;
    private Theme theme = Theme.CYBERPUNK;
    private boolean showNoteLabels = true;

    private final DropShadow neonGlow = new DropShadow(16, theme.whiteKeyNeon);
    private final ParticleSystem particleSystem = new ParticleSystem();
    private final boolean[] prevKeyActive = new boolean[128];

    private long lastFrameNano;
    private long fpsWindowStart;
    private int fpsFrames;
    private double smoothFps = 60.0;
    private String songTitle = "Piano Waterfall";
    private double totalDurationSec = 1.0;

    public void setSongMetadata(String title, List<NoteBlock> notes) {
        this.songTitle = (title == null || title.isBlank()) ? "Piano Waterfall" : title;
        this.particleSystem.clear();
        java.util.Arrays.fill(prevKeyActive, false);
        this.totalDurationSec = notes.stream()
                .mapToDouble(NoteBlock::endTime)
                .max()
                .orElse(1.0);
        if (totalDurationSec <= 0) {
            totalDurationSec = 1.0;
        }
    }

    public Theme nextTheme() {
        Theme[] values = Theme.values();
        theme = values[(theme.ordinal() + 1) % values.length];
        return theme;
    }

    public Theme getTheme() {
        return theme;
    }

    public void setTheme(Theme theme) {
        this.theme = theme == null ? Theme.CYBERPUNK : theme;
    }

    public void setFallSpeed(double speed) {
        this.fallSpeed = Math.max(120.0, Math.min(900.0, speed));
    }

    public double getFallSpeed() {
        return fallSpeed;
    }

    public void toggleNoteLabels() {
        showNoteLabels = !showNoteLabels;
    }

    public boolean isShowingNoteLabels() {
        return showNoteLabels;
    }

    public double getTotalDurationSec() {
        return totalDurationSec;
    }

    public void render(GraphicsContext gc, double width, double height,
                       double currentTimeSec, List<NoteBlock> notes, KeyGeometry[] keys) {
        render(gc, width, height, currentTimeSec, -1, notes, keys);
    }

    /**
     * @param fixedDt 大于 0 时使用固定步长（离线导出 60 FPS），否则按真实帧间隔
     */
    public void render(GraphicsContext gc, double width, double height,
                       double currentTimeSec, double fixedDt,
                       List<NoteBlock> notes, KeyGeometry[] keys) {
        long now = System.nanoTime();
        double dt;
        if (fixedDt > 0) {
            dt = fixedDt;
            smoothFps = 1.0 / fixedDt;
        } else {
            dt = lastFrameNano == 0 ? 1.0 / 60.0 : (now - lastFrameNano) / 1_000_000_000.0;
            lastFrameNano = now;
            dt = Math.min(dt, 0.05);
            fpsFrames++;
            if (fpsWindowStart == 0) {
                fpsWindowStart = now;
            } else if (now - fpsWindowStart >= 500_000_000L) {
                smoothFps = fpsFrames * 1_000_000_000.0 / (now - fpsWindowStart);
                fpsFrames = 0;
                fpsWindowStart = now;
            }
        }

        double hudHeight = 54.0;
        double keyboardHeight = Math.max(110.0, Math.min(190.0, height * 0.18));
        double hitLineY = height - keyboardHeight;

        drawBackgroundAndGrid(gc, width, height, hudHeight, hitLineY, keys);

        for (int pitch = 21; pitch <= 108; pitch++) {
            if (keys[pitch] != null) {
                keys[pitch].setActive(false);
            }
        }

        int activeNoteCount = drawNotes(gc, currentTimeSec, hudHeight, hitLineY, notes, keys);
        drawActiveKeyBeamsAndEmitParticles(gc, hudHeight, hitLineY, keys);
        draw3DPianoKeys(gc, keys, hitLineY, keyboardHeight);
        drawBreathingHitLine(gc, currentTimeSec, hitLineY, keys);
        particleSystem.updateAndRender(gc, dt);
        drawTopHud(gc, width, hudHeight, currentTimeSec, activeNoteCount);

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BASELINE);
        gc.setGlobalBlendMode(BlendMode.SRC_OVER);
    }

    private void drawBackgroundAndGrid(GraphicsContext gc, double width, double height,
                                       double hudHeight, double hitLineY, KeyGeometry[] keys) {
        gc.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, theme.bgTop),
                new Stop(1, theme.bgBottom)));
        gc.fillRect(0, 0, width, height);

        // 八度分隔线与白键轨道底纹
        for (int pitch = 21; pitch <= 108; pitch++) {
            KeyGeometry key = keys[pitch];
            if (key == null) {
                continue;
            }
            if (pitch % 12 == 0) {
                gc.setStroke(Color.web("#FFFFFF", 0.08));
                gc.setLineWidth(1.2);
                gc.strokeLine(key.getX(), hudHeight, key.getX(), hitLineY);
            } else if (!key.isBlack()) {
                gc.setStroke(Color.web("#FFFFFF", 0.02));
                gc.setLineWidth(0.5);
                gc.strokeLine(key.getX(), hudHeight, key.getX(), hitLineY);
            }
        }

        // 判定线附近的主题色环境光
        gc.setFill(new LinearGradient(0, hitLineY - 110, 0, hitLineY, false, CycleMethod.NO_CYCLE,
                new Stop(0, theme.whiteKeyNeon.deriveColor(0, 1, 1, 0.0)),
                new Stop(1, theme.whiteKeyNeon.deriveColor(0, 1, 1, 0.07))));
        gc.fillRect(0, hitLineY - 110, width, 110);
    }

    private int drawNotes(GraphicsContext gc, double currentTimeSec, double hudHeight,
                          double hitLineY, List<NoteBlock> notes, KeyGeometry[] keys) {
        int activeCount = 0;
        gc.save();
        for (NoteBlock note : notes) {
            double yBottom = hitLineY - (note.startTime() - currentTimeSec) * fallSpeed;
            double noteHeight = Math.max(6.0, (note.endTime() - note.startTime()) * fallSpeed);
            double yTop = yBottom - noteHeight;

            KeyGeometry key = keys[note.pitch()];
            if (key == null) {
                continue;
            }

            boolean hitting = currentTimeSec >= note.startTime() && currentTimeSec <= note.endTime();
            if (hitting) {
                key.setActive(true);
                activeCount++;
            }

            if (yBottom < hudHeight || yTop > hitLineY) {
                continue;
            }

            double clampedTop = Math.max(yTop, hudHeight);
            double clampedBottom = Math.min(yBottom, hitLineY);
            double visibleHeight = clampedBottom - clampedTop;
            if (visibleHeight <= 0) {
                continue;
            }

            Color baseColor = key.isBlack() ? theme.blackKeyNeon : theme.whiteKeyNeon;
            neonGlow.setColor(baseColor);
            gc.setEffect(neonGlow);

            Color topFill = hitting ? Color.WHITE : baseColor.brighter();
            gc.setFill(new LinearGradient(0, clampedTop, 0, clampedBottom, false, CycleMethod.NO_CYCLE,
                    new Stop(0, baseColor.deriveColor(0, 1.0, 0.85, 0.92)),
                    new Stop(1, topFill)));
            gc.fillRoundRect(key.getX() + 1.2, clampedTop, key.getWidth() - 2.4,
                    visibleHeight, 8, 8);

            // 内部玻璃高光边框
            gc.setEffect(null);
            gc.setStroke(Color.WHITE.deriveColor(0, 1, 1, hitting ? 0.95 : 0.62));
            gc.setLineWidth(hitting ? 1.7 : 1.0);
            gc.strokeRoundRect(key.getX() + 1.2, clampedTop, key.getWidth() - 2.4,
                    visibleHeight, 8, 8);

            // 音符底部的简谱数字标注
            if (showNoteLabels && visibleHeight >= 22 && key.getWidth() >= 12) {
                String label = JIANPU_LABELS[note.pitch() % 12];
                gc.setFill(Color.web("#08090C", 0.82));
                gc.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD,
                        Math.min(13.0, key.getWidth() * 0.62)));
                gc.setTextAlign(TextAlignment.CENTER);
                gc.setTextBaseline(VPos.BOTTOM);
                gc.fillText(label, key.getX() + key.getWidth() / 2.0, clampedBottom - 4);
            }
        }
        gc.setEffect(null);
        gc.restore();
        return activeCount;
    }

    private void drawActiveKeyBeamsAndEmitParticles(GraphicsContext gc, double hudHeight,
                                                    double hitLineY, KeyGeometry[] keys) {
        gc.save();
        gc.setGlobalBlendMode(BlendMode.ADD);
        double beamHeight = (hitLineY - hudHeight) * 0.30;
        for (int pitch = 21; pitch <= 108; pitch++) {
            KeyGeometry key = keys[pitch];
            if (key == null) {
                continue;
            }
            boolean nowActive = key.isActive();
            boolean wasActive = prevKeyActive[pitch];
            Color color = key.isBlack() ? theme.blackKeyNeon : theme.whiteKeyNeon;
            double centerX = key.getX() + key.getWidth() / 2.0;

            if (nowActive) {
                gc.setFill(new LinearGradient(0, hitLineY - beamHeight, 0, hitLineY,
                        false, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.TRANSPARENT),
                        new Stop(1, color.deriveColor(0, 1.0, 1.0, 0.26))));
                gc.fillRect(key.getX() - 2, hitLineY - beamHeight,
                        key.getWidth() + 4, beamHeight);
                if (!wasActive) {
                    particleSystem.emitBurst(centerX, hitLineY, key.getWidth(), color);
                } else {
                    particleSystem.emitContinuous(centerX, hitLineY, key.getWidth(), color);
                }
            }
            prevKeyActive[pitch] = nowActive;
        }
        gc.restore();
    }

    private void draw3DPianoKeys(GraphicsContext gc, KeyGeometry[] keys,
                                 double hitLineY, double kbHeight) {
        // 白键：底座阴影 + 象牙白表面 + 按下时的主题色渐变
        for (int pitch = 21; pitch <= 108; pitch++) {
            KeyGeometry key = keys[pitch];
            if (key == null || key.isBlack()) {
                continue;
            }
            double x = key.getX();
            double w = key.getWidth() - 1.5;
            double radius = Math.min(6, w / 3.0);

            gc.setFill(Color.web("#9AA0AC"));
            gc.fillRoundRect(x, hitLineY, w, kbHeight, radius, radius);

            if (key.isActive()) {
                gc.setFill(new LinearGradient(0, hitLineY, 0, hitLineY + kbHeight,
                        false, CycleMethod.NO_CYCLE,
                        new Stop(0, theme.whiteKeyNeon),
                        new Stop(0.82, theme.whiteKeyNeon.deriveColor(0, 0.55, 1.0, 1.0)),
                        new Stop(1, Color.WHITE)));
                gc.fillRoundRect(x, hitLineY + 2, w, kbHeight - 2, radius, radius);
            } else {
                gc.setFill(new LinearGradient(0, hitLineY, 0, hitLineY + kbHeight - 5,
                        false, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.web("#E6E9F0")),
                        new Stop(1, Color.web("#FFFFFF"))));
                gc.fillRoundRect(x, hitLineY, w, kbHeight - 5, radius, radius);
            }

            if (showNoteLabels && pitch % 12 == 0) {
                int octave = (pitch / 12) - 1;
                gc.setFill(key.isActive() ? Color.web("#08090C") : Color.web("#687082"));
                gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, Math.min(10, w * 0.5)));
                gc.setTextAlign(TextAlignment.CENTER);
                gc.setTextBaseline(VPos.BOTTOM);
                gc.fillText("C" + octave, x + w / 2.0, hitLineY + kbHeight - 7);
            }
        }

        // 黑键：底座 + 内侧光泽面
        for (int pitch = 21; pitch <= 108; pitch++) {
            KeyGeometry key = keys[pitch];
            if (key == null || !key.isBlack()) {
                continue;
            }
            double x = key.getX();
            double w = key.getWidth();
            double bh = kbHeight * 0.64;

            gc.setFill(Color.web("#0A0B0E"));
            gc.fillRoundRect(x - 0.5, hitLineY, w + 1, bh + 2, 5, 5);

            if (key.isActive()) {
                gc.setFill(new LinearGradient(0, hitLineY, 0, hitLineY + bh,
                        false, CycleMethod.NO_CYCLE,
                        new Stop(0, theme.blackKeyNeon),
                        new Stop(1, Color.WHITE)));
                gc.fillRoundRect(x, hitLineY + 1, w, bh, 5, 5);
            } else {
                gc.setFill(new LinearGradient(0, hitLineY, 0, hitLineY + bh - 4,
                        false, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.web("#2B303C")),
                        new Stop(1, Color.web("#151820"))));
                gc.fillRoundRect(x + 1, hitLineY, w - 2, bh - 4, 4, 4);
            }
        }

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BASELINE);
    }

    private void drawBreathingHitLine(GraphicsContext gc, double currentTimeSec,
                                      double hitLineY, KeyGeometry[] keys) {
        if (keys[21] == null || keys[108] == null) {
            return;
        }
        double leftX = keys[21].getX();
        double rightX = keys[108].getX() + keys[108].getWidth();

        // 钢琴顶部经典暗红绒布条
        gc.setFill(Color.web("#5E1124"));
        gc.fillRect(leftX, hitLineY - 2, rightX - leftX, 4);

        double pulse = 0.75 + 0.25 * Math.sin(currentTimeSec * 6.0);
        gc.save();
        gc.setEffect(new DropShadow(18 * pulse, theme.whiteKeyNeon));
        gc.setStroke(theme.whiteKeyNeon.deriveColor(0, 1, 1, 0.9 * pulse));
        gc.setLineWidth(2.6);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.strokeLine(leftX, hitLineY, rightX, hitLineY);
        gc.restore();
    }

    private void drawTopHud(GraphicsContext gc, double width, double hudHeight,
                            double currentTimeSec, int activeNoteCount) {
        gc.setFill(Color.web("#080A10", 0.86));
        gc.fillRect(0, 0, width, hudHeight);

        double clampedTime = Math.max(0.0, currentTimeSec);
        double progress = Math.min(1.0, clampedTime / Math.max(1.0, totalDurationSec));

        double barY = hudHeight - 4.0;
        gc.setFill(Color.web("#1E2433"));
        gc.fillRect(0, barY, width, 4.0);
        gc.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, theme.whiteKeyNeon),
                new Stop(1, theme.blackKeyNeon)));
        gc.fillRect(0, barY, width * progress, 4.0);

        gc.save();
        gc.beginPath();
        gc.rect(0, 0, width, hudHeight);
        gc.clip();

        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 15));
        gc.setFill(Color.web("#F0F4F8"));
        gc.fillText("♪  " + shorten(songTitle, 28) + "   [" + theme.label + "]", 22, 31);

        String timeText = String.format("%s / %s  (%.0f%%)",
                formatTime(clampedTime), formatTime(totalDurationSec), progress * 100);
        gc.setFont(Font.font("Consolas", FontWeight.BOLD, 14));
        gc.setFill(theme.whiteKeyNeon);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(timeText, width * 0.50, 31);

        String statsText = String.format("Notes: %2d  |  Speed: %.0f  |  FPS: %3.0f",
                activeNoteCount, fallSpeed, smoothFps);
        gc.setFont(Font.font("Consolas", FontWeight.NORMAL, 12));
        gc.setFill(Color.web("#9BA3B0"));
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(statsText, width - 22, 31);

        gc.restore();
        gc.setTextAlign(TextAlignment.LEFT);
    }

    private String formatTime(double seconds) {
        int totalSec = (int) Math.max(0, seconds);
        return String.format("%02d:%02d", totalSec / 60, totalSec % 60);
    }

    private String shorten(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(1, maxLength - 1)) + "…";
    }
}
