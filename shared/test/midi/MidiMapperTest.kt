package org.meshtastic.aether.midi

import org.meshtastic.aether.music.ComposerOutput
import org.meshtastic.aether.music.ControlEvent
import org.meshtastic.aether.music.NoteEvent
import org.meshtastic.aether.music.Texture
import org.meshtastic.aether.music.Voice
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SR = 48_000
private const val C4_HZ = 261.6255653005986

private fun sec(s: Double): Long = (s * SR).toLong()

private fun note(at: Double, hz: Double, seconds: Double, voice: Voice = Voice.Bell, velocity: Float = 0.5f, pan: Float = 0f, node: Long? = 1L) =
    NoteEvent(sec(at), voice, hz, velocity, pan, 0.3f, sec(seconds), 0.5f, node)

private fun texture(at: Double, pad: Float) = ControlEvent.SetTexture(
    sec(at), Texture(130.8, 0.5f, 0.5f, 0.3f, 0.2f, 0.2f, 0.1f, mapOf(Voice.Pad to pad, Voice.Drone to pad, Voice.Shimmer to 0.5f)),
)

/** One note as a receiver hears it: decoded from the stream with the MIDI 1.0 pitch formula, not the mapper's code. */
private class Heard(val channel: Int, val key: Int, val velocity: Int, val on: Long, var off: Long, val hz: Double)

/**
 * A minimal receiver: tracks each channel's bend and sounding keys and flags what the spec forbids, a message that
 * arrives after the drain it was due in ([due] is the previous drain's frame), a channel re-bent within [allowance]
 * of its last note-off, and a note-on on a channel whose bend was never set.
 */
private class Receiver(private val range: Int, private val allowance: Long = sec(2.0)) {
    val heard = ArrayList<Heard>()
    val problems = ArrayList<String>()
    val events = ArrayList<MidiEvent>()
    private val bend = IntArray(16) { 8192 }
    private val bent = BooleanArray(16)
    private val lastBendAt = LongArray(16) { Long.MIN_VALUE / 2 }
    private val lastOffAt = LongArray(16) { Long.MIN_VALUE / 2 }
    private val sounding = HashMap<Pair<Int, Int>, Heard>()
    var lastFrame = Long.MIN_VALUE
    var due = Long.MIN_VALUE

    fun take(e: MidiEvent) {
        events += e
        if (e.frame < lastFrame) problems += "out of order at ${e.frame}"
        if (e.frame <= due) problems += "sent after its drain: frame ${e.frame}, drained to $due"
        lastFrame = e.frame
        val status = e.data[0].toInt() and 0xF0
        val ch = e.data[0].toInt() and 0x0F
        val d1 = e.data.getOrNull(1)?.toInt() ?: 0
        val d2 = e.data.getOrNull(2)?.toInt() ?: 0
        when (status) {
            0xE0 -> {
                if (sounding.keys.any { it.first == ch }) problems += "channel $ch re-bent while sounding at ${e.frame}"
                val value = d1 or (d2 shl 7)
                if (value != 8192 && e.frame < lastOffAt[ch] + allowance) problems += "channel $ch re-bent in its release at ${e.frame}"
                bend[ch] = value
                bent[ch] = true
                lastBendAt[ch] = e.frame
            }
            0x90 -> {
                if (sounding.containsKey(ch to d1)) problems += "second note-on for key $d1 on channel $ch at ${e.frame}"
                if (!bent[ch]) problems += "note-on on channel $ch whose bend was never set"
                val semis = d1 - 69 + (bend[ch] - 8192).toDouble() / 8192 * range
                val h = Heard(ch, d1, d2, e.frame, -1, 440.0 * 2.0.pow(semis / 12))
                if (e.frame - lastBendAt[ch] in 0 until sec(0.05) && lastBendAt[ch] > 0) problems += "bend only ${e.frame - lastBendAt[ch]} frames before note-on"
                heard += h
                sounding[ch to d1] = h
            }
            0x80 -> {
                val h = sounding.remove(ch to d1)
                if (h == null) problems += "note-off for silent key $d1 on channel $ch" else h.off = e.frame
                lastOffAt[ch] = e.frame
            }
        }
    }

    val stillSounding: Int get() = sounding.size
}

private fun cents(a: Double, b: Double) = abs(1200 * ln(a / b) / ln(2.0))

class MidiMapperTest {
    private fun run(
        notes: List<NoteEvent>,
        controls: List<ControlEvent> = emptyList(),
        config: MidiConfig = MidiConfig(),
        until: Double = 120.0,
    ): Pair<MidiMapper, Receiver> {
        val mapper = MidiMapper(SR, config)
        val rx = Receiver(config.bendRange)
        mapper.schedule(ComposerOutput(notes, controls))
        drainTo(mapper, rx, 0L, sec(until))
        return mapper to rx
    }

    /** Drains every 0.1 s from [from] up to [until], then closes there; the receiver checks each drain's timing. */
    private fun drainTo(mapper: MidiMapper, rx: Receiver, from: Long, until: Long, close: Boolean = true) {
        var f = from
        while (f < until) {
            mapper.drain(f, rx::take)
            rx.due = f
            f += sec(0.1)
        }
        if (close) mapper.close(f, rx::take)
    }

    // The MIDI 1.0 pitch formula: 5/4 over C4 is 327.03 Hz, note 63.863, so key 64 lowered 13.69 cents, which is
    // 8192 - round(0.13686 / 48 * 8192) = 8169 at an MPE bend range of 48.
    @Test
    fun `a just major third over middle C is key 64 bent to 8169 at range 48`() {
        val t = MidiPitch.tune(C4_HZ * 5 / 4, 48)!!
        assertEquals(64, t.key)
        assertEquals(8169, t.bend)
        val plain = MidiPitch.tune(C4_HZ * 5 / 4, 2)!!
        assertEquals(64, plain.key)
        assertEquals(8192 - 561, plain.bend)
    }

    // A channel per note runs out of MPE's 15 channels on a busy mesh; a channel per pitch offset does not.
    @Test
    fun `notes with one offset share a channel and another offset takes another`() {
        val third = C4_HZ * 5 / 4
        val (_, rx) = run(listOf(note(1.0, third, 4.0), note(1.0, third * 2, 4.0), note(1.0, C4_HZ * 6 / 5, 4.0)))
        val byHz = rx.heard.associateBy { it.hz.toInt() }
        assertEquals(3, rx.heard.size)
        assertEquals(byHz.getValue(third.toInt()).channel, byHz.getValue((third * 2).toInt()).channel)
        assertTrue(byHz.getValue((C4_HZ * 6 / 5).toInt()).channel != byHz.getValue(third.toInt()).channel)
        rx.heard.forEach { h -> assertTrue(listOf(third, third * 2, C4_HZ * 6 / 5).any { cents(it, h.hz) < 0.3 }) }
    }

    /** Overlapping struck notes over a minute on 20 offsets, unisons included, from a fixed generator. */
    private fun crowd(): List<NoteEvent> {
        var x = 12345L
        fun next(n: Int): Int {
            x = (x * 6364136223846793005L + 1442695040888963407L)
            return ((x ushr 33) % n).toInt()
        }
        val offsets = List(20) { 2.0.pow((it * 3.3 - 30) / 1200) }
        return List(400) { i ->
            val hz = C4_HZ * 2.0.pow(next(24) / 12.0) * offsets[next(offsets.size)]
            note(i * 0.15, hz, 0.3 + next(30) / 10.0, voice = if (i % 3 == 0) Voice.Marimba else Voice.Bell, node = i.toLong())
        }
    }

    // A sounding channel re-bent detunes every note on it; a bend sent at the note-on is ignored by some hardware;
    // a second note-on for a sounding key hangs a voice on stacking receivers.
    @Test
    fun `a crowd of offsets never re-bends a sounding channel nor sends a key on twice and every note ends`() {
        val (mapper, rx) = run(crowd(), until = 80.0)
        assertTrue(rx.problems.isEmpty(), rx.problems.take(5).joinToString("; "))
        assertEquals(0, rx.stillSounding)
        // The crowd ends by 63.3 s; a note still sounding at 80 s was ended by the close, not by its own end.
        assertTrue(rx.heard.all { it.off in it.on until sec(70.0) }, "a note-off before its note-on or left to the close")
        assertEquals(mapper.stats.notes, rx.heard.size + mapper.stats.carried + mapper.stats.held)
    }

    // Every note sounds at the composer's pitch within one bend step (0.586 cents at range 48), except notes the
    // mapper counted as fallbacks.
    @Test
    fun `every note sounds at its pitch within one bend step unless counted as a fallback`() {
        val notes = crowd()
        val (mapper, rx) = run(notes, until = 80.0)
        val wanted = notes.groupBy { it.atFrame }
        val off = rx.heard.count { h -> wanted.getValue(h.on).none { cents(it.pitchHz, h.hz) <= 0.3 } }
        assertEquals(mapper.stats.fallbacks, off)
    }

    // A later composer window can bring a note that sounds before notes already queued; held behind them in arrival
    // order it would go out late, after its drain.
    @Test
    fun `a note that arrives after a later one is placed in time and in tune`() {
        val mapper = MidiMapper(SR)
        val rx = Receiver(48)
        val late = C4_HZ * 3 / 2
        val early = C4_HZ * 16 / 15
        mapper.schedule(ComposerOutput(listOf(note(10.0, late, 2.0)), emptyList()))
        drainTo(mapper, rx, 0L, sec(1.0), close = false)
        mapper.schedule(ComposerOutput(listOf(note(5.0, early, 2.0)), emptyList()))
        drainTo(mapper, rx, sec(1.0), sec(20.0))
        assertTrue(rx.problems.isEmpty(), rx.problems.joinToString())
        assertEquals(listOf(sec(5.0), sec(10.0)), rx.heard.map { it.on })
        assertTrue(cents(rx.heard[0].hz, early) < 0.3 && cents(rx.heard[1].hz, late) < 0.3)
        assertEquals(0, mapper.stats.shifted)
    }

    // A note that sounds before one already placed, on every channel busy, would land on the later note's channel as a
    // note-off for a key not yet on and a second note-on; it starts at the placed note's frame instead.
    @Test
    fun `a note arriving after a later one was placed starts at that frame and strikes nothing twice`() {
        val held = List(14) { note(0.0, C4_HZ * 1.5 * 2.0.pow((it + 1) * 5.0 / 1200), 30.0, node = 100L + it) }
        val placed = note(10.0, C4_HZ * 5 / 4, 2.0)
        val mapper = MidiMapper(SR)
        val rx = Receiver(48)
        mapper.schedule(ComposerOutput(held + placed, emptyList()))
        drainTo(mapper, rx, 0L, sec(9.8), close = false)
        mapper.schedule(ComposerOutput(listOf(note(9.9, C4_HZ * 5 / 4, 1.0)), emptyList()))
        drainTo(mapper, rx, sec(9.8), sec(40.0))
        assertTrue(rx.problems.isEmpty(), rx.problems.joinToString())
        assertEquals(1, mapper.stats.shifted)
        assertEquals(0, mapper.stats.late)
        assertEquals(listOf(sec(10.0), sec(10.0)), rx.heard.filter { it.on > 0 }.map { it.on })
    }

    // The bed is struck again at every 20 s tick; an external synth would re-attack it each time. The synth carries a
    // re-strike on whether it abuts, overlaps or follows within its release.
    @Test
    fun `a bed re-strike carries on whether it abuts overlaps or follows a short gap`() {
        val pad = C4_HZ
        val notes = listOf(
            note(0.0, pad, 20.0, Voice.Pad, node = null),
            note(20.0, pad, 20.0, Voice.Pad, node = null),
            note(39.0, pad, 21.0, Voice.Pad, node = null),
            note(60.05, pad, 10.0, Voice.Pad, node = null),
        )
        val (mapper, rx) = run(notes, until = 80.0)
        assertTrue(rx.problems.isEmpty(), rx.problems.joinToString())
        assertEquals(1, rx.heard.size, "the bed was struck ${rx.heard.size} times")
        assertEquals(sec(70.05), rx.heard.single().off)
        assertEquals(3, mapper.stats.carried)
    }

    // A loop note at a held pad's pitch would be a second note-on for a sounding key; held, it keeps the pad sounding
    // to its own end.
    @Test
    fun `a bed note at a key the bed already holds extends it to its own end`() {
        val notes = listOf(
            note(0.0, C4_HZ, 20.0, Voice.Pad, velocity = 0.5f, pan = -0.5f, node = null),
            note(5.0, C4_HZ, 20.0, Voice.Pad, velocity = 0.4f, pan = 0.2f, node = null),
        )
        val (mapper, rx) = run(notes, until = 40.0)
        assertTrue(rx.problems.isEmpty(), rx.problems.joinToString())
        assertEquals(1, rx.heard.size)
        assertEquals(sec(25.0), rx.heard.single().off)
        assertEquals(1, mapper.stats.held)
    }

    // A bed drone at the pitch of a node's drone is a note of its own: held into the node's, it never sounds.
    @Test
    fun `a bed note at the key of a held node drone is struck on its own`() {
        val notes = listOf(note(0.0, C4_HZ, 20.0, Voice.Drone, node = 7L), note(5.0, C4_HZ, 20.0, Voice.Drone, node = null))
        val (mapper, rx) = run(notes, until = 40.0)
        assertTrue(rx.problems.isEmpty(), rx.problems.joinToString())
        assertEquals(listOf(sec(0.0), sec(5.0)), rx.heard.map { it.on })
        assertEquals(0, mapper.stats.held)
    }

    // A retrigger cuts a long note short; a channel still marked busy to the cut note's old end is never freed for
    // another offset, and a note that could have had it falls back to a wrong bend.
    @Test
    fun `a channel whose long note a retrigger cut is free again after its release`() {
        val long = List(15) { note(0.0, C4_HZ, 100.0, node = it.toLong()) }
        val cut = note(1.0, C4_HZ, 1.0, node = 99L)
        val other = note(5.0, C4_HZ * 2.0.pow(25.0 / 1200), 1.0, node = 98L)
        val (mapper, rx) = run(long + cut + other, until = 10.0)
        assertTrue(rx.problems.isEmpty(), rx.problems.joinToString())
        assertEquals(1, mapper.stats.retriggered)
        assertEquals(0, mapper.stats.fallbacks)
        assertTrue(rx.heard.any { it.on == sec(5.0) && cents(it.hz, C4_HZ * 2.0.pow(25.0 / 1200)) < 0.3 })
    }

    // Closing a mapper that never drained must still drop what comes after the close.
    @Test
    fun `closing before any drain drops the notes after the close`() {
        val mapper = MidiMapper(SR)
        val rx = Receiver(48)
        mapper.schedule(ComposerOutput(listOf(note(1.0, C4_HZ, 1.0), note(10.0, C4_HZ * 3 / 2, 1.0)), emptyList()))
        mapper.close(sec(5.0), rx::take)
        drainTo(mapper, rx, sec(5.1), sec(20.0))
        assertTrue(rx.heard.none { it.on == sec(10.0) }, "the note after the close sounded")
        assertEquals(0, rx.stillSounding)
    }

    // The synth never carries a voice a node started; a bed note at a node drone's pitch is struck on its own.
    @Test
    fun `a bed note does not carry on the drone of a node`() {
        val notes = listOf(note(0.0, C4_HZ, 20.0, Voice.Drone, node = 7L), note(20.0, C4_HZ, 20.0, Voice.Drone, node = null))
        val (mapper, rx) = run(notes, until = 50.0)
        assertTrue(rx.problems.isEmpty(), rx.problems.joinToString())
        assertEquals(2, rx.heard.size)
        assertEquals(0, mapper.stats.carried)
    }

    // A struck unison (an echo over its note) goes to another channel at the same bend, so neither cuts the other.
    @Test
    fun `a struck note at a sounding key takes another channel at the same bend`() {
        val (_, rx) = run(listOf(note(1.0, C4_HZ, 2.0), note(1.375, C4_HZ, 2.0)), until = 10.0)
        assertTrue(rx.problems.isEmpty(), rx.problems.joinToString())
        assertEquals(2, rx.heard.size)
        assertTrue(rx.heard[0].channel != rx.heard[1].channel)
        assertEquals(listOf(sec(3.0), sec(3.375)), rx.heard.map { it.off })
    }

    // The bed's dynamics travel in the texture's layer level, not in note velocities; carried on without a re-strike
    // a fading pad would hold its first loudness for minutes.
    @Test
    fun `a carried bed note is struck again lower when its layer falls more than 3 dB`() {
        val notes = listOf(note(0.0, C4_HZ, 20.0, Voice.Pad, node = null), note(20.0, C4_HZ, 20.0, Voice.Pad, node = null))
        val (mapper, rx) = run(notes, listOf(texture(0.0, 1f), texture(15.0, 0.5f)), until = 50.0)
        assertTrue(rx.problems.isEmpty(), rx.problems.joinToString())
        assertEquals(listOf(64, 32), rx.heard.map { it.velocity })
        assertEquals(1, mapper.stats.restruck)
    }

    // RP-053: the MPE Configuration Message is RPN 6 on the master channel, here 15 members in the lower zone; each
    // member's bend range is RPN 0, then the null RPN.
    @Test
    fun `an MPE output opens with the configuration and the bend range of each member`() {
        val (_, rx) = run(listOf(note(1.0, C4_HZ, 1.0)), until = 3.0)
        val bytes = rx.events.map { e -> e.data.map { it.toInt() and 0xFF } }
        assertEquals(listOf(0xB0, 0x65, 0), bytes[0])
        assertEquals(listOf(0xB0, 0x64, 6), bytes[1])
        assertEquals(listOf(0xB0, 0x06, 15), bytes[2])
        assertEquals(listOf(0xB0, 0x65, 0x7F), bytes[3])
        assertEquals(listOf(0xB0, 0x64, 0x7F), bytes[4])
        for (ch in 1..15) {
            val i = 5 + (ch - 1) * 6
            assertEquals(listOf(0xB0 or ch, 0x64, 0), bytes[i + 1])
            assertEquals(listOf(0xB0 or ch, 0x06, 48), bytes[i + 2])
        }
    }

    // General MIDI plays channel 10 as drums; a plain multi-channel output must never use it.
    @Test
    fun `a plain output at bend range 2 never uses channel 10 and opens each channel at range 2`() {
        val (_, rx) = run(crowd(), config = MidiConfig(bendRange = 2), until = 80.0)
        assertTrue(rx.problems.isEmpty(), rx.problems.take(3).joinToString())
        assertTrue(rx.events.none { (it.data[0].toInt() and 0x0F) == 9 })
        assertTrue(rx.events.none { (it.data[0].toInt() and 0xF0) == 0xB0 && it.data[1].toInt() == 0x64 && it.data[2].toInt() == 6 })
        val ranges = rx.events.filter { (it.data[0].toInt() and 0xF0) == 0xB0 && it.data[1].toInt() == 0x06 }.map { it.data[2].toInt() }
        assertEquals(List(15) { 2 }, ranges)
    }

    // A restrike is placed ahead of its frame; stopping between the placing and the frame drops its off and on, and
    // the note first struck is still sounding on the receiver.
    @Test
    fun `closing before a placed restrike still ends the note that is sounding`() {
        val mapper = MidiMapper(SR)
        val rx = Receiver(48)
        val notes = listOf(note(0.0, C4_HZ, 20.0, Voice.Pad, node = null), note(20.0, C4_HZ, 20.0, Voice.Pad, node = null))
        mapper.schedule(ComposerOutput(notes, listOf(texture(0.0, 1f), texture(15.0, 0.5f))))
        var f = 0L
        while (f < sec(19.9)) {
            mapper.drain(f, rx::take)
            f += sec(0.1)
        }
        mapper.close(sec(19.9), rx::take)
        assertTrue(rx.problems.isEmpty(), rx.problems.joinToString())
        assertEquals(1, mapper.stats.restruck, "the restrike was not placed before the close")
        assertEquals(1, rx.heard.size)
        assertEquals(0, rx.stillSounding)
    }

    // Stopping must leave nothing sounding on the receiver, and nothing it never started.
    @Test
    fun `closing mid note ends every started note and sends sustain off and centred bends`() {
        val mapper = MidiMapper(SR)
        val rx = Receiver(48)
        mapper.schedule(ComposerOutput(listOf(note(1.0, C4_HZ, 30.0), note(1.0, C4_HZ * 3 / 2, 30.0), note(9.0, C4_HZ, 5.0)), emptyList()))
        var f = 0L
        while (f < sec(5.0)) {
            mapper.drain(f, rx::take)
            f += sec(0.1)
        }
        mapper.close(f, rx::take)
        assertTrue(rx.problems.isEmpty(), rx.problems.joinToString())
        assertEquals(0, rx.stillSounding)
        assertEquals(2, rx.heard.size)
        val tail = rx.events.takeLast(16).map { e -> e.data.map { it.toInt() and 0xFF } }
        assertTrue(tail.contains(listOf(0xB0, 0x40, 0)), "no sustain off on the master channel")
        assertEquals(15, rx.events.count { (it.data[0].toInt() and 0xF0) == 0xE0 && it.data[1].toInt() == 0 && it.data[2].toInt() == 0x40 && it.frame == f })
    }
}
