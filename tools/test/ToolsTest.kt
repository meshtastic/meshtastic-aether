package org.meshtastic.aether.tools

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val SR = 48_000

private fun analyze(frames: Int, window: Int = frames, f: (Int) -> Pair<Float, Float>): List<WindowStats> {
    val a = WindowAnalyzer(SR, window.toLong())
    val buf = FloatArray(frames * 2)
    for (i in 0 until frames) {
        val (l, r) = f(i)
        buf[2 * i] = l
        buf[2 * i + 1] = r
    }
    a.push(buf, frames)
    return a.finish()
}

class ArgParserTest {
    @Test
    fun `a full render command parses density hours path and seed`() {
        val c = parseArgs(listOf("busy", "1", "/tmp/x.wav", "7")) as Command.Render
        assertEquals(Density.Busy, c.density)
        assertEquals(1.0, c.hours)
        assertEquals("/tmp/x.wav", c.out)
        assertEquals(7L, c.seed)
    }

    @Test
    fun `the seed defaults to one and the density is case insensitive`() {
        val c = parseArgs(listOf("SPARSE", "0.5", "o.wav")) as Command.Render
        assertEquals(Density.Sparse, c.density)
        assertEquals(0.5, c.hours)
        assertEquals(1L, c.seed)
    }

    @Test
    fun `the analyze command takes exactly one path`() {
        assertEquals(Command.Analyze("a.wav"), parseArgs(listOf("analyze", "a.wav")))
        assertFailsWith<IllegalArgumentException> { parseArgs(listOf("analyze")) }
        assertFailsWith<IllegalArgumentException> { parseArgs(listOf("analyze", "a", "b")) }
    }

    @Test
    fun `bad densities hours and seeds are rejected`() {
        assertFailsWith<IllegalArgumentException> { parseArgs(listOf("loud", "1", "o.wav")) }
        assertFailsWith<IllegalArgumentException> { parseArgs(listOf("busy", "0", "o.wav")) }
        assertFailsWith<IllegalArgumentException> { parseArgs(listOf("busy", "-1", "o.wav")) }
        assertFailsWith<IllegalArgumentException> { parseArgs(listOf("busy", "NaN", "o.wav")) }
        assertFailsWith<IllegalArgumentException> { parseArgs(listOf("busy", "x", "o.wav")) }
        assertFailsWith<IllegalArgumentException> { parseArgs(listOf("busy", "1", "o.wav", "s")) }
        assertFailsWith<IllegalArgumentException> { parseArgs(emptyList()) }
        assertFailsWith<IllegalArgumentException> { parseArgs(listOf("busy", "1")) }
    }

    @Test
    fun `the weekday of the fixed start epoch is Wednesday and 1970 began on a Thursday`() {
        // 1970-01-01 was a Thursday (4); 2026-11-25 20:00 UTC was a Wednesday (3), minute 1200 of the day.
        assertEquals(4, momentAt(0L).dayOfWeek)
        assertEquals(3, momentAt(1_795_636_800_000L).dayOfWeek)
        assertEquals(20 * 60, momentAt(1_795_636_800_000L).minuteOfDay)
    }
}

class AnalyzerTest {
    @Test
    fun `a 3000 Hz sine at amplitude one half has peak half rms half over root two and centroid 3000`() {
        // 3000 Hz is bin 256 of a 4096-point FFT at 48 kHz, so the Hann spectrum is symmetric about it.
        val st = analyze(SR * 4) { i ->
            val x = (0.5 * sin(2.0 * PI * 3000.0 * i / SR)).toFloat()
            x to x
        }.single()
        assertEquals(0.5, st.peak, 1e-4)
        assertEquals(0.5 / sqrt(2.0), st.rms, 1e-3)
        assertEquals(0.0, st.dc, 1e-3)
        assertEquals(3000.0, st.centroidHz, 1.0)
        assertEquals(0L, st.clippedCount)
        assertEquals(0L, st.nanCount)
    }

    @Test
    fun `a signal pinned at full scale counts every sample as clipped and has dc one`() {
        val st = analyze(100) { 1f to 1f }.single()
        assertEquals(200L, st.clippedCount)
        assertEquals(1.0, st.peak, 1e-9)
        assertEquals(1.0, st.rms, 1e-9)
        assertEquals(1.0, st.dc, 1e-9)
    }

    @Test
    fun `a sine driven to twice full scale and clamped counts only its rail samples`() {
        // 48 samples per period, 7.5 degrees each; amplitude 2 reaches the rail where |sin| >= 0.5:
        // 30..150 degrees is i = 4..20 (17 samples) and 210..330 degrees is i = 28..44 (17); 34 frames, 68 samples.
        val st = analyze(48) { i ->
            val x = (2.0 * sin(2.0 * PI * i / 48.0)).coerceIn(-1.0, 1.0).toFloat()
            x to x
        }.single()
        assertEquals(68L, st.clippedCount)
        assertEquals(1.0, st.peak, 1e-9)
    }

    @Test
    fun `non finite samples are counted and excluded from the level statistics`() {
        val st = analyze(10) { i -> if (i < 3) Float.NaN to 0.25f else 0.25f to 0.25f }.single()
        assertEquals(3L, st.nanCount)
        assertEquals(0.25, st.peak, 1e-9)
        assertEquals(0.25, st.rms, 1e-9)
    }

    @Test
    fun `three tone bursts after silence count as three onsets`() {
        // 1 s silence, then 3 bursts of 0.3 s of a 440 Hz tone at 0.5, one burst per second.
        val st = analyze(SR * 4) { i ->
            val t = i.toDouble() / SR
            val inBurst = (0 until 3).any { t >= 1.0 + it && t < 1.3 + it }
            val x = if (inBurst) (0.5 * sin(2.0 * PI * 440.0 * t)).toFloat() else 0f
            x to x
        }.single()
        assertEquals(3, st.onsets)
    }

    @Test
    fun `a steady tone after silence has one onset and the silence reports its length`() {
        // 2 s of zeros then a steady tone for 2 s: one onset and a longest quiet run of 2 s (10 ms hops).
        val st = analyze(SR * 4) { i ->
            val x = if (i < SR * 2) 0f else (0.5 * sin(2.0 * PI * 440.0 * i / SR)).toFloat()
            x to x
        }.single()
        assertEquals(1, st.onsets)
        assertEquals(2.0, st.longestQuietSec, 0.011)
    }

    @Test
    fun `two separate silences report the longer one and not their sum`() {
        // 0.5 s silence, 1 s tone, 0.3 s silence, 1 s tone: longest quiet run is 0.5 s (10 ms hops), never 0.8 s.
        val st = analyze((SR * 2.8).toInt()) { i ->
            val t = i.toDouble() / SR
            val quiet = t < 0.5 || (t >= 1.5 && t < 1.8)
            val x = if (quiet) 0f else (0.5 * sin(2.0 * PI * 440.0 * t)).toFloat()
            x to x
        }.single()
        assertEquals(0.5, st.longestQuietSec, 0.011)
    }

    @Test
    fun `windows split the stream at the window length`() {
        val stats = analyze(SR * 3, window = SR) { 0.1f to 0.1f }
        assertEquals(3, stats.size)
        assertEquals(listOf(0.0, 1.0, 2.0), stats.map { it.startSec })
        assertTrue(stats.all { it.frames == SR.toLong() })
    }
}

class StartInstantTest {
    @Test
    fun `the at flag sets the render start and may sit anywhere after the density`() {
        // 2026-11-29T23:00:00Z is the Wednesday 20:00 epoch 1_795_636_800_000 plus 4 d 3 h: a Sunday night, the Phrygian hours before the Monday key.
        val c = parseArgs(listOf("sparse", "--at", "2026-11-29T23:00:00Z", "1", "o.wav", "3")) as Command.Render
        assertEquals(1_795_636_800_000L + 4 * 86_400_000L + 3 * 3_600_000L, c.startEpochMs)
        assertEquals(3L, c.seed)
        assertEquals(7, momentAt(c.startEpochMs).dayOfWeek)
        assertEquals(23 * 60, momentAt(c.startEpochMs).minuteOfDay)
    }

    @Test
    fun `a missing or malformed instant is rejected`() {
        assertFailsWith<IllegalArgumentException> { parseArgs(listOf("sparse", "1", "o.wav", "--at")) }
        assertFailsWith<IllegalArgumentException> { parseArgs(listOf("sparse", "1", "o.wav", "--at", "sunday")) }
        assertFailsWith<IllegalArgumentException> { parseArgs(listOf("analyze", "x.wav", "--at", "2026-11-29T23:00:00Z")) }
    }
}

class LoudnessTest {
    private fun sine(amplitude: Double, hz: Double, i: Int) = (amplitude * sin(2.0 * PI * hz * i / SR)).toFloat()

    @Test
    fun `a stereo 1 kHz sine at minus 23 dBFS reads minus 23 LUFS as in EBU Tech 3341`() {
        // EBU Tech 3341 test signal 1: 1000 Hz stereo sine, -23.0 dBFS peak per channel, expected -23.0 +-0.1 LUFS.
        val a = Math.pow(10.0, -23.0 / 20)
        val st = analyze(SR * 20) { i -> sine(a, 1000.0, i).let { it to it } }.single()
        assertEquals(-23.0, st.lufs, 0.1)
    }

    @Test
    fun `quiet passages below the relative gate do not pull integrated loudness down`() {
        // EBU Tech 3341 test signal 3: 10 s at -36 dBFS, 60 s at -23 dBFS, 10 s at -36 dBFS reads -23.0 +-0.1 LUFS.
        val loud = Math.pow(10.0, -23.0 / 20)
        val soft = Math.pow(10.0, -36.0 / 20)
        val st = analyze(SR * 80) { i ->
            val a = if (i < SR * 10 || i >= SR * 70) soft else loud
            sine(a, 1000.0, i).let { it to it }
        }.single()
        assertEquals(-23.0, st.lufs, 0.1)
    }

    @Test
    fun `a 60 Hz tone sits in the low band and a 1 kHz tone does not`() {
        val low = analyze(SR * 4) { i -> sine(0.5, 60.0, i).let { it to it } }.single()
        val high = analyze(SR * 4) { i -> sine(0.5, 1000.0, i).let { it to it } }.single()
        assertTrue(low.lowBandRatio > 0.99, "60 Hz low band ${low.lowBandRatio}")
        assertTrue(high.lowBandRatio < 0.01, "1 kHz low band ${high.lowBandRatio}")
    }

    /** A 440 Hz tone that dips 20 dB for 2 s once every [periodSec], over 300 s. */
    private fun dips(periodSec: Double) = analyze(SR * 300) { i ->
        val t = i.toDouble() / SR
        val a = if (t % periodSec < 2.0) 0.05 else 0.5
        sine(a, 440.0, i).let { it to it }
    }.single()

    /** A 440 Hz tone whose level swells smoothly between 0.1 and 0.5 once every [periodSec], over 300 s. */
    private fun swell(periodSec: Double) = analyze(SR * 300) { i ->
        val t = i.toDouble() / SR
        sine(0.3 + 0.2 * kotlin.math.cos(2.0 * PI * t / periodSec), 440.0, i).let { it to it }
    }.single()

    @Test
    fun `a level dip every 20 s scores a pulse and a smooth 17 s swell does not`() {
        // The old bed re-struck every pad and drone on one 20 s tick. A 17 s swell still correlates at a 20 s lag
        // (cos of 2 pi 20 over 17 is about 0.44) but more at 17 s, so it must not read as the 20 s pulse.
        val pulse = dips(20.0).pulse20
        val loop = swell(17.0).pulse20
        assertTrue(pulse > 0.5, "20 s dips $pulse")
        assertTrue(loop < 0.1, "17 s swell $loop")
    }

    @Test
    fun `a rest 54 dB under the music counts as a rest though it is not digital silence`() {
        // 60 s of tone, 20 s at amplitude 0.001 (54 dB down, above the absolute quiet floor), 60 s of tone.
        val st = analyze(SR * 140) { i ->
            val t = i.toDouble() / SR
            val a = if (t >= 60.0 && t < 80.0) 0.001 else 0.5
            sine(a, 440.0, i).let { it to it }
        }.single()
        assertEquals(0.0, st.longestQuietSec, 1e-9)
        assertEquals(20.0, st.longestRestSec, 0.11)
    }

    @Test
    fun `channels in opposite polarity are not a rest`() {
        // A signed channel average cancels to zero when the right channel is the left inverted, though both play.
        val st = analyze(SR * 140) { i ->
            val x = sine(0.5, 440.0, i)
            val t = i.toDouble() / SR
            if (t >= 60.0 && t < 80.0) x to -x else x to x
        }.single()
        assertEquals(0.0, st.longestRestSec, 1e-9)
    }

    @Test
    fun `a DC offset does not count as low band energy`() {
        // A Hann window spreads DC into the bin next to zero (11.7 Hz at 48 kHz) unless the mean comes off first.
        val st = analyze(SR * 4) { i -> (0.5f + sine(0.2, 1000.0, i)).let { it to it } }.single()
        assertTrue(st.lowBandRatio < 0.01, "low band ${st.lowBandRatio} from DC")
    }
}
