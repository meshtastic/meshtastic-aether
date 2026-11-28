package org.meshtastic.aether.midi

/**
 * The MIDI choices the settings screen keeps. [output] names the output to play to, null for none. With
 * [playAlongside] false the built-in synth falls silent while an output plays. [releaseSeconds] is how long a channel
 * rests before it is retuned, which should cover the receiver's longest release; [latencyMillis] delays every message,
 * for a receiver that sounds before the built-in synth.
 */
data class MidiChoice(
    val output: String? = null,
    val playAlongside: Boolean = false,
    val releaseSeconds: Double = DEFAULT_RELEASE,
    val latencyMillis: Int = 0,
) {
    companion object {
        const val DEFAULT_RELEASE = 2.0
        val RELEASES = listOf(0.5, DEFAULT_RELEASE, 8.0)
        const val MIN_LATENCY = -100
        const val MAX_LATENCY = 200
        const val LATENCY_STEP = 10
    }
}
