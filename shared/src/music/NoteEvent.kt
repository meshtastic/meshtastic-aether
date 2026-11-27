package org.meshtastic.aether.music

/**
 * The timbres the synth builds; the first seven are the original set, the rest are the instrument palette of
 * `Palette`.
 */
enum class Voice {
    Bell, Mallet, Pluck, Breath, Pad, Drone, Shimmer,
    Marimba, Vibes, Kalimba, Glass, Harp, Gong, Bowed, Choir, Sub,
}

/** One sounding note. [atFrame] is in the audio sample counter, the app's single clock. */
data class NoteEvent(
    val atFrame: Long,
    val voice: Voice,
    val pitchHz: Double,
    val velocity: Float,
    val pan: Float,
    val reverbSend: Float,
    val durationFrames: Long,
    val brightness: Float,
    /** The mesh node this note stands for (ornaments, echoes, memory notes), null for the bed. */
    val node: Long? = null,
)

/** Global sound character, changed slowly. All fields 0..1 except [rootHz]. */
data class Texture(
    val rootHz: Double,
    val brightness: Float,
    val reverbSize: Float,
    val reverbWet: Float,
    val detune: Float,
    val shimmer: Float,
    val noise: Float,
    val layerLevels: Map<Voice, Float>,
)

sealed interface ControlEvent {
    val atFrame: Long

    data class SetTexture(override val atFrame: Long, val texture: Texture) : ControlEvent
}

data class ComposerOutput(val notes: List<NoteEvent>, val controls: List<ControlEvent>)

/** The seam a MIDI/OSC output would implement; the synth is the one implementation now. */
interface NoteSink {
    fun schedule(output: ComposerOutput)
}

/**
 * Wall-clock view the composer needs; [dayOfWeek] is 1 = Monday .. 7 = Sunday. Built by the app from the platform
 * UTC offset.
 */
data class LocalMoment(val dayOfWeek: Int, val minuteOfDay: Int, val epochMillis: Long)
