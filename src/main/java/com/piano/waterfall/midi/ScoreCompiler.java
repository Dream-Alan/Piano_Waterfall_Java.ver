package com.piano.waterfall.midi;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * 简谱/数字谱编译器：把文本直接编译成内存中的 MIDI Sequence。
 *
 * <p>音高：1~7 为基本音级，0 为休止符；前缀/后缀 + 表示高八度，- 表示低八度；
 * # 表示升半音，b 表示降半音。<br>
 * 时值：默认四分音符；/ 减半，~ 延长一拍，. 附点（1.5 倍）。</p>
 */
public final class ScoreCompiler {

    private static final int PPQ = 480;
    private static final int[] MAJOR_SCALE_OFFSETS = {0, 2, 4, 5, 7, 9, 11};

    private ScoreCompiler() {
    }

    /**
     * 将简谱文本（右手主旋律 + 左手伴奏）直接编译为 MIDI Sequence。
     *
     * @param bpm         曲速（每分钟拍数）
     * @param basePitch   主调基准音高（例如 60 代表 C4 中央 C）
     * @param rightMelody 右手主旋律简谱字符串
     * @param leftChords  左手伴奏简谱字符串
     */
    public static Sequence compileJianpu(int bpm, int basePitch,
                                         String rightMelody, String leftChords) throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();

        int mpqn = 60_000_000 / Math.max(20, bpm);
        byte[] tempoBytes = {
                (byte) ((mpqn >> 16) & 0xFF),
                (byte) ((mpqn >> 8) & 0xFF),
                (byte) (mpqn & 0xFF)
        };
        MetaMessage tempoMessage = new MetaMessage();
        tempoMessage.setMessage(0x51, tempoBytes, 3);
        track.add(new MidiEvent(tempoMessage, 0));

        compileTrackTokens(track, rightMelody, basePitch, 100);
        if (leftChords != null && !leftChords.isBlank()) {
            compileTrackTokens(track, leftChords, basePitch - 12, 78);
        }
        return sequence;
    }

    private static void compileTrackTokens(Track track, String scoreText,
                                           int basePitch, int velocity) throws Exception {
        long currentTick = 0;
        String[] tokens = scoreText.trim().split("\s+");

        for (String token : tokens) {
            if (token.isEmpty() || token.equals("|")) {
                continue;
            }

            int degree = -1;
            int octaveShift = 0;
            int sharpShift = 0;
            double beatMultiplier = 1.0;

            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                if (c >= '0' && c <= '7') {
                    degree = c - '0';
                } else if (c == '+') {
                    octaveShift += 12;
                } else if (c == '-') {
                    octaveShift -= 12;
                } else if (c == '#') {
                    sharpShift += 1;
                } else if (c == 'b') {
                    sharpShift -= 1;
                } else if (c == '/') {
                    beatMultiplier *= 0.5;
                } else if (c == '~') {
                    beatMultiplier += 1.0;
                } else if (c == '.') {
                    beatMultiplier *= 1.5;
                }
            }

            long durationTicks = Math.round(PPQ * beatMultiplier);
            if (degree >= 1 && degree <= 7) {
                int pitch = basePitch + MAJOR_SCALE_OFFSETS[degree - 1] + octaveShift + sharpShift;
                pitch = Math.max(21, Math.min(108, pitch));
                long gateTicks = Math.max(20, (long) (durationTicks * 0.94));
                track.add(new MidiEvent(
                        new ShortMessage(ShortMessage.NOTE_ON, 0, pitch, velocity), currentTick));
                track.add(new MidiEvent(
                        new ShortMessage(ShortMessage.NOTE_OFF, 0, pitch, 0),
                        currentTick + gateTicks));
            }
            currentTick += durationTicks;
        }
    }

    /**
     * 内置《卡农》经典片段（双轨简谱），无需任何外部 MIDI 文件即可演示。
     */
    public static Sequence createBuiltInJianpuDemo() throws Exception {
        String rightHand =
                "3+ 2+ | 1+ 7~ | 6~ 5~ | 6~ 7~ | "
                + "1+ 3+ 5+ 3+ | 2+ 7 2+ 5 | 1+ 6 1+ 6 | 5 7 2+ 7 | "
                + "6/ 5/ 6/ 7/ 1+/ 2+/ 3+/ 5+/ | 4+/ 3+/ 2+/ 4+/ 3+/ 2+/ 1+/ 7/ | "
                + "6/ 5/ 4/ 6/ 5/ 4/ 3/ 5/ | 4/ 5/ 6/ 7/ 1+~";

        String leftHand =
                "1/ 5/ 1+/ 5/ 5-/ 2/ 5/ 2/ | 6-/ 3/ 6/ 3/ 3-/ 7-/ 3/ 7-/ | "
                + "4-/ 1/ 4/ 1/ 1-/ 5-/ 1/ 5-/ | 4-/ 1/ 4/ 1/ 5-/ 2/ 5/ 2/ | "
                + "1/ 5/ 1+/ 5/ 5-/ 2/ 5/ 2/ | 6-/ 3/ 6/ 3/ 3-/ 7-/ 3/ 7-/ | "
                + "4-/ 1/ 4/ 1/ 1-/ 5-/ 1/ 5-/ | 4-/ 1/ 4/ 1/ 5-/ 1/ 5/ 1~";

        return compileJianpu(96, 60, rightHand, leftHand);
    }
}
