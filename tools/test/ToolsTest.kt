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
