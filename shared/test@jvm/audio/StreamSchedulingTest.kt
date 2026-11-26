package org.meshtastic.ambient.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamSchedulingTest {
    // 4800 frames at 48 kHz is exactly 0.1 s, so every expected time below is plain arithmetic.
    private fun scheduler() = ChunkScheduler(sampleRate = 48_000, chunkFrames = 4_800, marginSeconds = 0.02)

    @Test
    fun `back to back chunks start where the previous one ends`() {
        val s = scheduler().also { it.begin(10.0) }
        assertEquals(10.02, s.claim(10.0), 1e-9)
        assertEquals(10.12, s.claim(10.0), 1e-9)
        assertEquals(10.22, s.claim(10.05), 1e-9)
    }

    @Test
    fun `a chunk after a long stall starts just ahead of now instead of in the past`() {
        val s = scheduler().also { it.begin(10.0) }
        s.claim(10.0)
        s.claim(10.0)
        // The schedule ends at 10.22; the main thread then stalls until 15.0.
        assertEquals(15.02, s.claim(15.0), 1e-9)
    }

    @Test
    fun `catch-up chunks after a stall never overlap`() {
        val s = scheduler().also { it.begin(10.0) }
        var end = 0.0
        val nows = listOf(10.0, 10.1, 10.2, 14.0, 14.0, 14.0, 14.1)
        for (now in nows) {
            val start = s.claim(now)
            assertTrue(start >= end - 1e-9, "chunk at $start overlaps previous ending $end")
            assertTrue(start >= now, "chunk at $start is before now $now")
            end = start + 0.1
        }
    }

    @Test
    fun `a chunk is due only while the schedule is less than the lookahead ahead`() {
        val s = scheduler().also { it.begin(10.0) }
        assertTrue(s.needsChunk(10.0, 1.0))
        repeat(10) { s.claim(10.0) }
        // Ten chunks end at 11.02, which is 1.02 s ahead of 10.0.
        assertEquals(false, s.needsChunk(10.0, 1.0))
        assertTrue(s.needsChunk(10.05, 1.0))
    }

    @Test
    fun `played frames follow the clock when nothing was skipped`() {
        val s = scheduler().also { it.begin(10.0) }
        repeat(3) { s.claim(10.0) }
        // Playback started at 10.02, so at 10.17 exactly 0.15 s = 7200 frames have played.
        assertEquals(7_200L, s.playedFrames(10.17))
    }

    @Test
    fun `played frames never exceed what was scheduled during a stall`() {
        val s = scheduler().also { it.begin(10.0) }
        repeat(2) { s.claim(10.0) }
        assertEquals(9_600L, s.playedFrames(12.0))
    }

    @Test
    fun `played frames exclude the silent gap a stall left behind`() {
        val s = scheduler().also { it.begin(10.0) }
        s.claim(10.0)
        s.claim(10.0)
        s.claim(15.0)
        // Two chunks played 10.02 to 10.22, silence to 15.02, then 0.05 s of the third: 9600 + 2400.
        assertEquals(12_000L, s.playedFrames(15.07))
        assertEquals(14_400L, s.playedFrames(15.12))
    }

    @Test
    fun `before playback begins no frames have played`() {
        val s = scheduler().also { it.begin(10.0) }
        assertEquals(0L, s.playedFrames(10.0))
    }

    @Test
    fun `a full write advances to the end`() {
        assertEquals(1_024, advanceWrite(0, 1_024, 1_024))
    }

    @Test
    fun `a partial write continues from where it stopped`() {
        assertEquals(300, advanceWrite(0, 1_024, 300))
        assertEquals(1_024, advanceWrite(300, 1_024, 724))
    }

    @Test
    fun `a write that took nothing makes no progress`() {
        assertEquals(300, advanceWrite(300, 1_024, 0))
    }

    @Test
    fun `a negative write result is a failure`() {
        assertEquals(WRITE_FAILED, advanceWrite(0, 1_024, -6))
        assertEquals(WRITE_FAILED, advanceWrite(512, 1_024, -3))
    }

    @Test
    fun `a write result larger than requested is capped at the end`() {
        assertEquals(1_024, advanceWrite(1_000, 1_024, 500))
    }
}
