package org.meshtastic.aether.audio

import kotlin.math.roundToLong

/**
 * Places fixed-size chunks back to back on a clock in seconds (Web Audio's `currentTime`).
 * A chunk that would start before `now + margin` is moved up to it, so a stall never leaves
 * catch-up chunks overlapping, and the skipped time is excluded from [playedFrames].
 */
class ChunkScheduler(
    private val sampleRate: Int,
    private val chunkFrames: Int,
    private val marginSeconds: Double = 0.02,
) {
    private var nextStart = 0.0
    private var origin = 0.0
    private var scheduledFrames = 0L
    private var skippedFrames = 0L

    fun begin(now: Double) {
        origin = now + marginSeconds
        nextStart = origin
        scheduledFrames = 0L
        skippedFrames = 0L
    }

    fun needsChunk(now: Double, aheadSeconds: Double): Boolean = nextStart < now + aheadSeconds

    /** Returns the start time for the next chunk and advances the schedule past it. */
    fun claim(now: Double): Double {
        val earliest = now + marginSeconds
        if (nextStart < earliest) {
            skippedFrames += ((earliest - nextStart) * sampleRate).roundToLong()
            nextStart = earliest
        }
        val start = nextStart
        nextStart += chunkFrames.toDouble() / sampleRate
        scheduledFrames += chunkFrames
        return start
    }

    /** Frames of scheduled audio that have actually played by [now]; silent gaps are not counted. */
    fun playedFrames(now: Double): Long {
        val byClock = ((now - origin) * sampleRate).roundToLong() - skippedFrames
        return byClock.coerceIn(0L, scheduledFrames)
    }
}

const val WRITE_FAILED = -1

/**
 * New offset after a blocking `write` of `total - offset` units returned [result]: a negative result
 * is an error code and yields [WRITE_FAILED]; zero keeps the offset; the result is capped at [total].
 */
fun advanceWrite(offset: Int, total: Int, result: Int): Int =
    if (result < 0) WRITE_FAILED else minOf(total, offset + result)
