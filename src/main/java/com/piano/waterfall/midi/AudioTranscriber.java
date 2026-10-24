package com.piano.waterfall.midi;

import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 音频转 MIDI 桥接器。
 *
 * <p>优先调用本项目的 Python 实现（hFT-Transformer / CRNN 双引擎），
 * 其次尝试 Spotify basic-pitch 命令行，最后回退到 piano_transcription_inference。
 * Python 解释器可以用系统属性 {@code -Dpiano.python=...} 或环境变量
 * {@code PIANO_PYTHON} 指定。</p>
 */
public final class AudioTranscriber {

    private static final long TIMEOUT_MINUTES = 12;

    private AudioTranscriber() {
    }

    public static void transcribeAudioAsync(File audioFile,
                                            Consumer<String> onProgress,
                                            Consumer<File> onSuccess,
                                            Consumer<String> onError) {
        new Thread(() -> {
            List<String> failures = new ArrayList<>();
            try {
                File outDir = Files.createTempDirectory("waterfall_ai_midi_").toFile();
                String baseName = audioFile.getName().replaceFirst("[.][^.]+$", "");
                File midi = new File(outDir, baseName + ".mid");

                Platform.runLater(() -> onProgress.accept(
                        "🤖 正在调用 AI 扒谱引擎: " + audioFile.getName() + " ..."));

                if (runPythonEngine(audioFile, midi, onProgress, failures)) {
                    Platform.runLater(() -> onSuccess.accept(midi));
                    return;
                }

                File basicPitchMidi = new File(outDir, baseName + "_basic_pitch.mid");
                if (runBasicPitch(audioFile, outDir, basicPitchMidi, onProgress, failures)) {
                    Platform.runLater(() -> onSuccess.accept(basicPitchMidi));
                    return;
                }

                if (runPianoTranscription(audioFile, midi, onProgress, failures)) {
                    Platform.runLater(() -> onSuccess.accept(midi));
                    return;
                }

                String detail = failures.isEmpty() ? "未知原因" : String.join("；", failures);
                Platform.runLater(() -> onError.accept(
                        "AI 扒谱失败。请安装 Python 版 Piano Waterfall（pip install -e .）、"
                                + "basic-pitch 或 piano-transcription-inference。详情: " + detail));
            } catch (Exception exception) {
                Platform.runLater(() -> onError.accept("AI 转录异常: " + exception.getMessage()));
            }
        }, "AI-Audio-Transcriber").start();
    }

    private static boolean runPythonEngine(File audioFile, File midi,
                                           Consumer<String> onProgress, List<String> failures) {
        String script = String.join("\n",
                "from pathlib import Path",
                "from piano_waterfall.transcribe import transcribe",
                "transcribe(Path(r'" + escape(audioFile.getAbsolutePath()) + "'),",
                "Path(r'" + escape(midi.getAbsolutePath()) + "'), engine='hft')",
                "print('OK', r'" + escape(midi.getAbsolutePath()) + "')");
        boolean ok = runProcess(List.of(pythonExecutable(), "-c", script),
                onProgress, failures, "python piano_waterfall");
        return ok && midi.isFile() && midi.length() > 0;
    }

    private static boolean runBasicPitch(File audioFile, File outDir, File expectedMidi,
                                         Consumer<String> onProgress, List<String> failures) {
        boolean ok = runProcess(List.of("basic-pitch", outDir.getAbsolutePath(),
                        audioFile.getAbsolutePath()),
                onProgress, failures, "basic-pitch");
        return ok && expectedMidi.isFile() && expectedMidi.length() > 0;
    }

    private static boolean runPianoTranscription(File audioFile, File midi,
                                                 Consumer<String> onProgress,
                                                 List<String> failures) {
        String script = String.join("\n",
                "from piano_transcription_inference import PianoTranscription, sample_rate, load_audio",
                "audio, _ = load_audio(r'" + escape(audioFile.getAbsolutePath()) + "', sr=sample_rate, mono=True)",
                "PianoTranscription(device='cpu').transcribe(audio, r'"
                        + escape(midi.getAbsolutePath()) + "')",
                "print('OK')");
        boolean ok = runProcess(List.of(pythonExecutable(), "-c", script),
                onProgress, failures, "piano_transcription_inference");
        return ok && midi.isFile() && midi.length() > 0;
    }

    private static boolean runProcess(List<String> command, Consumer<String> onProgress,
                                      List<String> failures, String label) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String text = line.trim();
                    if (!text.isEmpty()) {
                        System.out.println("[" + label + "] " + text);
                        Platform.runLater(() -> onProgress.accept(text));
                    }
                }
            }
            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                failures.add(label + " 超时");
                return false;
            }
            if (process.exitValue() != 0) {
                failures.add(label + " 退出码 " + process.exitValue());
                return false;
            }
            return true;
        } catch (Exception exception) {
            failures.add(label + ": " + exception.getMessage());
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String pythonExecutable() {
        String configured = System.getProperty("piano.python");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PIANO_PYTHON");
        }
        return (configured == null || configured.isBlank()) ? "python" : configured;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
