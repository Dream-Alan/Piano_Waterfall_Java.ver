package com.piano.waterfall.midi;

/**
 * 采样钢琴音色预设。参数直接作用在采样混音上，避免维护多套采样。
 */
public enum PianoVoice {

    GRAND("音乐会三角", 16_000, 25, 1.00, 1.00, 0.06),
    BRIGHT("明亮流行", 20_000, 650, 0.92, 1.60, 0.04),
    FELT("毛毡柔音", 900, 20, 0.82, 0.80, 0.20),
    UPRIGHT("复古立式", 3_200, 95, 1.05, 1.35, 0.10),
    ROOM("音乐厅", 8_000, 25, 0.95, 0.95, 0.55);

    private final String label;
    private final double lowpassHz;
    private final double highpassHz;
    private final double gain;
    private final double drive;
    private final double reverbMix;

    PianoVoice(String label, double lowpassHz, double highpassHz,
               double gain, double drive, double reverbMix) {
        this.label = label;
        this.lowpassHz = lowpassHz;
        this.highpassHz = highpassHz;
        this.gain = gain;
        this.drive = drive;
        this.reverbMix = reverbMix;
    }

    public String label() {
        return label;
    }

    public double lowpassHz() {
        return lowpassHz;
    }

    public double highpassHz() {
        return highpassHz;
    }

    public double gain() {
        return gain;
    }

    public double drive() {
        return drive;
    }

    public double reverbMix() {
        return reverbMix;
    }

    @Override
    public String toString() {
        return label;
    }
}
