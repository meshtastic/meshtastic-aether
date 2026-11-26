package org.meshtastic.aether.visual

import kotlin.math.ceil
import kotlin.math.round

/** One window of frame timing: [frames] timestamps seen, [fps] over the window, [p95Ms] and [worstMs] frame times. */
class FrameReport(val frames: Int, val fps: Double, val p95Ms: Double, val worstMs: Double) {
    /** The log fragment, for example `fps=58.9 p95ms=17.9 worst=41.2`. */
    fun format(): String = "fps=${tenths(fps)} p95ms=${tenths(p95Ms)} worst=${tenths(worstMs)}"

    private fun tenths(v: Double): String {
        val t = round(v * 10).toLong()
        return "${t / 10}.${t % 10}"
    }
}

/**
 * Frame timing for one window, recorded from the render loop without allocating. [record] takes a frame's timestamp
 * in nanoseconds; [takeReport] summarises the window and starts a new one.
 *
 * With n timestamps there are n-1 frame times. fps is (n-1) divided by the span from first to last timestamp. p95 is the
 * nearest-rank percentile: sort the frame times ascending and take the one at 1-based rank ceil(0.95 * count). Worst is
 * the largest frame time. At most [CAPACITY] frame times are kept; beyond that the oldest are overwritten.
 *
 * [record] runs on the render thread and [takeReport] on another; a report taken mid-frame can be off by one frame,
 * which is acceptable for a log line.
 */
class FrameStats {
    private val deltas = LongArray(CAPACITY)
    private var count = 0
    private var firstNanos = 0L
    private var lastNanos = 0L
    private var frames = 0

    fun record(nowNanos: Long) {
        val seen = frames
        if (seen == 0) {
            firstNanos = nowNanos
        } else {
            deltas[(seen - 1) % CAPACITY] = nowNanos - lastNanos
            if (count < CAPACITY) count++
        }
        lastNanos = nowNanos
        frames = seen + 1
    }

    /** The window since the last call, then a reset; null when fewer than two frames were recorded. */
    fun takeReport(): FrameReport? {
        val n = count
        val total = frames
        val span = lastNanos - firstNanos
        frames = 0
        count = 0
        if (n < 1 || span <= 0L) return null
        val sorted = deltas.copyOf(n)
        sorted.sort()
        val rank = ceil(P95 * n).toInt().coerceIn(1, n)
        return FrameReport(
            frames = total,
            fps = (total - 1) * NANOS_PER_SECOND / span,
            p95Ms = sorted[rank - 1] / NANOS_PER_MS,
            worstMs = sorted[n - 1] / NANOS_PER_MS,
        )
    }

    private companion object {
        const val CAPACITY = 16_384
        const val P95 = 0.95
        const val NANOS_PER_MS = 1_000_000.0
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
