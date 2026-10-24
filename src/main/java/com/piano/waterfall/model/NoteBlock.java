package com.piano.waterfall.model;

/**
 * 一个下落的音符：MIDI 音高、开始/结束时间（秒）与力度。
 *
 * @param pitch     MIDI 音高，88 键钢琴范围为 21 ~ 108
 * @param startTime 按下时间（秒）
 * @param endTime   释放时间（秒）
 * @param velocity  力度 0 ~ 127
 */
public record NoteBlock(int pitch, double startTime, double endTime, int velocity) {

    public NoteBlock {
        if (endTime < startTime) {
            throw new IllegalArgumentException("endTime must not be before startTime");
        }
    }

    public double duration() {
        return endTime - startTime;
    }
}
