package com.piano.waterfall.midi;

import com.piano.waterfall.model.NoteBlock;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 MIDI 事件转换为以秒为单位的音符列表。
 *
 * <p>MIDI 文件里的时间单位是 tick，且曲中可能多次变速（Set Tempo）。
 * 因此这里先按 tick 排序所有轨道的事件，再逐段累加真实时间。</p>
 */
public final class MidiParser {

    private static final int DEFAULT_TEMPO = 500_000; // 120 BPM

    private MidiParser() {
    }

    public static List<NoteBlock> parseMidi(File midiFile) throws Exception {
        Sequence sequence = MidiSystem.getSequence(midiFile);
        return parseSequence(sequence);
    }

    public static List<NoteBlock> parseSequence(Sequence sequence) {
        int resolution = sequence.getResolution();
        if (resolution <= 0) {
            throw new IllegalArgumentException("Invalid MIDI resolution: " + resolution);
        }

        List<MidiEvent> allEvents = new ArrayList<>();
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                allEvents.add(track.get(i));
            }
        }
        allEvents.sort(Comparator
                .comparingLong(MidiEvent::getTick)
                .thenComparingInt(event -> eventPriority(event.getMessage())));

        List<NoteBlock> notes = new ArrayList<>();
        Map<Integer, Double> activeNotes = new HashMap<>();
        Map<Integer, Integer> activeVelocities = new HashMap<>();

        long lastTick = 0;
        double currentTimeSec = 0.0;
        double microsecondsPerQuarterNote = DEFAULT_TEMPO;

        for (MidiEvent event : allEvents) {
            long currentTick = event.getTick();
            long deltaTicks = currentTick - lastTick;
            currentTimeSec += (deltaTicks * microsecondsPerQuarterNote / resolution) / 1_000_000.0;
            lastTick = currentTick;

            MidiMessage message = event.getMessage();
            if (message instanceof MetaMessage meta && meta.getType() == 0x51) {
                byte[] data = meta.getData();
                if (data.length >= 3) {
                    microsecondsPerQuarterNote = ((data[0] & 0xFF) << 16)
                            | ((data[1] & 0xFF) << 8)
                            | (data[2] & 0xFF);
                }
            } else if (message instanceof ShortMessage shortMessage) {
                int command = shortMessage.getCommand();
                int channel = shortMessage.getChannel();
                int pitch = shortMessage.getData1();
                int velocity = shortMessage.getData2();

                if (channel == 9 || pitch < 21 || pitch > 108) {
                    continue;
                }

                if (command == ShortMessage.NOTE_ON && velocity > 0) {
                    activeNotes.putIfAbsent(pitch, currentTimeSec);
                    activeVelocities.putIfAbsent(pitch, velocity);
                } else if (command == ShortMessage.NOTE_OFF
                        || (command == ShortMessage.NOTE_ON && velocity == 0)) {
                    Double startTime = activeNotes.remove(pitch);
                    Integer noteVelocity = activeVelocities.remove(pitch);
                    if (startTime != null && currentTimeSec > startTime) {
                        notes.add(new NoteBlock(
                                pitch,
                                startTime,
                                currentTimeSec,
                                noteVelocity != null ? noteVelocity : 90));
                    }
                }
            }
        }

        // 没有 note-off 的悬挂音符：在序列末尾收尾。
        double sequenceEnd = Math.max(currentTimeSec, sequence.getMicrosecondLength() / 1_000_000.0);
        for (Map.Entry<Integer, Double> entry : activeNotes.entrySet()) {
            if (sequenceEnd > entry.getValue()) {
                int pitch = entry.getKey();
                int velocity = activeVelocities.getOrDefault(pitch, 90);
                notes.add(new NoteBlock(pitch, entry.getValue(), sequenceEnd, velocity));
            }
        }

        notes.sort(Comparator
                .comparingDouble(NoteBlock::startTime)
                .thenComparingInt(NoteBlock::pitch));
        return notes;
    }

    private static int eventPriority(MidiMessage message) {
        if (message instanceof MetaMessage meta && meta.getType() == 0x51) {
            return 0;
        }
        if (message instanceof ShortMessage shortMessage) {
            if (shortMessage.getCommand() == ShortMessage.NOTE_OFF
                    || (shortMessage.getCommand() == ShortMessage.NOTE_ON
                    && shortMessage.getData2() == 0)) {
                return 1;
            }
            if (shortMessage.getCommand() == ShortMessage.NOTE_ON) {
                return 2;
            }
        }
        return 3;
    }
}
