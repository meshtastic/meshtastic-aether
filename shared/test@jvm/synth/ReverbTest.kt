package org.meshtastic.aether.synth

import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReverbTest {
    private fun impulseResponse(rt60: Double, seconds: Int, rate: Int = TEST_RATE): DoubleArray {
        val reverb = Reverb(rate)
        reverb.setRt60(rt60)
        val block = 512
        val total = seconds * rate
        val energy = DoubleArray(total)
        val input = FloatArray(block)
        val l = FloatArray(block)
        val r = FloatArray(block)
        var done = 0
        while (done < total) {
            input.fill(0f)
            if (done == 0) input[0] = 1f
            reverb.process(input, l, r, block)
            for (i in 0 until block) {
                if (done + i < total) energy[done + i] = l[i].toDouble() * l[i] + r[i].toDouble() * r[i]
            }
            done += block
        }
        return energy
    }

    /** RT60 from the Schroeder backward integral, extrapolating the -5 to -35 dB slope. */
    private fun measuredRt60(energy: DoubleArray, rate: Int): Double {
        val curve = DoubleArray(energy.size)
        var acc = 0.0
        for (i in energy.indices.reversed()) {
            acc += energy[i]
            curve[i] = acc
        }
        val total = curve[0]
        fun timeAt(db: Double): Double {
            for (i in curve.indices) if (10.0 * log10(curve[i] / total) <= db) return i.toDouble() / rate
            return Double.NaN
        }
        return 2.0 * (timeAt(-35.0) - timeAt(-5.0))
    }

    // Regression: a feedback gain that is wrong by a constant (or a loop that gains) shows up as a tail outside the spec's 10-30 s.
    @Test
    fun `the largest reverb size rings for between 10 and 30 seconds`() {
        val t = measuredRt60(impulseResponse(rt60ForSize(1f), 70), TEST_RATE)
        assertTrue(t in 10.0..30.0, "measured RT60 $t")
    }

    @Test
    fun `the smallest reverb size still rings for at least 10 seconds`() {
        val t = measuredRt60(impulseResponse(rt60ForSize(0f), 40), TEST_RATE)
        assertTrue(t in 10.0..30.0, "measured RT60 $t")
    }

    // Regression: growing feedback or a broken allpass sign makes the energy envelope rise after the build-up.
    @Test
    fun `the impulse response energy never rises from one second to the next`() {
        val rate = TEST_RATE
        val energy = impulseResponse(rt60ForSize(1f), 30)
        var previous = Double.MAX_VALUE
        for (s in 1 until 30) {
            var e = 0.0
            for (i in s * rate until (s + 1) * rate) e += energy[i]
            assertTrue(e <= previous, "window $s energy $e rose above $previous")
            previous = e
        }
    }

    @Test
    fun `a longer reverb setting gives a longer measured tail`() {
        val short = measuredRt60(impulseResponse(rt60ForSize(0f), 40), TEST_RATE)
        val long = measuredRt60(impulseResponse(rt60ForSize(1f), 70), TEST_RATE)
        assertTrue(long > short * 1.3, "short $short long $long")
    }

    // Regression: without the injected floor decaying feedback reaches subnormal floats, which stall the CPU on real hardware.
    @Test
    fun `five silent minutes after an impulse leave no subnormal floats and a live floor`() {
        val rate = 16000
        val reverb = Reverb(rate)
        reverb.setRt60(10.0)
        val block = 512
        val input = FloatArray(block)
        val l = FloatArray(block)
        val r = FloatArray(block)
        input[0] = 1f
        var done = 0
        while (done < 300 * rate) {
            reverb.process(input, l, r, block)
            input[0] = 0f
            done += block
        }
        val stats = reverb.stateStats()
        assertEquals(0f, stats[1], "subnormal count")
        assertTrue(stats[0] > 1.0e-21f, "state peak ${stats[0]} means the floor is gone")
        assertTrue(stats[0] < 1.0e-9f, "state peak ${stats[0]} should be only the floor")
    }

    private fun firstChannelDifference(rate: Int): Int {
        val reverb = Reverb(rate)
        reverb.setRt60(0.1)
        val n = 6000
        val input = FloatArray(n)
        input[0] = 1f
        val l = FloatArray(n)
        val r = FloatArray(n)
        reverb.process(input, l, r, n)
        for (i in 0 until n) if (abs(l[i] - r[i]) > 1.0e-9f) return i
        return -1
    }

    // Regression: a different network (Freeverb combs or a swapped tank) still passes the tail-length tests. With the loop
    // feedback effectively off the two channels share the four input allpasses and split only at the first tank allpass:
    // 1653 samples at 32 kHz on the left against 1913 on the right in the Clouds source (reverb.h), one sample less
    // because the tail read is len - 1 behind the write.
    @Test
    fun `left and right outputs part at the first Clouds tank allpass of 1653 samples`() {
        val at32 = firstChannelDifference(32000)
        assertTrue(at32 in 1650..1655, "channels first differ at $at32 samples")
        val at48 = firstChannelDifference(48000)
        assertTrue(at48 in 2477..2483, "at 48 kHz the channels first differ at $at48 samples, expected 1653 x 1.5")
    }
}
