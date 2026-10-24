package com.piano.waterfall.export;

import com.piano.waterfall.model.NoteBlock;
import com.piano.waterfall.render.WaterfallRenderer;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Track;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 60 FPS 离线导出：固定步长渲染 + Canvas 快照经 stdin 送入 ffmpeg，音轨离线合成。
 *
 * <p>需要系统 PATH 中存在 ffmpeg。音频优先使用 Gervill SoftSynthesizer 的
 * {@code openStream} 反射接口；若被模块系统拦截，则写入等长静音轨，至少保证
 * 视频可以正常导出。</p>
 */
public final class VideoExporter {

    private static final int FPS = 60;
    private static final double LEAD_IN_SEC = 2.0;
    private static final double TAIL_OUT_SEC = 2.5;

    private record TimedEvent(long microsecond, MidiMessage message) {
    }

    private VideoExporter() {
    }

    public static void exportMp4Async(Stage stage, Canvas canvas, WaterfallRenderer renderer,
                                      Sequence sequence, List<NoteBlock> notes,
                                      File soundFont, File outputMp4, Runnable onFinished) {
        final int width = Math.max(2, ((int) canvas.getWidth()) & ~1);
        final int height = Math.max(2, ((int) canvas.getHeight()) & ~1);
        final double maxNoteEnd = notes.stream()
                .mapToDouble(NoteBlock::endTime)
                .max()
                .orElse(5.0);
        final double totalDurationSec = LEAD_IN_SEC + maxNoteEnd + TAIL_OUT_SEC;
        final int totalFrames = (int) Math.ceil(totalDurationSec * FPS);
        final double dt = 1.0 / FPS;

        try {
            stage.setTitle("正在预合成 WAV 音轨，请稍候…");
            File tempWav = File.createTempFile("piano_waterfall_audio_", ".wav");
            tempWav.deleteOnExit();
            renderMidiToWav(sequence, soundFont, LEAD_IN_SEC, totalDurationSec, tempWav);

            ProcessBuilder builder = new ProcessBuilder(
                    ffmpegExecutable(), "-y",
                    "-f", "rawvideo",
                    "-pix_fmt", "bgra",
                    "-s", width + "x" + height,
                    "-r", String.valueOf(FPS),
                    "-i", "-",
                    "-i", tempWav.getAbsolutePath(),
                    "-c:v", "libx264",
                    "-preset", "fast",
                    "-crf", "16",
                    "-pix_fmt", "yuv420p",
                    "-c:a", "aac",
                    "-b:a", "256k",
                    "-shortest",
                    outputMp4.getAbsolutePath());
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process ffmpeg = builder.start();
            OutputStream stdin = new BufferedOutputStream(ffmpeg.getOutputStream(),
                    width * height * 4);

            WritableImage frameImage = new WritableImage(width, height);
            SnapshotParameters snapshotParameters = new SnapshotParameters();
            byte[] pixelBuffer = new byte[width * height * 4];
            var bgraFormat = PixelFormat.getByteBgraPreInstance();
            var exportKeys = com.piano.waterfall.model.KeyGeometry.build88Keys(width, 0.94);

            AnimationTimer exportLoop = new AnimationTimer() {
                private int currentFrame;

                @Override
                public void handle(long now) {
                    if (currentFrame >= totalFrames) {
                        this.stop();
                        finishExport(stdin, ffmpeg, tempWav, stage, outputMp4, onFinished);
                        return;
                    }

                    double simTimeSec = -LEAD_IN_SEC + currentFrame * dt;
                    renderer.render(canvas.getGraphicsContext2D(), width, height,
                            simTimeSec, dt, notes, exportKeys);
                    canvas.snapshot(snapshotParameters, frameImage);
                    frameImage.getPixelReader().getPixels(0, 0, width, height,
                            bgraFormat, pixelBuffer, 0, width * 4);
                    try {
                        stdin.write(pixelBuffer);
                    } catch (IOException exception) {
                        this.stop();
                        exception.printStackTrace();
                        return;
                    }

                    currentFrame++;
                    if (currentFrame % 15 == 0 || currentFrame == totalFrames) {
                        double percent = currentFrame * 100.0 / totalFrames;
                        stage.setTitle(String.format("正在导出 60FPS MP4：%.1f%% (%d / %d 帧)",
                                percent, currentFrame, totalFrames));
                    }
                }
            };
            exportLoop.start();
        } catch (Exception exception) {
            exception.printStackTrace();
            stage.setTitle("导出失败：请确认已安装 ffmpeg 并加入 PATH");
            if (onFinished != null) {
                onFinished.run();
            }
        }
    }


    private static String ffmpegExecutable() {
        String explicit = System.getProperty("piano.ffmpeg");
        if (explicit != null && !explicit.isBlank() && new File(explicit).canExecute()) {
            return explicit;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                File candidate = new File(dir, "ffmpeg.exe");
                if (candidate.canExecute()) {
                    return candidate.getAbsolutePath();
                }
            }
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path packages = Path.of(localAppData, "Microsoft", "WinGet", "Packages");
            if (Files.isDirectory(packages)) {
                try (Stream<Path> stream = Files.walk(packages, 6)) {
                    var found = stream
                            .filter(p -> p.getFileName().toString().equals("ffmpeg.exe"))
                            .findFirst();
                    if (found.isPresent()) {
                        return found.get().toAbsolutePath().toString();
                    }
                } catch (IOException ignored) {
                    // fall through to PATH lookup
                }
            }
        }
        for (String candidate : new String[]{
                "C:/ffmpeg/bin/ffmpeg.exe",
                "C:/Program Files/ffmpeg/bin/ffmpeg.exe"}) {
            if (new File(candidate).canExecute()) {
                return candidate;
            }
        }
        return "ffmpeg";
    }

    private static void finishExport(OutputStream stdin, Process process, File tempWav,
                                     Stage stage, File outputMp4, Runnable onFinished) {
        new Thread(() -> {
            try {
                stdin.flush();
                stdin.close();
                process.waitFor();
                Files.deleteIfExists(tempWav.toPath());
                Platform.runLater(() -> {
                    stage.setTitle("视频导出完成：" + outputMp4.getAbsolutePath());
                    if (onFinished != null) {
                        onFinished.run();
                    }
                });
            } catch (Exception exception) {
                exception.printStackTrace();
                Platform.runLater(() -> {
                    stage.setTitle("视频导出失败：" + exception.getMessage());
                    if (onFinished != null) {
                        onFinished.run();
                    }
                });
            }
        }, "video-export-finisher").start();
    }

    /**
     * 用 Gervill 离线合成 WAV；失败时生成等长静音 WAV。
     */
    private static void renderMidiToWav(Sequence sequence, File soundFont,
                                        double leadInSec, double totalDurationSec,
                                        File outWav) throws Exception {
        AudioFormat format = new AudioFormat(44_100, 16, 2, true, false);
        long totalFrames = (long) (totalDurationSec * format.getFrameRate());

        Synthesizer synth = MidiSystem.getSynthesizer();
        if (!synth.isOpen()) {
            synth.open();
        }
        AudioInputStream pcmStream = null;
        try {
            Method openStreamMethod = synth.getClass().getMethod(
                    "openStream", AudioFormat.class, java.util.Map.class);
            openStreamMethod.setAccessible(true);
            pcmStream = (AudioInputStream) openStreamMethod.invoke(synth, format, null);
        } catch (Exception ignored) {
            // 模块系统未开放时退回静音轨。
        }

        if (pcmStream == null) {
            byte[] silence = new byte[(int) Math.min(Integer.MAX_VALUE, totalFrames
                    * format.getFrameSize())];
            AudioInputStream silentStream = new AudioInputStream(
                    new ByteArrayInputStream(silence), format, silence.length / format.getFrameSize());
            AudioSystem.write(silentStream, AudioFileFormat.Type.WAVE, outWav);
            return;
        }

        if (soundFont != null && soundFont.isFile()) {
            try {
                synth.loadAllInstruments(MidiSystem.getSoundbank(soundFont));
            } catch (Exception ignored) {
                // 继续使用默认音色。
            }
        }

        Receiver receiver = synth.getReceiver();
        long leadInMicros = Math.round(leadInSec * 1_000_000.0);
        for (TimedEvent event : collectMicrosecondEvents(sequence)) {
            receiver.send(event.message(), leadInMicros + event.microsecond());
        }

        AudioInputStream bounded = new AudioInputStream(pcmStream, format, totalFrames);
        AudioSystem.write(bounded, AudioFileFormat.Type.WAVE, outWav);
        bounded.close();
        synth.close();
    }

    private static List<TimedEvent> collectMicrosecondEvents(Sequence sequence) {
        int resolution = sequence.getResolution();
        List<MidiEvent> raw = new ArrayList<>();
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                raw.add(track.get(i));
            }
        }
        raw.sort(Comparator.comparingLong(MidiEvent::getTick));

        List<TimedEvent> result = new ArrayList<>(raw.size());
        long lastTick = 0;
        double currentMicros = 0.0;
        double microsecondsPerQuarterNote = 500_000.0;

        for (MidiEvent event : raw) {
            long delta = event.getTick() - lastTick;
            currentMicros += delta * (microsecondsPerQuarterNote / resolution);
            lastTick = event.getTick();

            MidiMessage message = event.getMessage();
            if (message instanceof MetaMessage meta && meta.getType() == 0x51) {
                byte[] data = meta.getData();
                if (data.length >= 3) {
                    microsecondsPerQuarterNote = ((data[0] & 0xFF) << 16)
                            | ((data[1] & 0xFF) << 8)
                            | (data[2] & 0xFF);
                }
            } else if (message instanceof ShortMessage shortMessage
                    && shortMessage.getCommand() == ShortMessage.NOTE_ON
                    && shortMessage.getData2() == 0) {
                // NOTE_ON velocity 0 等价 NOTE_OFF；直接转发即可。
                result.add(new TimedEvent(Math.round(currentMicros), message));
            } else if (message instanceof ShortMessage) {
                result.add(new TimedEvent(Math.round(currentMicros), message));
            }
        }
        return result;
    }
}
