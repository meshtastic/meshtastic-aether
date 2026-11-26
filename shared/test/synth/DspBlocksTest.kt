package org.meshtastic.ambient.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DspBlocksTest {
    private val rate = 48000

    private fun tone(hz: Double, frames: Int) = FloatArray(frames) { sin(2.0 * PI * hz * it / rate).toFloat() }

    private fun amplitude(x: FloatArray, from: Int): Double {
        var peak = 0f
        for (i in from until x.size) if (abs(x[i]) > peak) peak = abs(x[i])
        return peak.toDouble()
    }

    // Regression: a wrong feedback sign or coefficient in the filter port loses the 12 dB per octave slope.
    @Test
    fun `a low-pass at 500 Hz cuts 8 kHz by at least 40 dB and passes 50 Hz`() {
        val svf = Svf()
        svf.setFq(500f / rate, 0.7071f)
        val highTone = tone(8000.0, rate)
        val high = FloatArray(rate) { svf.lowPass(highTone[it]) }
        val low = Svf().also { it.setFq(500f / rate, 0.7071f) }
        val lowTone = tone(50.0, rate)
        val lowOut = FloatArray(rate) { low.lowPass(lowTone[it]) }
        val cutDb = 20.0 * log10(amplitude(high, rate / 2))
        val passDb = 20.0 * log10(amplitude(lowOut, rate / 2))
        assertTrue(cutDb < -40.0, "8 kHz at $cutDb dB")
        assertTrue(passDb > -1.0, "50 Hz at $passDb dB")
    }

    @Test
    fun `a high-pass removes a constant input`() {
        val svf = Svf()
        svf.setFq(200f / rate, 0.7071f)
        var y = 0f
        repeat(rate) { y = svf.highPass(1f) }
        assertTrue(abs(y) < 1.0e-3f, "DC leaked through at $y")
    }

    @Test
    fun `a band-pass at 1 kHz passes 1 kHz and cuts 100 Hz and 10 kHz`() {
        fun gainDb(hz: Double): Double {
            val svf = Svf()
            svf.setFq(1000f / rate, 4f)
            val t = tone(hz, rate)
            val out = FloatArray(rate) { svf.bandPass(t[it]) }
            return 20.0 * log10(amplitude(out, rate / 2))
        }
        assertTrue(gainDb(1000.0) > gainDb(100.0) + 20.0)
        assertTrue(gainDb(1000.0) > gainDb(10000.0) + 20.0)
    }

    // Regression: a phase wrap off by one sample or a frequency in the wrong units shifts the pitch.
    @Test
    fun `the polyblep saw runs at the requested frequency`() {
        val osc = PolyBlepOsc()
        val hz = 1000.0
        val x = FloatArray(rate) { osc.saw(hz / rate) }
        var rises = 0
        for (i in 1 until x.size) if (x[i - 1] < 0f && x[i] >= 0f) rises++
        assertEquals(1000, rises)
        var sum = 0.0
        for (v in x) sum += v
        assertTrue(abs(sum / x.size) < 0.01, "mean ${sum / x.size}")
        assertTrue(amplitude(x, 0) < 1.1)
    }

    @Test
    fun `the polyblep square is high for the pulse width fraction`() {
        val osc = PolyBlepOsc()
        val x = FloatArray(rate) { osc.square(500.0 / rate, 0.25) }
        val high = x.count { it > 0f }.toDouble() / x.size
        assertEquals(0.75, high, 0.01)
    }

    @Test
    fun `a polyblep saw has less energy far above the fundamental than a naive saw`() {
        val hz = 3001.0
        val osc = PolyBlepOsc()
        val blep = FloatArray(rate) { osc.saw(hz / rate) }
        var phase = 0.0
        val naive = FloatArray(rate) {
            phase += hz / rate
            if (phase >= 1.0) phase -= 1.0
            (2.0 * phase - 1.0).toFloat()
        }
        // Alias components of the naive saw fold below 22 kHz; the polyblep one suppresses them.
        fun band(x: FloatArray): Double {
            var e = 0.0
            var f = 100.0
            while (f < 22000.0) {
                val h = f / hz
                if (abs(h - h.toInt().toDouble()) > 0.2 && abs(h - h.toInt().toDouble()) < 0.8) {
                    e += goertzel(x, 0, rate, f, rate).let { it * it }
                }
                f += 211.0
            }
            return e
        }
        assertTrue(band(blep) < band(naive) * 0.5, "blep ${band(blep)} naive ${band(naive)}")
    }

    @Test
    fun `the sine oscillator completes one cycle per period`() {
        val osc = SineOsc()
        val x = FloatArray(rate) { osc.next(440.0 / rate) }
        var up = 0
        for (i in 1 until x.size) if (x[i - 1] < 0f && x[i] >= 0f) up++
        assertTrue(up in 439..440, "upward crossings $up")
    }

    // Regression: the ported delay compensation is wrong and the string plays out of tune.
    @Test
    fun `a Karplus string sounds within 1 percent of the requested pitch`() {
        for (hz in doubleArrayOf(110.0, 220.0, 440.0, 880.0)) {
            val string = KarplusString(rate, Rng(7))
            string.configure(hz, -0.15f, 0.5f, 0.6f)
            val rng = Rng(3)
            val burst = (rate / hz).toInt()
            val out = FloatArray(rate) { string.process(if (it < burst) rng.nextSigned() else 0f) }
            val peakAt = bestLag(out, 4000, 20000, (rate / hz * 0.9).toInt(), (rate / hz * 1.1).toInt())
            val measured = rate.toDouble() / peakAt
            assertTrue(abs(measured - hz) / hz < 0.01, "asked $hz heard $measured")
        }
    }

    private fun bestLag(x: FloatArray, from: Int, to: Int, minLag: Int, maxLag: Int): Int {
        var best = minLag
        var bestValue = -Double.MAX_VALUE
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in from until to) sum += x[i].toDouble() * x[i + lag]
            if (sum > bestValue) {
                bestValue = sum
                best = lag
            }
        }
        return best
    }

    @Test
    fun `a Karplus string with more damping dies faster`() {
        fun level(damping: Float): Double {
            val string = KarplusString(rate, Rng(7))
            string.configure(220.0, -0.15f, 0.5f, damping)
            val rng = Rng(3)
            val out = FloatArray(rate * 2) { string.process(if (it < 218) rng.nextSigned() else 0f) }
            var e = 0.0
            for (i in rate until rate * 2) e += out[i] * out[i]
            return e
        }
        assertTrue(level(0.3f) < level(0.8f))
    }

    // Regression: the mode frequency or Q formulas are wrong and the strike has no clear fundamental.
    @Test
    fun `the modal resonator rings at its fundamental and not a third away`() {
        val modal = ModalResonator(rate)
        modal.configure(440.0, 0.3f, 0.5f, 3.0f, 0.2f, 14)
        val out = FloatArray(rate) { modal.process(if (it == 0) 1f else 0f) }
        val at = goertzel(out, 4800, 40000, 440.0, rate)
        val off = goertzel(out, 4800, 40000, 440.0 * 1.26, rate)
        assertTrue(at > off * 20.0, "fundamental $at off $off")
    }

    @Test
    fun `a longer ring time keeps the modal resonator louder one second in`() {
        fun level(ring: Float): Double {
            val modal = ModalResonator(rate)
            modal.configure(440.0, 0.3f, 0.5f, ring, 0.2f, 14)
            val out = FloatArray(rate * 2) { modal.process(if (it == 0) 1f else 0f) }
            var e = 0.0
            for (i in rate until rate * 2) e += out[i] * out[i]
            return e
        }
        assertTrue(level(8.0f) > level(0.5f) * 10.0)
    }

    // Regression: a dropped dry-amount term makes the chorus colour or mute the signal when its amount is zero.
    @Test
    fun `an ensemble with zero amount passes the signal through unchanged`() {
        val ensemble = Ensemble(rate)
        ensemble.amount = 0f
        ensemble.depth = 1f
        val l = tone(300.0, 2000)
        val r = tone(450.0, 2000)
        val l2 = l.copyOf()
        val r2 = r.copyOf()
        ensemble.process(l2, r2, 2000)
        for (i in l.indices) {
            assertEquals(l[i], l2[i])
            assertEquals(r[i], r2[i])
        }
    }

    @Test
    fun `an ensemble at full amount keeps a steady tone bounded and still audible`() {
        val ensemble = Ensemble(rate)
        ensemble.amount = 1f
        ensemble.depth = 0.5f
        val l = tone(300.0, rate)
        val r = tone(300.0, rate)
        ensemble.process(l, r, rate)
        val a = amplitude(l, rate / 2)
        assertTrue(a in 0.3..1.2, "amplitude $a")
    }

    // Regression: a sweep rate or crossfade error leaves the shifted tone at the input pitch or at no clear pitch.
    @Test
    fun `the pitch shifter at ratio two moves 500 Hz up to 1 kHz`() {
        val shifter = PitchShifter(rate)
        shifter.ratio = 2f
        val input = tone(500.0, rate)
        val out = FloatArray(rate)
        val pair = FloatArray(2)
        for (i in 0 until rate) {
            shifter.process(input[i], input[i], pair)
            out[i] = pair[0]
        }
        val up = goertzel(out, 8000, rate, 1000.0, rate)
        val same = goertzel(out, 8000, rate, 500.0, rate)
        assertTrue(up > same * 5.0, "1 kHz $up 500 Hz $same")
    }

    // Regression: a limiter that no longer bounds its output lets the sum of voices clip the converter.
    @Test
    fun `the soft limiter is transparent when quiet and never exceeds one`() {
        val lim = SoftLimiter()
        assertEquals(0.5f, lim.process(0.5f))
        assertEquals(-0.3f, lim.process(-0.3f))
        var x = 0f
        var last = 0f
        while (x < 1000f) {
            val y = lim.process(x)
            assertTrue(y <= 1.0f && y >= last, "input $x gave $y after $last")
            last = y
            x += 0.37f
        }
        assertEquals(-lim.process(7f), lim.process(-7f))
        assertTrue(lim.process(50f) > 0.99f)
    }

    @Test
    fun `the soft limiter silences non-finite input and counts it`() {
        val lim = SoftLimiter()
        assertEquals(0f, lim.process(Float.NaN))
        assertEquals(0f, lim.process(Float.POSITIVE_INFINITY))
        assertEquals(2, lim.nonFiniteHits)
    }

    @Test
    fun `an envelope rises to one and falls back to idle after release`() {
        val env = Adsr(1000)
        env.trigger(0.1f, 0.2f, 0.5f, 0.3f)
        var peak = 0f
        repeat(150) { peak = maxOf(peak, env.next()) }
        assertEquals(1f, peak)
        repeat(1000) { env.next() }
        assertEquals(0.5f, env.level, 0.01f)
        env.release()
        repeat(3000) { env.next() }
        assertTrue(env.isIdle)
    }
}
