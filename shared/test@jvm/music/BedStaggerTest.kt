package org.meshtastic.aether.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An hour of composing is too long for the Wasm runner's 30 s limit, so this lives in the JVM tests. */
class BedStaggerTest {
    private val sr = 48_000
    private val t0 = 1_800_000_000_000L
    private val lookahead = sr / 2L
    private val step = sr / 4L

    // Regression: a new chord staggered every pad voice, so a held tone ended at the tick and came back up to 0.9 s
    // later, a dip of up to about 7 dB on the very tones voice leading holds.
    @Test
    fun `at a chord change a held pad tone strikes on the tick and only moving voices stagger`() {
        val c = malletComposer(41L, sr, pinFormFull = true)
        val pads = ArrayList<NoteEvent>()
        var f = 0L
        while (f < 60L * 60 * sr) {
            pads += c.advance(f, lookahead, LocalMoment(3, 600, t0 + f * 1000 / sr)).notes.filter { it.voice == Voice.Pad && it.node == null && it.velocity == 0.5f }
            f += step
        }
        val pans = listOf(-0.5f, -0.17f, 0.17f, 0.5f)
        val bed = pads.filter { it.pan in pans }
        val ticks = bed.groupBy { it.atFrame / (20L * sr) }.entries.sortedBy { it.key }.map { it.value }
        var held = 0
        ticks.zipWithNext().forEach { (prev, cur) ->
            val tick = cur.minOf { it.atFrame }
            for (n in cur) {
                val before = prev.firstOrNull { it.pan == n.pan } ?: continue
                if (before.pitchHz == n.pitchHz) {
                    held++
                    assertEquals(tick, n.atFrame, "a held tone at pan ${n.pan} came in late")
                }
            }
        }
        assertTrue(held > 50, "only $held held tones")
        assertTrue(bed.any { (it.atFrame % (20L * sr)) != 0L }, "no voice ever staggered")
    }
}
