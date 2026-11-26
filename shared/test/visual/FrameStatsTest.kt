package org.meshtastic.ambient.visual

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FrameStatsTest {
    private val frame = 16_666_667L
    private val tol = 0.01

    private fun stats(vararg gapsNanos: Long): FrameStats {
        val s = FrameStats()
        var t = 5_000_000_000L
        s.record(t)
        for (g in gapsNanos) {
            t += g
            s.record(t)
        }
        return s
    }

    @Test
    fun `sixty one steady frames over one second report 60 fps and 16 point 67 ms`() {
        val r = assertNotNull(stats(*LongArray(60) { frame }).takeReport())
        assertEquals(61, r.frames)
        assertEquals(60.0, r.fps, tol)
        assertEquals(16.67, r.p95Ms, tol)
        assertEquals(16.67, r.worstMs, tol)
    }

    @Test
    fun `one 100 ms gap among sixty frames is the worst but leaves p95 at nearest rank 57`() {
        val gaps = LongArray(60) { frame }.also { it[30] = 100_000_000L }
        val r = assertNotNull(stats(*gaps).takeReport())
        assertEquals(100.0, r.worstMs, tol)
        assertEquals(16.67, r.p95Ms, tol)
    }

    @Test
    fun `one 100 ms gap among ten frame times is rank 10 so p95 equals the gap`() {
        val gaps = LongArray(10) { frame }.also { it[4] = 100_000_000L }
        val r = assertNotNull(stats(*gaps).takeReport())
        assertEquals(100.0, r.p95Ms, tol)
    }

    @Test
    fun `two 100 ms gaps among twenty frame times move p95 to rank 19`() {
        val one = LongArray(20) { frame }.also { it[3] = 100_000_000L }
        assertEquals(16.67, assertNotNull(stats(*one).takeReport()).p95Ms, tol)
        val two = LongArray(20) { frame }.also { it[3] = 100_000_000L; it[9] = 100_000_000L }
        assertEquals(100.0, assertNotNull(stats(*two).takeReport()).p95Ms, tol)
    }

    @Test
    fun `fps counts the span not the frame count so a stall lowers it`() {
        val gaps = LongArray(10) { 10_000_000L }.also { it[0] = 110_000_000L }
        val r = assertNotNull(stats(*gaps).takeReport())
        assertEquals(10 * 1000.0 / 200.0, r.fps, tol)
    }

    @Test
    fun `taking a report resets the window`() {
        val s = stats(frame, frame, frame)
        assertNotNull(s.takeReport())
        assertNull(s.takeReport())
        s.record(1_000L)
        s.record(1_000L + 20_000_000L)
        assertEquals(20.0, assertNotNull(s.takeReport()).worstMs, tol)
    }

    @Test
    fun `fewer than two frames give no report`() {
        assertNull(FrameStats().takeReport())
        assertNull(stats().takeReport())
    }

    @Test
    fun `a window longer than capacity keeps the newest frame times`() {
        val s = FrameStats()
        var t = 0L
        s.record(t)
        t += 500_000_000L
        s.record(t)
        repeat(16_384) {
            t += frame
            s.record(t)
        }
        assertEquals(16.67, assertNotNull(s.takeReport()).worstMs, tol)
    }

    @Test
    fun `the report formats one decimal each`() {
        assertEquals("fps=58.9 p95ms=17.9 worst=41.2", FrameReport(100, 58.93, 17.94, 41.19).format())
        assertEquals("fps=60.0 p95ms=16.7 worst=16.7", FrameReport(61, 59.99999, 16.666667, 16.666667).format())
    }
}
