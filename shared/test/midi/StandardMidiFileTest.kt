package org.meshtastic.aether.midi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun hex(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

class StandardMidiFileTest {
    // The variable-length quantity table in the Standard MIDI Files 1.0 specification.
    @Test
    fun `variable-length quantities match the table in the specification`() {
        val table = mapOf(
            0x00L to hex(0x00), 0x40L to hex(0x40), 0x7FL to hex(0x7F), 0x80L to hex(0x81, 0x00),
            0x2000L to hex(0xC0, 0x00), 0x3FFFL to hex(0xFF, 0x7F), 0x4000L to hex(0x81, 0x80, 0x00),
            0x100000L to hex(0xC0, 0x80, 0x00), 0x1FFFFFL to hex(0xFF, 0xFF, 0x7F),
            0x200000L to hex(0x81, 0x80, 0x80, 0x00), 0x8000000L to hex(0xC0, 0x80, 0x80, 0x00),
            0xFFFFFFFL to hex(0xFF, 0xFF, 0xFF, 0x7F),
        )
        for ((v, bytes) in table) assertContentEquals(bytes, StandardMidiFile.varLength(v), "0x${v.toString(16)}")
        assertFailsWith<IllegalArgumentException> { StandardMidiFile.varLength(0x10000000L) }
    }

    // MThd, length 6, format 0, one track, 960 ticks per quarter; the track opens with a 120 bpm tempo (500,000 us per
    // quarter) and closes with the end-of-track meta event, and its length counts every byte between.
    @Test
    fun `a one-part file is format 0 with the tempo and timed events and end of track`() {
        val smf = StandardMidiFile(48_000, 1)
        smf.add(MidiEvent(-100, 0, hex(0xB0, 0x65, 0x00)))
        smf.add(MidiEvent(48_000, 0, hex(0x91, 60, 100)))
        smf.add(MidiEvent(48_000 + 25 * 200, 0, hex(0x81, 60, 64)))
        val body = hex(
            0x00, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20,
            0x00, 0xB0, 0x65, 0x00,
            0x8F, 0x00, 0x91, 60, 100, // 1 s is 1920 ticks
            0x81, 0x48, 0x81, 60, 64, // 200 ticks
            0x00, 0xFF, 0x2F, 0x00,
        )
        val expected = hex(0x4D, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 0, 0, 1, 0x03, 0xC0, 0x4D, 0x54, 0x72, 0x6B, 0, 0, 0, body.size) + body
        assertContentEquals(expected, smf.bytes())
    }

    @Test
    fun `a two-part file is format 1 with a track per part and the tempo only in the first`() {
        val smf = StandardMidiFile(48_000, 2)
        smf.add(MidiEvent(0, 1, hex(0x92, 70, 90)))
        smf.add(MidiEvent(48_000, 0, hex(0x91, 60, 100)))
        val bytes = smf.bytes()
        assertContentEquals(hex(0, 1, 0, 2), bytes.copyOfRange(8, 12))
        val first = 14 + 8 + (7 + 5 + 4)
        assertContentEquals("MTrk".encodeToByteArray(), bytes.copyOfRange(first, first + 4))
        assertContentEquals(hex(0, 0, 0, 8, 0x00, 0x92, 70, 90, 0x00, 0xFF, 0x2F, 0x00), bytes.copyOfRange(first + 4, bytes.size))
        assertEquals(first + 16, bytes.size)
    }
}
