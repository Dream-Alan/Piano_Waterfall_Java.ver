package com.piano.waterfall.midi;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
     * 简谱文件内容。
     *
     * @param title     曲名
     * @param bpm       曲速
     * @param basePitch 1 对应的 MIDI 音高
     * @param right     右手简谱
     * @param left      左手简谱
     */
    public record JianpuDocument(String title, int bpm, int basePitch,
                                 String right, String left) {
    }

    /**
     * 读取简谱文件。
     *
     * <p>支持两种写法：</p>
     * <pre>
     * title=小星星
     * bpm=96
     * key=C
     * right: 1 1 5 5 6 6 5~
     * left: 1/ 5/ 1/ 5/
     * </pre>
     * <p>或直接两行：第一行右手，第二行左手。键名还可用 base=60 直接指定。</p>
     */
    public static JianpuDocument readJianpuFile(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        String title = path.getFileName().toString().replaceFirst("[.][^.]+$", "");
        int bpm = 96;
        Integer explicitBase = null;
        String key = "C";
        String right = null;
        String left = "";
        List<String> plainLines = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            int separator = fieldSeparator(line);
            if (separator > 0) {
                String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(separator + 1).trim();
                switch (name) {
                    case "title", "name" -> title = value;
                    case "bpm", "tempo" -> bpm = parseInt(value, bpm);
                    case "key" -> key = value;
                    case "base", "tonic" -> explicitBase = parseInt(value, 60);
                    case "right", "melody", "main", "right:" -> right = value;
                    case "left", "chord", "chords", "accompaniment" -> left = value;
                    default -> plainLines.add(line);
                }
            } else {
                plainLines.add(line);
            }
        }

        if (right == null) {
            if (!plainLines.isEmpty()) {
                right = plainLines.get(0);
                plainLines.remove(0);
            } else {
                throw new IOException("简谱文件中没有右手旋律");
            }
        }
        if (left == null || left.isBlank()) {
            left = String.join(" ", plainLines);
        }
        int basePitch = explicitBase != null ? explicitBase : keyToMidi(key);
        return new JianpuDocument(title, Math.max(20, Math.min(300, bpm)),
                Math.max(21, Math.min(108, basePitch)), right, left);
    }

    /** 把 Sequence 写成标准 MIDI 文件（type 1）。 */
    public static void saveMidi(Sequence sequence, File output) throws IOException {
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent);
        }
        int written = MidiSystem.write(sequence, 1, output);
        if (written <= 0) {
            throw new IOException("MIDI 写入失败: " + output);
        }
    }

    private static int fieldSeparator(String line) {
        int colon = line.indexOf(':');
        int equals = line.indexOf('=');
        if (colon <= 0) {
            return equals;
        }
        if (equals <= 0) {
            return colon;
        }
        return Math.min(colon, equals);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int keyToMidi(String key) {
        String normalized = key == null ? "C" : key.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            normalized = "C";
        }
        int pitch = switch (normalized.charAt(0)) {
            case 'C' -> 60;
            case 'D' -> 62;
            case 'E' -> 64;
            case 'F' -> 65;
            case 'G' -> 67;
            case 'A' -> 69;
            case 'B' -> 71;
            default -> 60;
        };
        if (normalized.contains("#")) {
            pitch += 1;
        } else if (normalized.contains("B") && normalized.length() > 1
                && normalized.charAt(1) == 'B') {
            pitch -= 1;
        }
        return pitch;
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
