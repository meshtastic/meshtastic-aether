package org.meshtastic.aether.audio

/** Fills [out] with [frames] interleaved stereo float frames. Never allocates; runs on a thread the sink owns. */
fun interface BlockRenderer {
    fun render(out: FloatArray, frames: Int)
}

interface AudioSink {
    val sampleRate: Int

    fun start(renderer: BlockRenderer)

    fun stop()

    /** Frames the speaker has played so far, the clock the composer and visuals share. */
    fun playbackFrame(): Long
}
