package org.meshtastic.aether.tools

import org.meshtastic.aether.midi.MidiConfig
import org.meshtastic.aether.midi.MidiLayout
import org.meshtastic.aether.music.NoteEvent
import org.meshtastic.aether.music.Voice
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A note-on read back from the file: its time in ticks, its track and channel, and the pitch the bend gives it. */
private class Onset(val tick: Long, val track: Int, val channel: Int, val hz: Double)

/** Reads a Standard MIDI File the way the specification describes it, independent of the writer. */
private class SmfReader(private val b: ByteArray, private val range: Int) {
    val onsets = ArrayList<Onset>()
    val problems = ArrayList<String>()
    var format = -1
    var division = -1
    var unclosed = 0
    var lastTick = 0L
    val firstMessages = ArrayList<List<Int>>()
    private var p = 0

    private fun u8() = b[p++].toInt() and 0xFF
    private fun u16() = (u8() shl 8) or u8()
    private fun u32() = (u16() shl 16) or u16()
    private fun tag() = String(b, p, 4, Charsets.US_ASCII).also { p += 4 }
    private fun varLength(): Long {
        var v = 0L
        do {
            val c = u8()
            v = (v shl 7) or (c and 0x7F).toLong()
        } while (c and 0x80 != 0)
        return v
    }

    fun read(): SmfReader {
        check(tag() == "MThd" && u32() == 6)
        format = u16()
        val tracks = u16()
        division = u16()
        repeat(tracks) { track(it) }
        check(p == b.size) { "${b.size - p} bytes after the last track" }
        return this
    }

    private fun track(index: Int) {
        check(tag() == "MTrk")
        val length = u32()
        val end = p + length
        val bend = IntArray(16) { 8192 }
        val sounding = HashSet<Pair<Int, Int>>()
        var tick = 0L
        var ended = false
        while (p < end) {
            tick += varLength()
            val status = u8()
            if (status == 0xFF) {
                val type = u8()
                val skip = varLength().toInt()
                p += skip
                if (type == 0x2F) ended = true
                continue
            }
            val ch = status and 0x0F
            val d1 = u8()
            val d2 = if (status and 0xF0 == 0xC0 || status and 0xF0 == 0xD0) 0 else u8()
            if (index == 0 && firstMessages.size < 8) firstMessages += listOf(status, d1, d2)
            when (status and 0xF0) {
                0xE0 -> {
                    if (sounding.any { it.first == ch }) problems += "channel $ch re-bent while sounding at tick $tick"
                    bend[ch] = d1 or (d2 shl 7)
                }
                0x90 -> {
                    if (!sounding.add(ch to d1)) problems += "key $d1 on twice on channel $ch at tick $tick"
                    val semis = d1 - 69 + (bend[ch] - 8192).toDouble() / 8192 * range
                    onsets += Onset(tick, index, ch, 440.0 * 2.0.pow(semis / 12))
                }
                0x80 -> if (!sounding.remove(ch to d1)) problems += "note-off for silent key $d1 at tick $tick"
            }
        }
        check(p == end && ended) { "track $index is not closed by its end-of-track event" }
        lastTick = maxOf(lastTick, tick)
        unclosed += sounding.size
    }
}

private fun cents(a: Double, b: Double) = abs(1200 * ln(a / b) / ln(2.0))

class RenderMidiTest {
    @Test
    fun `the midi command reads its flags`() {
        val c = parseArgs(listOf("midi", "3600", "out.mid", "--nodes", "150", "--speed", "30", "--range", "2", "--layout", "two"))
        c as Command.Midi
        assertEquals(3600.0, c.spec.seconds)
        assertEquals(150, c.spec.nodes)
        assertEquals(2, c.spec.config.bendRange)
        assertEquals(MidiLayout.BedAndOrnaments, c.spec.config.layout)
        assertEquals("out.mid", c.out)
        assertFailsWith<IllegalArgumentException> { parseArgs(listOf("midi", "60", "x.mid", "--range", "2.0")) }
    }

    // The close is the file's last moment: closed a dispatch horizon early, a render drops its final 0.3 s of notes.
    @Test
    fun `a render ends at the requested length`() {
        val r = renderMidi(MidiSpec(seconds = 60.0, nodes = 20, speed = 1.0))
        val rx = SmfReader(r.bytes, 48).read()
        assertEquals(60L * 1920, rx.lastTick)
    }

    // The probe's 12 h day on 150 nodes: every note the composer made sounds within half a bend step (0.29 cents at
    // range 48) of its pitch unless the mapper counted it as a fallback, the file is well formed, and nothing is left
    // sounding. A fallback one step away can land within 0.3 cents, so the count bounds the mistuned notes.
    @Test
    fun `a day on a 150 node mesh decodes to the composer's pitches except the counted fallbacks`() {
        val notes = ArrayList<NoteEvent>()
        val spec = MidiSpec(seconds = 12 * 3600.0, nodes = 150, speed = 1.0, startEpochMs = START_EPOCH_MS - 12 * 3_600_000L)
        val r = renderMidi(spec) { notes += it.notes }
        val rx = SmfReader(r.bytes, 48).read()
        assertEquals(0, rx.format)
        assertEquals(960, rx.division)
        assertTrue(rx.problems.isEmpty(), rx.problems.take(5).joinToString("; "))
        assertEquals(0, rx.unclosed)
        assertEquals(0, r.stats.late)
        assertEquals(listOf(listOf(0xB0, 0x65, 0), listOf(0xB0, 0x64, 6), listOf(0xB0, 0x06, 15)), rx.firstMessages.take(3))
        // A tick is 25 frames at 48 kHz, so a note-on is within half a tick of its note.
        val byTick = notes.filter { it.voice != Voice.Breath }.groupBy { (it.atFrame + 12) / 25 }
        val off = rx.onsets.count { o ->
            val near = (o.tick - 1..o.tick + 1).flatMap { byTick[it].orEmpty() }
            near.none { n -> cents(if (n.voice == Voice.Shimmer) n.pitchHz * 2 else n.pitchHz, o.hz) <= 0.3 }
        }
        assertTrue(rx.onsets.size > 10_000, "only ${rx.onsets.size} notes in 12 h")
        assertTrue(off <= r.stats.fallbacks, "$off notes mistuned, ${r.stats.fallbacks} counted")
    }

    @Test
    fun `two parts write a format 1 file whose second track holds the ornaments`() {
        val r = renderMidi(MidiSpec(seconds = 300.0, nodes = 40, speed = 10.0, config = MidiConfig(layout = MidiLayout.BedAndOrnaments)))
        val rx = SmfReader(r.bytes, 48).read()
        assertEquals(1, rx.format)
        assertTrue(rx.problems.isEmpty(), rx.problems.take(5).joinToString("; "))
        assertTrue(rx.onsets.any { it.track == 1 } && rx.onsets.any { it.track == 0 })
    }
}
