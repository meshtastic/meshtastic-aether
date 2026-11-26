package org.meshtastic.ambient.audio

import kotlin.math.roundToInt

/** Frames per renderer call; sinks size their own buffers around it. */
const val PUMP_BLOCK_FRAMES = 512

/**
 * Pulls interleaved stereo blocks from a [BlockRenderer] and converts them to 16-bit PCM.
 * Preallocates its scratch buffer so the pump loop allocates nothing.
 */
class BlockPump(private val renderer: BlockRenderer, private val blockFrames: Int = PUMP_BLOCK_FRAMES) {
    private val scratch = FloatArray(blockFrames * 2)

    /** Renders exactly [frames] stereo frames into [out] (2 shorts per frame), clamped to 16-bit range. */
    fun renderPcm16(out: ShortArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = minOf(blockFrames, frames - done)
            renderer.render(scratch, n)
            for (i in 0 until n * 2) out[done * 2 + i] = toPcm16(scratch[i])
            done += n
        }
    }

    /** Same as [renderPcm16] but writes little-endian bytes (4 per frame) for byte-oriented lines. */
    fun renderPcm16Bytes(out: ByteArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = minOf(blockFrames, frames - done)
            renderer.render(scratch, n)
            for (i in 0 until n * 2) {
                val s = toPcm16(scratch[i]).toInt()
                val o = (done * 2 + i) * 2
                out[o] = s.toByte()
                out[o + 1] = (s shr 8).toByte()
            }
            done += n
        }
    }

    /** Renders [frames] stereo float frames straight into [out] for sinks that take float PCM. */
    fun renderFloat(out: FloatArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = minOf(blockFrames, frames - done)
            renderer.render(scratch, n)
            scratch.copyInto(out, done * 2, 0, n * 2)
            done += n
        }
    }

    companion object {
        /** Symmetric scale: full-scale +1.0 maps to 32767 and -1.0 to -32767; out-of-range and NaN are tamed. */
        fun toPcm16(sample: Float): Short {
            if (sample.isNaN()) return 0
            val c = if (sample > 1f) 1f else if (sample < -1f) -1f else sample
            return (c * 32767f).roundToInt().toShort()
        }
    }
}

/** Extends a 32-bit position counter that wraps (Android's playback head) into a monotonic [Long]. */
class WrapSafeFrameCounter {
    private var last = 0
    private var total = 0L

    fun update(head: Int): Long {
        total += (head - last).toLong()
        last = head
        return total
    }
}
