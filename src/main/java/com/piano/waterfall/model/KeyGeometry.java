package com.piano.waterfall.model;

/**
 * 88 键钢琴中单个琴键的几何信息与当前激活状态。
 */
public class KeyGeometry {

    private final int pitch;
    private final boolean black;
    private final double x;
    private final double width;
    private boolean active;

    public KeyGeometry(int pitch, boolean black, double x, double width) {
        this.pitch = pitch;
        this.black = black;
        this.x = x;
        this.width = width;
        this.active = false;
    }

    public int getPitch() {
        return pitch;
    }

    public boolean isBlack() {
        return black;
    }

    public double getX() {
        return x;
    }

    public double getWidth() {
        return width;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * 十二平均律中 1(C#)、3(D#)、6(F#)、8(G#)、10(A#) 为黑键。
     */
    public static boolean isBlackKey(int pitch) {
        int noteInOctave = Math.floorMod(pitch, 12);
        return noteInOctave == 1 || noteInOctave == 3
                || noteInOctave == 6 || noteInOctave == 8
                || noteInOctave == 10;
    }

    /**
     * 根据画布宽度计算 88 键（MIDI 21 ~ 108）的居中坐标。
     * 返回长度 128 的数组，仅 21 ~ 108 的索引非空。
     *
     * @param canvasWidth 当前画布宽度
     * @param widthRatio  键盘占画布宽度的比例（例如 0.94）
     */
    public static KeyGeometry[] build88Keys(double canvasWidth, double widthRatio) {
        KeyGeometry[] keys = new KeyGeometry[128];
        if (canvasWidth <= 0) {
            return keys;
        }

        double ratio = Math.max(0.40, Math.min(1.0, widthRatio));
        final int totalWhiteKeys = 52;
        double usableWidth = canvasWidth * ratio;
        double whiteKeyWidth = usableWidth / totalWhiteKeys;
        double blackKeyWidth = whiteKeyWidth * 0.62;
        double startX = (canvasWidth - usableWidth) / 2.0;

        int whiteIndex = 0;
        for (int pitch = 21; pitch <= 108; pitch++) {
            if (!isBlackKey(pitch)) {
                double x = startX + whiteIndex * whiteKeyWidth;
                keys[pitch] = new KeyGeometry(pitch, false, x, whiteKeyWidth);
                whiteIndex++;
            }
        }

        for (int pitch = 21; pitch <= 108; pitch++) {
            if (isBlackKey(pitch)) {
                KeyGeometry previousWhite = keys[pitch - 1];
                if (previousWhite != null) {
                    double x = previousWhite.getX() + whiteKeyWidth - blackKeyWidth / 2.0;
                    keys[pitch] = new KeyGeometry(pitch, true, x, blackKeyWidth);
                }
            }
        }
        return keys;
    }
}
