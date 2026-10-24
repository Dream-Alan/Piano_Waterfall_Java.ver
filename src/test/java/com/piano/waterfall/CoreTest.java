package com.piano.waterfall;

import com.piano.waterfall.midi.MidiParser;
import com.piano.waterfall.midi.PianoVoice;
import com.piano.waterfall.midi.SampledPianoEngine;
import com.piano.waterfall.midi.ScoreCompiler;
import com.piano.waterfall.model.KeyGeometry;
import com.piano.waterfall.model.NoteBlock;
import com.piano.waterfall.render.WaterfallRenderer;
import org.junit.jupiter.api.Test;

import javax.sound.midi.MetaMessage;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreTest {

    @Test
    void builds52WhiteAnd36BlackKeysCentered() {
        KeyGeometry[] keys = KeyGeometry.build88Keys(1000, 0.94);

        int white = 0;
        int black = 0;
        for (int pitch = 21; pitch <= 108; pitch++) {
            assertNotNull(keys[pitch]);
            if (keys[pitch].isBlack()) {
                black++;
            } else {
                white++;
            }
        }
        assertEquals(52, white);
        assertEquals(36, black);
        assertEquals(1000 * 0.03, keys[21].getX(), 0.001);
        assertEquals(1000 * 0.97,
                keys[108].getX() + keys[108].getWidth(), 0.001);
    }

    @Test
    void blackKeyIsCenteredOnWhiteBoundary() {
        KeyGeometry[] keys = KeyGeometry.build88Keys(1000, 0.94);
        KeyGeometry c = keys[60];
        KeyGeometry cSharp = keys[61];
        assertEquals(c.getX() + c.getWidth() - cSharp.getWidth() / 2.0,
                cSharp.getX(), 0.001);
    }

    @Test
    void parsesTempoAndNoteTimes() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 480);
        Track track = sequence.createTrack();
        track.add(new MidiEvent(tempoMessage(500_000), 0));
        track.add(noteOn(60, 100, 0));
        track.add(noteOff(60, 480));
        track.add(new MidiEvent(tempoMessage(1_000_000), 480));
        track.add(noteOn(64, 90, 480));
        track.add(noteOff(64, 960));

        List<NoteBlock> notes = MidiParser.parseSequence(sequence);

        assertEquals(2, notes.size());
        assertEquals(60, notes.get(0).pitch());
        assertEquals(0.0, notes.get(0).startTime(), 0.0001);
        assertEquals(0.5, notes.get(0).endTime(), 0.0001);
        assertEquals(64, notes.get(1).pitch());
        assertEquals(0.5, notes.get(1).startTime(), 0.0001);
        assertEquals(1.5, notes.get(1).endTime(), 0.0001);
    }

    @Test
    void noteOffWinsOverNoteOnAtSameTick() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 480);
        Track track = sequence.createTrack();
        track.add(noteOn(60, 100, 0));
        track.add(noteOn(60, 90, 480));
        track.add(noteOff(60, 480));
        track.add(noteOff(60, 960));

        List<NoteBlock> notes = MidiParser.parseSequence(sequence);

        assertEquals(2, notes.size());
        assertEquals(0.0, notes.get(0).startTime(), 0.0001);
        assertEquals(0.5, notes.get(0).endTime(), 0.0001);
        assertEquals(0.5, notes.get(1).startTime(), 0.0001);
        assertEquals(1.0, notes.get(1).endTime(), 0.0001);
    }

    @Test
    void compilesJianpuToSequence() throws Exception {
        Sequence sequence = ScoreCompiler.compileJianpu(120, 60, "1 2 3", "");
        List<NoteBlock> notes = MidiParser.parseSequence(sequence);

        assertEquals(3, notes.size());
        assertEquals(60, notes.get(0).pitch());
        assertEquals(62, notes.get(1).pitch());
        assertEquals(64, notes.get(2).pitch());
        assertEquals(0.0, notes.get(0).startTime(), 0.0001);
        assertEquals(0.5, notes.get(1).startTime(), 0.0001);
        assertEquals(1.0, notes.get(2).startTime(), 0.0001);
        assertTrue(notes.get(0).endTime() > notes.get(0).startTime());
    }

    @Test
    void readsJianpuFileAndWritesMidi() throws Exception {
        Path source = Files.createTempFile("piano", ".jianpu");
        Files.writeString(source,
                "title=Test Song\nbpm=90\nkey=G\n"
                        + "right: 1 2 3\nleft: 1/ 5/\n");
        ScoreCompiler.JianpuDocument document = ScoreCompiler.readJianpuFile(source);
        assertEquals("Test Song", document.title());
        assertEquals(90, document.bpm());
        assertEquals(67, document.basePitch());
        assertTrue(document.right().contains("1 2 3"));

        Sequence sequence = ScoreCompiler.compileJianpu(document.bpm(),
                document.basePitch(), document.right(), document.left());
        File midi = Files.createTempFile("piano", ".mid").toFile();
        ScoreCompiler.saveMidi(sequence, midi);
        assertTrue(midi.isFile() && midi.length() > 0);

        List<NoteBlock> notes = MidiParser.parseSequence(MidiSystem.getSequence(midi));
        assertTrue(notes.size() >= 5);
        assertTrue(notes.stream().anyMatch(note -> note.pitch() == 67));
    }

    @Test
    void cyclesFourThemes() {
        WaterfallRenderer renderer = new WaterfallRenderer();
        assertEquals(WaterfallRenderer.Theme.CYBERPUNK, renderer.getTheme());
        assertEquals(WaterfallRenderer.Theme.AURORA, renderer.nextTheme());
        assertEquals(WaterfallRenderer.Theme.GOLDEN, renderer.nextTheme());
        assertEquals(WaterfallRenderer.Theme.SAKURA, renderer.nextTheme());
        assertEquals(WaterfallRenderer.Theme.CYBERPUNK, renderer.nextTheme());
    }

    @Test
    void rendersSampledPianoWav() throws Exception {
        List<NoteBlock> notes = List.of(
                new NoteBlock(60, 0.0, 0.5, 90),
                new NoteBlock(64, 0.25, 0.5, 80));
        File wav = SampledPianoEngine.render("unit-voice", notes, PianoVoice.GRAND, percent -> { });
        assertTrue(wav.isFile() && wav.length() > 10_000);
        AudioFileFormat format = AudioSystem.getAudioFileFormat(wav);
        assertEquals(44_100, (int) format.getFormat().getSampleRate());
        assertEquals(2, format.getFormat().getChannels());
    }

    @Test
    void rejectsInvalidNoteBlock() {
        assertThrows(IllegalArgumentException.class,
                () -> new NoteBlock(60, 1.0, 0.5, 90));
    }

    private static MetaMessage tempoMessage(int mpqn) throws Exception {
        MetaMessage message = new MetaMessage();
        byte[] data = {(byte) (mpqn >> 16), (byte) (mpqn >> 8), (byte) mpqn};
        message.setMessage(0x51, data, data.length);
        return message;
    }

    private static MidiEvent noteOn(int pitch, int velocity, long tick) throws Exception {
        return new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, pitch, velocity), tick);
    }

    private static MidiEvent noteOff(int pitch, long tick) throws Exception {
        return new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, pitch, 0), tick);
    }
}
