package org.meshtastic.aether.midi

import org.meshtastic.aether.audio.FrameTime
import org.meshtastic.aether.music.ComposerOutput
import org.meshtastic.aether.music.NoteEvent
import org.meshtastic.aether.music.Voice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SR = 48_000
private const val SECOND = 1_000_000_000L

/** The sink plays 250 ms behind what the engine rendered; frame f plays at 1 s + f / rate on the system clock. */
private const val BUFFER = SR / 4
private fun playsAt(frame: Long) = SECOND + frame * SECOND / SR

private fun bell(at: Double, hz: Double, seconds: Double) =
    NoteEvent((at * SR).toLong(), Voice.Bell, hz, 0.5f, 0f, 0.3f, (seconds * SR).toLong(), 0.5f, 1L)

class MidiLiveOutputTest {
    /** Ticks every 100 ms of rendering up to [until] seconds, anchoring on the frame the sink plays. */
    private fun pumpTo(live: MidiLiveOutput, from: Double, until: Double, port: FakeMidiOutput, deliver: Boolean = true) {
        var rendered = (from * SR).toLong()
        while (rendered <= (until * SR).toLong()) {
            val played = rendered - BUFFER
            live.pump(rendered, FrameTime(played, playsAt(played)))
            if (deliver) {
                port.now = playsAt(played)
                port.deliver()
            }
            rendered += SR / 10
        }
    }

    // A message sent when it is handed over, or stamped with the engine's rendered frame, sounds a buffer early; the
    // note-on must reach the port for the time the speaker plays its frame, its bend 50 ms before.
    @Test
    fun `a note goes to the port stamped for when the speaker plays its frame`() {
        val port = FakeMidiOutput()
        val live = MidiLiveOutput(port, SR)
        live.schedule(ComposerOutput(listOf(bell(2.0, 261.6255653005986 * 5 / 4, 1.0)), emptyList()))
        pumpTo(live, 0.0, 2.2, port, deliver = false)
        val on = port.stamped.single { (it.second[0].toInt() and 0xF0) == 0x90 }
        assertContentEquals(byteArrayOf(0x91.toByte(), 64, 64), on.second)
        assertEquals(playsAt(2L * SR), on.first)
        val bend = port.stamped.single { it.second[0] == 0xE1.toByte() && it.second[2].toInt() != 0x40 }
        assertEquals(playsAt(2L * SR - SR / 20), bend.first)
    }

    // A note-on the port's thread has not yet sent when the engine stops would go out after the close's note-offs and
    // hang on the receiver.
    @Test
    fun `closing drops what the port has not sent so nothing is left sounding`() {
        val port = FakeMidiOutput()
        val live = MidiLiveOutput(port, SR)
        val third = 261.6255653005986 * 5 / 4
        live.schedule(ComposerOutput(listOf(bell(1.0, third, 5.0), bell(2.4, third * 3 / 2, 5.0)), emptyList()))
        pumpTo(live, 0.0, 2.0, port)
        pumpTo(live, 2.1, 2.6, port, deliver = false)
        port.now = playsAt((2.45 * SR).toLong())
        live.close()
        assertTrue(port.closed)
        assertEquals(1, port.cancels)
        assertTrue(port.sounding.isEmpty(), "still sounding: ${port.sounding}")
        assertTrue(port.sent.any { (it.second[0].toInt() and 0xF0) == 0x90 }, "no note was ever sent")
    }
}
