package com.piano.waterfall.midi;

import com.piano.waterfall.model.NoteBlock;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

/**
 * 使用 Python 版的钢琴采样离线渲染整首曲子为 WAV。
 *
 * <p>每个音从最近的根音采样按半音比变速，保留真实钢琴的起音和衰减；
 * 混音后再按音色预设做滤波、软削波和混响。</p>
 */
public final class SampledPianoEngine {

    public static final int SAMPLE_RATE = 44_100;

    private static final int[] ROOT_PITCHES = {
            33, 36, 39, 42, 45, 48, 51, 54, 57, 60, 63, 66, 69,
            72, 75, 78, 81, 84, 87, 90, 93, 96, 99, 102, 105, 108
    };
    private static final String[] ROOT_NAMES = {
            "A1", "C2", "Ds2", "Fs2", "A2", "C3", "Ds3", "Fs3", "A3", "C4",
            "Ds4", "Fs4", "A4", "C5", "Ds5", "Fs5", "A5", "C6", "Ds6", "Fs6",
            "A6", "C7", "Ds7", "Fs7", "A7", "C8"
    };

    private static final Map<Integer, Sample> SAMPLES = new HashMap<>();

    private SampledPianoEngine() {
    }

    /**
     * 渲染并返回 WAV 文件；同一个 key/音色会命中本地缓存。
     */
    public static File render(String cacheKey, List<NoteBlock> notes, PianoVoice voice,
                              DoubleConsumer progress) throws Exception {
        File cached = cacheFile(cacheKey, voice);
        if (cached.isFile() && cached.length() > 2_000) {
            if (progress != null) {
                progress.accept(100.0);
            }
            return cached;
        }

        double end = 0.0;
        for (NoteBlock note : notes) {
            end = Math.max(end, note.endTime());
        }
        int frames = (int) Math.ceil((end + 2.2) * SAMPLE_RATE);
        if (frames <= 0) {
            frames = SAMPLE_RATE;
        }
        float[] left = new float[frames];
        float[] right = new float[frames];

        int done = 0;
        for (NoteBlock note : notes) {
            renderNote(note, voice, left, right);
            done++;
            if (progress != null && (done % 32 == 0 || done == notes.size())) {
                progress.accept(done * 100.0 / notes.size());
            }
        }

        applyTone(left, right, voice);
        normalize(left, right, 0.88f);
        writeWav(cached, left, right);
        return cached;
    }

    private static void renderNote(NoteBlock note, PianoVoice voice,
                                   float[] left, float[] right) {
        int rootIndex = nearestRootIndex(note.pitch());
        Sample sample = sampleFor(rootIndex);
        if (sample == null || sample.data.length < 8) {
            return;
        }
        int rootPitch = ROOT_PITCHES[rootIndex];
        double ratio = Math.pow(2.0, (note.pitch() - rootPitch) / 12.0);
        int startFrame = (int) Math.round(note.startTime() * SAMPLE_RATE);
        if (startFrame < 0 || startFrame >= left.length) {
            return;
        }
        int noteFrames = (int) Math.round(Math.max(0.04, note.endTime() - note.startTime())
                * SAMPLE_RATE);
        int maxFrames = Math.min(left.length - startFrame,
                (int) ((sample.data.length - 1) / ratio));
        maxFrames = Math.min(maxFrames, noteFrames + (int) (0.35 * SAMPLE_RATE));
        if (maxFrames <= 0) {
            return;
        }

        double velocity = Math.pow(note.velocity() / 127.0, 1.4);
        double gain = velocity * voice.gain() * 0.55;
        double pan = Math.max(-1.0, Math.min(1.0, (note.pitch() - 60) / 48.0)) * 0.35;
        double panAngle = (pan + 1.0) * Math.PI / 4.0;
        float leftGain = (float) (Math.cos(panAngle) * gain);
        float rightGain = (float) (Math.sin(panAngle) * gain);
        int attackFrames = (int) (0.0025 * SAMPLE_RATE);
        int releaseFrames = (int) (0.28 * SAMPLE_RATE);

        int sourceLength = sample.data.length;
        for (int i = 0; i < maxFrames; i++) {
            double sourcePosition = i * ratio;
            int index = (int) sourcePosition;
            if (index >= sourceLength - 1) {
                break;
            }
            double fraction = sourcePosition - index;
            float value = (float) (sample.data[index] * (1.0 - fraction)
                    + sample.data[index + 1] * fraction);

            double envelope = Math.min(1.0, i / (double) attackFrames);
            int afterNoteEnd = i - noteFrames;
            if (afterNoteEnd > 0) {
                envelope *= Math.max(0.0, 1.0 - afterNoteEnd / (double) releaseFrames);
            }
            float sampleValue = (float) (value * envelope);
            int target = startFrame + i;
            left[target] += sampleValue * leftGain;
            right[target] += sampleValue * rightGain;
        }
    }

    private static int nearestRootIndex(int pitch) {
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < ROOT_PITCHES.length; i++) {
            int distance = Math.abs(ROOT_PITCHES[i] - pitch);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static synchronized Sample sampleFor(int rootIndex) {
        Sample sample = SAMPLES.get(rootIndex);
        if (sample != null) {
            return sample;
        }
        String resource = "/samples/piano/" + ROOT_NAMES[rootIndex] + ".wav";
        try (InputStream input = SampledPianoEngine.class.getResourceAsStream(resource)) {
            if (input == null) {
                return null;
            }
            try (AudioInputStream stream = AudioSystem.getAudioInputStream(
                    new BufferedInputStream(input))) {
                AudioFormat format = stream.getFormat();
                AudioFormat pcm = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        format.getSampleRate(), 16,
                        format.getChannels(), format.getChannels() * 2,
                        format.getSampleRate(), false);
                try (AudioInputStream converted = AudioSystem.getAudioInputStream(pcm, stream)) {
                    byte[] bytes = converted.readAllBytes();
                    int channels = Math.max(1, format.getChannels());
                    float[] mono = new float[bytes.length / (2 * channels)];
                    for (int frame = 0; frame < mono.length; frame++) {
                        float sum = 0.0f;
                        for (int channel = 0; channel < channels; channel++) {
                            int offset = (frame * channels + channel) * 2;
                            short value = (short) ((bytes[offset] & 0xFF)
                                    | (bytes[offset + 1] << 8));
                            sum += value / 32768.0f;
                        }
                        mono[frame] = sum / channels;
                    }
                    if (format.getSampleRate() != SAMPLE_RATE) {
                        mono = resample(mono, format.getSampleRate(), SAMPLE_RATE);
                    }
                    sample = new Sample(mono);
                }
            }
        } catch (Exception exception) {
            return null;
        }
        SAMPLES.put(rootIndex, sample);
        return sample;
    }

    private static float[] resample(float[] input, float fromRate, float toRate) {
        int outputLength = Math.max(1, (int) Math.round(input.length * toRate / fromRate));
        float[] output = new float[outputLength];
        double step = (double) fromRate / toRate;
        for (int i = 0; i < outputLength; i++) {
            double position = i * step;
            int index = (int) position;
            if (index >= input.length - 1) {
                output[i] = input[input.length - 1];
                continue;
            }
            double fraction = position - index;
            output[i] = (float) (input[index] * (1.0 - fraction)
                    + input[index + 1] * fraction);
        }
        return output;
    }

    private static void applyTone(float[] left, float[] right, PianoVoice voice) {
        double lowpassCoefficient = 1.0 - Math.exp(-2.0 * Math.PI
                * Math.min(voice.lowpassHz(), SAMPLE_RATE * 0.45) / SAMPLE_RATE);
        double highpassCoefficient = 1.0 - Math.exp(-2.0 * Math.PI
                * Math.min(voice.highpassHz(), SAMPLE_RATE * 0.45) / SAMPLE_RATE);

        float previousLeftLow = 0.0f;
        float previousRightLow = 0.0f;
        float previousLeftHigh = 0.0f;
        float previousRightHigh = 0.0f;
        float previousLeftInput = 0.0f;
        float previousRightInput = 0.0f;
        double drive = Math.max(1.0, voice.drive());

        for (int i = 0; i < left.length; i++) {
            float inputLeft = left[i];
            float inputRight = right[i];

            previousLeftLow += (float) (lowpassCoefficient * (inputLeft - previousLeftLow));
            previousRightLow += (float) (lowpassCoefficient * (inputRight - previousRightLow));
            float lowLeft = previousLeftLow;
            float lowRight = previousRightLow;

            previousLeftHigh += (float) (highpassCoefficient * (lowLeft - previousLeftHigh));
            previousRightHigh += (float) (highpassCoefficient * (lowRight - previousRightHigh));
            float highLeft = lowLeft - previousLeftHigh;
            float highRight = lowRight - previousRightHigh;

            left[i] = (float) (Math.tanh(highLeft * drive) / Math.tanh(drive));
            right[i] = (float) (Math.tanh(highRight * drive) / Math.tanh(drive));
            previousLeftInput = inputLeft;
            previousRightInput = inputRight;
        }
        if (voice != PianoVoice.GRAND && voice != PianoVoice.BRIGHT) {
            // keep the variables used on all paths without changing the signal
            previousLeftInput += previousRightInput * 0.0f;
        }

        if (voice.reverbMix() > 0.0) {
            addReverb(left, right, voice.reverbMix());
        }
    }

    private static void addReverb(float[] left, float[] right, double mix) {
        int delay = (int) (0.085 * SAMPLE_RATE);
        double feedback = 0.28;
        for (int i = delay; i < left.length; i++) {
            left[i] += (float) (right[i - delay] * mix * feedback);
            right[i] += (float) (left[i - delay] * mix * feedback);
        }
    }

    private static void normalize(float[] left, float[] right, float target) {
        float peak = 1.0e-6f;
        for (int i = 0; i < left.length; i++) {
            peak = Math.max(peak, Math.abs(left[i]));
            peak = Math.max(peak, Math.abs(right[i]));
        }
        float scale = peak > target ? target / peak : 1.0f;
        for (int i = 0; i < left.length; i++) {
            left[i] *= scale;
            right[i] *= scale;
        }
    }

    private static void writeWav(File output, float[] left, float[] right) throws Exception {
        File parent = output.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        int frames = Math.min(left.length, right.length);
        byte[] bytes = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            short l = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, Math.round(left[i] * 32767.0f)));
            short r = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, Math.round(right[i] * 32767.0f)));
            int offset = i * 4;
            bytes[offset] = (byte) (l & 0xFF);
            bytes[offset + 1] = (byte) ((l >> 8) & 0xFF);
            bytes[offset + 2] = (byte) (r & 0xFF);
            bytes[offset + 3] = (byte) ((r >> 8) & 0xFF);
        }
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes);
             AudioInputStream stream = new AudioInputStream(input, format, frames)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, output);
        }
    }

    public static File cacheFile(String cacheKey, PianoVoice voice) {
        String base = System.getenv().getOrDefault("LOCALAPPDATA",
                System.getProperty("user.home", "."));
        Path directory = Path.of(base, "PianoWaterfall", "java-sample-cache");
        if (!Files.isWritable(directory) && !Files.isWritable(directory.getParent())) {
            directory = Path.of(System.getProperty("java.io.tmpdir", "."),
                    "piano-waterfall-sample-cache");
        }
        int hash = 0;
        for (int i = 0; i < cacheKey.length(); i++) {
            hash = 31 * hash + cacheKey.charAt(i);
        }
        return directory.resolve("midi-" + Integer.toHexString(hash)
                + "-" + voice.name().toLowerCase() + ".wav").toFile();
    }

    private record Sample(float[] data) {
    }
}
