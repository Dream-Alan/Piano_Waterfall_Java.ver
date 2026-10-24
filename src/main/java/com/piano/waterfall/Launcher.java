package com.piano.waterfall;

import com.piano.waterfall.midi.ScoreCompiler;

import javax.sound.midi.Sequence;
import java.io.File;
import java.nio.file.Path;

/**
 * 启动引导类。
 *
 * <p>在 IntelliJ IDEA 中直接运行继承 {@link javafx.application.Application}
 * 的类可能报 “JavaFX runtime components are missing”，运行这个普通类即可。</p>
 *
 * <p>同时提供简谱转 MIDI 的命令行模式：</p>
 * <pre>
 * java -cp ... com.piano.waterfall.Launcher --jianpu song.txt --out song.mid
 * </pre>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && isJianpuCommand(args[0])) {
            System.exit(compileJianpu(args));
        }
        App.main(args);
    }

    private static boolean isJianpuCommand(String arg) {
        return "--jianpu".equalsIgnoreCase(arg)
                || "-j".equalsIgnoreCase(arg)
                || "--jianpu-to-midi".equalsIgnoreCase(arg);
    }

    private static int compileJianpu(String[] args) {
        Path input = null;
        File output = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--out", "-o" -> {
                    if (i + 1 < args.length) {
                        output = new File(args[++i]);
                    }
                }
                case "--help", "-h" -> {
                    printUsage();
                    return 0;
                }
                default -> {
                    if (input == null && !args[i].startsWith("-")) {
                        input = Path.of(args[i]);
                    }
                }
            }
        }
        if (input == null) {
            printUsage();
            return 2;
        }
        if (output == null) {
            String name = input.getFileName().toString().replaceFirst("[.][^.]+$", "");
            output = new File(input.toAbsolutePath().getParent().toFile(), name + ".mid");
        }
        try {
            ScoreCompiler.JianpuDocument document = ScoreCompiler.readJianpuFile(input);
            Sequence sequence = ScoreCompiler.compileJianpu(
                    document.bpm(), document.basePitch(), document.right(), document.left());
            ScoreCompiler.saveMidi(sequence, output);
            System.out.println("已生成 MIDI: " + output.getAbsolutePath());
            System.out.println("曲名: " + document.title() + "  BPM: " + document.bpm()
                    + "  1=" + document.basePitch());
            return 0;
        } catch (Exception exception) {
            System.err.println("简谱转 MIDI 失败: " + exception.getMessage());
            return 1;
        }
    }

    private static void printUsage() {
        System.out.println("""
                用法:
                  java -cp ... com.piano.waterfall.Launcher [MIDI 文件]
                  java -cp ... com.piano.waterfall.Launcher --jianpu <简谱.txt> [--out <输出.mid>]

                简谱文件格式:
                  title=小星星
                  bpm=96
                  key=C
                  right: 1 1 5 5 6 6 5~
                  left: 1/ 5/ 1/ 5/

                也支持直接两行：第一行右手，第二行左手。
                语法: 1~7 音符 | 0 休止 | + 高八度 | - 低八度 | #/b 升降 | / 八分音符 | ~ 延长一拍 | . 附点
                """);
    }
}
