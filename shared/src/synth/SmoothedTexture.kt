package org.meshtastic.aether.synth

import org.meshtastic.aether.music.Texture
import org.meshtastic.aether.music.Voice
import kotlin.math.exp

/** Time constant of the glide toward each new [Texture]. */
private const val SMOOTH_SECONDS = 2.0f

// The texture the synth starts from, before the composer sends one.
private const val START_BRIGHTNESS = 0.5f
private const val START_REVERB_SIZE = 0.5f
private const val START_REVERB_WET = 0.35f
private const val START_DETUNE = 0.2f

/** The synth's global sound character, gliding toward the latest [Texture] so a change never steps. */
internal class SmoothedTexture(private val sampleRate: Int) {
    private val kinds = Voice.entries.toTypedArray()

    var brightness = START_BRIGHTNESS
        private set
    var reverbSize = START_REVERB_SIZE
        private set
    var reverbWet = START_REVERB_WET
        private set
    var detune = START_DETUNE
        private set
    var shimmer = 0f
        private set
    var noise = 0f
        private set
    private val layer = FloatArray(kinds.size) { 1f }

    private var tBrightness = brightness
    private var tReverbSize = reverbSize
    private var tReverbWet = reverbWet
    private var tDetune = detune
    private var tShimmer = shimmer
    private var tNoise = noise
    private val tLayer = FloatArray(kinds.size) { 1f }

    /** Level of the [kind] layer, 0..1. */
    fun layer(kind: Voice): Float = layer[kind.ordinal]

    /** Aims the glide at [t], with every field clamped to 0..1 and NaN read as 0. */
    fun setTargets(t: Texture) {
        tBrightness = unit(t.brightness)
        tReverbSize = unit(t.reverbSize)
        tReverbWet = unit(t.reverbWet)
        tDetune = unit(t.detune)
        tShimmer = unit(t.shimmer)
        tNoise = unit(t.noise)
        for (i in kinds.indices) tLayer[i] = unit(t.layerLevels[kinds[i]] ?: 1f)
    }

    /** Jumps straight to the targets. */
    fun applyNow() {
        brightness = tBrightness
        reverbSize = tReverbSize
        reverbWet = tReverbWet
        detune = tDetune
        shimmer = tShimmer
        noise = tNoise
        for (i in layer.indices) layer[i] = tLayer[i]
    }

    /** Glides [frames] further toward the targets. */
    fun ease(frames: Int) {
        val smooth = 1f - exp(-frames.toFloat() / (sampleRate * SMOOTH_SECONDS))
        brightness += (tBrightness - brightness) * smooth
        reverbSize += (tReverbSize - reverbSize) * smooth
        reverbWet += (tReverbWet - reverbWet) * smooth
        detune += (tDetune - detune) * smooth
        shimmer += (tShimmer - shimmer) * smooth
        noise += (tNoise - noise) * smooth
        for (i in layer.indices) layer[i] += (tLayer[i] - layer[i]) * smooth
    }

    private fun unit(x: Float): Float = if (x != x) 0f else clamp(x, 0f, 1f)
}
