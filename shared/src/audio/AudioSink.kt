package org.meshtastic.aether.audio

/** Fills [out] with [frames] interleaved stereo float frames. Never allocates; runs on a thread the sink owns. */
fun interface BlockRenderer {
    fun render(out: FloatArray, frames: Int)
}

/** A frame the speaker played and when, in nanoseconds on the monotonic clock MIDI output is scheduled on. */
class FrameTime(val frame: Long, val nanos: Long)

interface AudioSink {
    val sampleRate: Int

    fun start(renderer: BlockRenderer)

    fun stop()

    /** Frames the speaker has played so far, the clock the composer and visuals share. */
    fun playbackFrame(): Long

    /**
     * The playback frame paired with the time it was read, which an external output's clock is anchored on; null
     * where the sink cannot pair them (the output then waits). The pair may lag the speaker by a hardware period.
     */
    fun playbackTimestamp(): FrameTime? = null
}
