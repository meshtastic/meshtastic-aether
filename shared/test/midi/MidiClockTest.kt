package org.meshtastic.aether.midi

import org.meshtastic.aether.audio.FrameTime
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

private const val RATE = 48_000
private const val SECOND = 1_000_000_000L

/**
 * A sound card whose clock runs [ppm] fast or slow against the system clock and whose position moves in [chunk]-frame
 * steps, read up to [readDelay] late: what `SourceDataLine` reports.
 */
private class Card(val ppm: Double, val chunk: Long = 480, val readDelay: Long = 2_000_000, seed: Int = 3) {
    private val rng = Random(seed)
    var stall = 0L

    /** When the speaker plays [frame], in true system time. */
    fun truth(frame: Long): Long = stall + (frame * SECOND / RATE * (1 + ppm / 1e6)).toLong()

    /** The pair the sink reports when read at system time [t]. */
    fun read(t: Long): FrameTime {
        val playing = ((t - stall) / (1 + ppm / 1e6) * RATE / SECOND).toLong()
        return FrameTime(playing / chunk * chunk, t + rng.nextLong(readDelay))
    }
}

class MidiClockTest {
    /** The worst error of the clock over the last [checkSeconds] of [seconds] of pairs read every 100 ms. */
    private fun worstError(card: Card, clock: MidiClock, seconds: Int, checkSeconds: Int, from: Long = SECOND): Long {
        var worst = 0L
        var t = from
        val end = from + seconds * SECOND
        while (t < end) {
            clock.anchor(card.read(t))
            if (t > end - checkSeconds * SECOND) {
                val f = card.read(t).frame + RATE / 4
                worst = maxOf(worst, abs(clock.nanosAt(f) - card.truth(f)))
            }
            t += SECOND / 10
        }
        return worst
    }

    // A clock that averaged the pairs would sit half a step (5 ms) late; one that did not track drift would be off by
    // 120 ms after ten minutes at 200 ppm.
    @Test
    fun `the clock holds the time of a frame within 3 ms through stepped late pairs and 200 ppm drift`() {
        for (ppm in listOf(-200.0, 0.0, 200.0)) {
            val worst = worstError(Card(ppm), MidiClock(RATE), seconds = 600, checkSeconds = 60)
            assertTrue(worst < 3_000_000, "ppm $ppm: off by ${worst / 1000} us")
        }
    }

    // An underrun stalls the speaker while the system clock runs on; a clock that only crept toward late pairs would
    // take minutes to catch up with half a second.
    @Test
    fun `the clock re-anchors after the speaker stalls for half a second`() {
        val card = Card(0.0)
        val clock = MidiClock(RATE)
        worstError(card, clock, seconds = 60, checkSeconds = 1)
        card.stall = SECOND / 2
        val first = worstError(card, clock, seconds = 1, checkSeconds = 1, from = 61 * SECOND)
        val settled = worstError(card, clock, seconds = 30, checkSeconds = 5, from = 62 * SECOND)
        assertTrue(first < 15_000_000, "first second after the stall: off by ${first / 1000} us")
        assertTrue(settled < 3_000_000, "settled: off by ${settled / 1000} us")
    }
}
