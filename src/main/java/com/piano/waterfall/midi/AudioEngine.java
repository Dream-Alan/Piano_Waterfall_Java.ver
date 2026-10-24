package com.piano.waterfall.midi;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import java.io.File;

/**
 * MIDI 播放引擎：Sequencer + Synthesizer，并提供与音频硬件同步的秒级时钟。
 *
 * <p>可以额外挂载原曲 MP3/WAV：挂载后以原曲播放器的硬件时钟为主时钟，
 * 保证“原曲音频 + MIDI 瀑布流”不会随着帧率漂移。</p>
 */
public class AudioEngine {

    /** 前导时间（秒）：让音符从顶部平滑落到判定线后才开始发声。 */
    private static final double LEAD_IN_SEC = 2.0;

    private Sequencer sequencer;
    private Synthesizer synthesizer;
    private Sequence sequence;
    private MediaPlayer externalAudioPlayer;
    private boolean muteMidiSynth;

    private long leadInStartNano;
    private double pausedTimeSec = -LEAD_IN_SEC;
    private boolean audioStarted;
    private boolean paused;

    public void init(Sequence sequence, File customSoundFont) throws Exception {
        stop();
        this.sequence = sequence;

        synthesizer = MidiSystem.getSynthesizer();
        synthesizer.open();
        loadSoundFont(customSoundFont);

        sequencer = MidiSystem.getSequencer(false);
        sequencer.open();
        sequencer.getTransmitter().setReceiver(synthesizer.getReceiver());
        sequencer.setSequence(sequence);
        applyMidiMuteState();
    }

    private void loadSoundFont(File customSoundFont) {
        if (customSoundFont == null || !customSoundFont.isFile()) {
            return;
        }
        try {
            Soundbank soundbank = MidiSystem.getSoundbank(customSoundFont);
            synthesizer.loadAllInstruments(soundbank);
        } catch (Exception ignored) {
            // 音色库损坏或格式不支持时，继续使用 JDK 自带音色。
        }
    }

    /**
     * 挂载外部原曲音频，并与 MIDI 播放同步。
     *
     * @param audioFile               mp3 / wav 原曲
     * @param muteMidiWhenPlayingAudio 播放原曲时是否静音 MIDI 合成器
     */
    public void attachExternalAudio(File audioFile, boolean muteMidiWhenPlayingAudio) {
        disposeExternalAudio();
        muteMidiSynth = false;
        if (audioFile != null && audioFile.isFile()) {
            try {
                Media media = new Media(audioFile.toURI().toString());
                externalAudioPlayer = new MediaPlayer(media);
                externalAudioPlayer.setOnError(() ->
                        System.err.println("原曲音频错误: " + externalAudioPlayer.getError()));
                muteMidiSynth = muteMidiWhenPlayingAudio;
            } catch (Exception exception) {
                System.err.println("无法加载原曲音频: " + exception.getMessage());
                externalAudioPlayer = null;
            }
        }
        applyMidiMuteState();
    }

    public boolean hasExternalAudio() {
        return externalAudioPlayer != null;
    }

    public void toggleMidiMute() {
        muteMidiSynth = !muteMidiSynth;
        applyMidiMuteState();
    }

    public boolean isMidiMuted() {
        return muteMidiSynth;
    }

    private void applyMidiMuteState() {
        if (synthesizer == null) {
            return;
        }
        MidiChannel[] channels = synthesizer.getChannels();
        if (channels == null) {
            return;
        }
        for (MidiChannel channel : channels) {
            if (channel != null) {
                channel.setMute(muteMidiSynth);
            }
        }
    }

    public void start() {
        leadInStartNano = System.nanoTime();
        pausedTimeSec = -LEAD_IN_SEC;
        audioStarted = false;
        paused = false;
        if (externalAudioPlayer != null) {
            externalAudioPlayer.stop();
            externalAudioPlayer.seek(Duration.ZERO);
        }
    }

    /**
     * 当前播放时间（秒）。前导阶段返回负数。
     */
    public double getCurrentTimeSec() {
        if (paused) {
            return pausedTimeSec;
        }
        if (!audioStarted) {
            double elapsed = (System.nanoTime() - leadInStartNano) / 1_000_000_000.0 - LEAD_IN_SEC;
            if (elapsed >= 0.0) {
                try {
                    sequencer.setMicrosecondPosition(0);
                    sequencer.start();
                } catch (Exception ignored) {
                    // 没有音频设备时仍允许画面继续播放。
                }
                startExternalAudio(0.0);
                audioStarted = true;
                pausedTimeSec = 0.0;
                return 0.0;
            }
            return elapsed;
        }
        if (externalAudioPlayer != null
                && externalAudioPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
            double mediaSec = externalAudioPlayer.getCurrentTime().toSeconds();
            if (mediaSec > 0.01) {
                return mediaSec;
            }
        }
        if (sequencer == null) {
            return pausedTimeSec;
        }
        return sequencer.getMicrosecondPosition() / 1_000_000.0;
    }

    public void togglePause() {
        if (sequencer == null) {
            return;
        }
        if (!paused) {
            pausedTimeSec = getCurrentTimeSec();
            paused = true;
            if (audioStarted) {
                if (sequencer.isRunning()) {
                    sequencer.stop();
                }
                if (externalAudioPlayer != null) {
                    externalAudioPlayer.pause();
                }
                silenceAllChannels();
            }
        } else {
            paused = false;
            if (pausedTimeSec < 0) {
                leadInStartNano = System.nanoTime()
                        - (long) ((pausedTimeSec + LEAD_IN_SEC) * 1_000_000_000.0);
            } else {
                audioStarted = true;
                sequencer.setMicrosecondPosition((long) (pausedTimeSec * 1_000_000.0));
                sequencer.start();
                startExternalAudio(pausedTimeSec);
            }
        }
    }

    /** 跳转到绝对秒数。 */
    public void seekToSeconds(double targetSec) {
        if (sequencer == null) {
            return;
        }
        double target = Math.max(0.0, targetSec);
        long maxMicros = Math.max(0L, sequencer.getMicrosecondLength());
        long targetMicros = Math.min(maxMicros, (long) (target * 1_000_000.0));

        silenceAllChannels();
        audioStarted = true;
        sequencer.setMicrosecondPosition(targetMicros);
        pausedTimeSec = targetMicros / 1_000_000.0;
        if (externalAudioPlayer != null) {
            externalAudioPlayer.seek(Duration.seconds(pausedTimeSec));
        }
        if (!paused) {
            if (!sequencer.isRunning() && targetMicros < maxMicros) {
                sequencer.start();
            }
            startExternalAudio(pausedTimeSec);
        }
    }

    /** 相对快进/快退指定秒数，例如 +5.0 或 -5.0。 */
    public void seekRelative(double deltaSeconds) {
        seekToSeconds(getCurrentTimeSec() + deltaSeconds);
    }

    /** 从头开始，重新落入。 */
    public void restart() {
        if (sequencer == null) {
            return;
        }
        sequencer.stop();
        sequencer.setMicrosecondPosition(0);
        silenceAllChannels();
        start();
    }

    public boolean isPaused() {
        return paused;
    }

    public double getDurationSec() {
        double duration = 0.0;
        if (sequencer != null && sequencer.getMicrosecondLength() > 0) {
            duration = sequencer.getMicrosecondLength() / 1_000_000.0;
        } else if (sequence != null) {
            duration = sequence.getMicrosecondLength() / 1_000_000.0;
        }
        if (externalAudioPlayer != null) {
            Duration mediaDuration = externalAudioPlayer.getTotalDuration();
            if (mediaDuration != null) {
                duration = Math.max(duration, mediaDuration.toSeconds());
            }
        }
        return duration;
    }

    private void startExternalAudio(double seconds) {
        if (externalAudioPlayer == null) {
            return;
        }
        try {
            externalAudioPlayer.seek(Duration.seconds(Math.max(0.0, seconds)));
            externalAudioPlayer.play();
        } catch (Exception exception) {
            System.err.println("原曲音频播放失败: " + exception.getMessage());
        }
    }

    private void disposeExternalAudio() {
        if (externalAudioPlayer != null) {
            externalAudioPlayer.stop();
            externalAudioPlayer.dispose();
            externalAudioPlayer = null;
        }
    }

    private void silenceAllChannels() {
        if (synthesizer == null) {
            return;
        }
        MidiChannel[] channels = synthesizer.getChannels();
        if (channels == null) {
            return;
        }
        for (MidiChannel channel : channels) {
            if (channel != null) {
                channel.allNotesOff();
            }
        }
    }

    public void stop() {
        silenceAllChannels();
        disposeExternalAudio();
        if (sequencer != null && sequencer.isOpen()) {
            sequencer.close();
        }
        if (synthesizer != null && synthesizer.isOpen()) {
            synthesizer.close();
        }
        sequencer = null;
        synthesizer = null;
    }
}
