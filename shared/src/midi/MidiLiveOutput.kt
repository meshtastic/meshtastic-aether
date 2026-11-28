package org.meshtastic.aether.midi

import org.meshtastic.aether.audio.FrameTime
import org.meshtastic.aether.log.Log
import org.meshtastic.aether.music.ComposerOutput
import org.meshtastic.aether.music.NoteSink

/** Places a note 100 ms ahead of its frame and bends its channel 50 ms ahead: the live output's horizon. */
private const val LIVE_AHEAD_SECONDS = 0.1
private const val TAG = "midi"

/**
 * The composer's notes on a MIDI [output], timed to sound when the audio sink plays their frame. The composer can
 * schedule a note at the frame it has rendered to, so the mapper is drained a horizon behind that frame and every
 * note is in hand before its channel's bend is due; the messages then reach the port the sink's buffer ahead of the
 * speaker. [latencyNanos] delays every message, for a receiver that sounds sooner than the built-in synth. Called
 * from the composer's thread only.
 */
class MidiLiveOutput(
    private val output: MidiOutput,
    sampleRate: Int,
    config: MidiConfig = MidiConfig(dispatchAheadSeconds = LIVE_AHEAD_SECONDS),
    private val latencyNanos: Long = 0L,
) : NoteSink {
    private val mapper = MidiMapper(sampleRate, config)
    private val clock = MidiClock(sampleRate)
    private val behind = (config.dispatchAheadSeconds * sampleRate).toLong()
    private var drained = Long.MIN_VALUE

    val stats: MidiStats get() = mapper.stats
    val name: String get() = output.name

    override fun schedule(output: ComposerOutput) = mapper.schedule(output)

    /** Anchors the clock on [played] and sends every message due up to the horizon behind [renderedTo]. */
    fun pump(renderedTo: Long, played: FrameTime?) {
        played?.let(clock::anchor)
        if (!clock.anchored) return
        drained = renderedTo - behind
        mapper.drain(drained) { e -> output.send(e.data, clock.nanosAt(e.frame) + latencyNanos) }
    }

    /** Drops what is queued, ends every note the port was sent, centres the bends and releases the port. */
    fun close() {
        output.cancel()
        if (drained != Long.MIN_VALUE) mapper.close(drained) { e -> output.send(e.data, Long.MIN_VALUE) }
        output.close()
    }
}

/** Opens the output [name] names for live playing at [sampleRate]; null, with the outputs there are logged, if none. */
fun openLiveOutput(name: String, sampleRate: Int): MidiLiveOutput? {
    val output = openMidiOutput(name)
    if (output == null) {
        Log.w(TAG, "no MIDI output matches \"$name\"; outputs: ${midiOutputNames().joinToString()}")
        return null
    }
    Log.i(TAG, "playing to ${output.name}")
    return MidiLiveOutput(output, sampleRate)
}
