package org.meshtastic.aether.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Expected values come from Woodworth's delay formula and the Brown and Duda (1998) head-shadow model, not from a run of the code. */
class BinauralPannerTest {
    private val rate = 48_000
    private val deg = PI / 180.0

    private fun render(lateral: Float, input: FloatArray, panner: BinauralPanner = BinauralPanner(rate), block: Int = 64): Pair<FloatArray, FloatArray> {
        val left = FloatArray(input.size)
        val right = FloatArray(input.size)
        val l = FloatArray(block)
        val r = FloatArray(block)
        var at = 0
        while (at < input.size) {
            val n = minOf(block, input.size - at)
            panner.process(input.copyOfRange(at, at + n), n, lateral, l, r)
            l.copyInto(left, at, 0, n)
            r.copyInto(right, at, 0, n)
            at += n
        }
        return Pair(left, right)
    }

    @Test
    fun `the interaural delay follows Woodworth at zero, thirty and ninety degrees`() {
        assertEquals(0.0, BinauralPanner.itdSeconds(0.0), 1e-12)
        assertEquals(261.1e-6, BinauralPanner.itdSeconds(30 * deg), 0.5e-6)
        assertEquals(655.8e-6, BinauralPanner.itdSeconds(90 * deg), 0.5e-6)
        assertEquals(31.48, BinauralPanner.itdSamples(90 * deg, 48_000), 0.01)
    }

    @Test
    fun `the head shadow alpha matches the published curve`() {
        assertEquals(2.0, BinauralPanner.shadowAlpha(0.0), 1e-9)
        assertEquals(0.7565, BinauralPanner.shadowAlpha(90 * deg), 1e-3)
        assertEquals(0.1, BinauralPanner.shadowAlpha(150 * deg), 1e-9)
    }

    @Test
    fun `a shelf passes lows untouched and sets the highs to its relative alpha`() {
        for (theta in listOf(0.0, 45.0, 90.0, 135.0, 180.0)) {
            val s = BinauralPanner.shelf(theta * deg, rate)
            val dc = (s.b0 + s.b1) / (1f + s.a1)
            val nyquist = (s.b0 - s.b1) / (1f - s.a1)
            val expected = (BinauralPanner.shadowAlpha(theta * deg) / BinauralPanner.shadowAlpha(90 * deg)).coerceIn(0.0, BinauralPanner.MAX_SHELF)
            assertEquals(1.0, dc.toDouble(), 1e-5, "DC at $theta degrees")
            assertEquals(expected, nyquist.toDouble(), 1e-4, "highs at $theta degrees")
        }
    }

    @Test
    fun `a voice straight ahead is the same in both ears as the constant power centre`() {
        val input = FloatArray(512) { sin(it * 0.07f) * 0.5f }
        val (l, r) = render(0f, input)
        val centre = cos(PI / 4).toFloat()
        for (i in input.indices) {
            assertEquals(input[i] * centre, l[i], 1e-6f)
            assertEquals(input[i] * centre, r[i], 1e-6f)
        }
    }

    @Test
    fun `a source on the other side gives exactly the mirrored ears`() {
        val input = FloatArray(1024) { sin(it * 0.05f) * 0.5f + sin(it * 0.31f) * 0.2f }
        val (la, ra) = render(0.6f, input)
        val (lb, rb) = render(-0.6f, input)
        for (i in input.indices) {
            assertEquals(la[i], rb[i], 0f)
            assertEquals(ra[i], lb[i], 0f)
        }
    }

    @Test
    fun `a hard east impulse reaches the right ear first and the left about thirty one samples later and duller`() {
        val warm = FloatArray(512)
        val impulse = FloatArray(512).also { it[0] = 1f }
        val panner = BinauralPanner(rate)
        render(1f, warm, panner)
        val (l, r) = render(1f, impulse, panner)
        fun peakAt(x: FloatArray) = x.indices.maxByOrNull { abs(x[it]) }!!
        val gap = peakAt(l) - peakAt(r)
        assertTrue(gap in 24..40, "the far ear lags by $gap samples")
        assertTrue(abs(r[peakAt(r)]) > 2f * abs(l[peakAt(l)]), "the near ear is louder at the click, highs shadowed in the far ear")
    }

    // A delay that jumped to its new value instead of ramping would step the far ear by a delay's worth of signal.
    @Test
    fun `the far ear delay ramps in a straight line across a chunk and never moves faster than a sample a sample`() {
        val n = 64
        assertEquals(0.0 + (31.5 - 0.0) / n, BinauralPanner.rampedDelay(0.0, 31.5, 0, n), 1e-12)
        assertEquals(31.5, BinauralPanner.rampedDelay(0.0, 31.5, n - 1, n), 1e-12)
        var previous = 0.0
        for (i in 0 until n) {
            val d = BinauralPanner.rampedDelay(0.0, 31.5, i, n)
            assertTrue(d - previous < 1.0, "delay moved ${d - previous} samples in one")
            previous = d
        }
        assertEquals(12.5, BinauralPanner.rampedDelay(12.5, 12.5, 10, n), 1e-12)
    }
}
