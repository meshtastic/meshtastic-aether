package org.meshtastic.ambient.synth

import org.meshtastic.ambient.music.ComposerOutput
import org.meshtastic.ambient.music.ControlEvent
import org.meshtastic.ambient.music.NoteEvent
import org.meshtastic.ambient.music.Texture
import org.meshtastic.ambient.music.Voice
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal const val TEST_RATE = 32000

internal fun note(
    voice: Voice,
    frame: Long = 0,
    hz: Double = 220.0,
    velocity: Float = 0.8f,
    seconds: Double = 2.0,
    pan: Float = 0f,
    send: Float = 0f,
    brightness: Float = 0.5f,
    rate: Int = TEST_RATE,
) = NoteEvent(frame, voice, hz, velocity, pan, send, (seconds * rate).toLong(), brightness)

internal fun texture(
    brightness: Float = 0.5f,
    size: Float = 0.5f,
    wet: Float = 0f,
    detune: Float = 0.2f,
    shimmer: Float = 0f,
    noise: Float = 0f,
    layers: Map<Voice, Float> = emptyMap(),
) = Texture(110.0, brightness, size, wet, detune, shimmer, noise, layers)

internal fun controls(atFrame: Long, t: Texture) = ComposerOutput(emptyList(), listOf(ControlEvent.SetTexture(atFrame, t)))

internal fun notes(vararg n: NoteEvent) = ComposerOutput(n.toList(), emptyList())

/** Renders [frames] stereo frames in blocks of [block]; returns interleaved samples. */
internal fun Synth.renderAll(frames: Int, block: Int = 512): FloatArray {
    val out = FloatArray(frames * 2)
    val buf = FloatArray(block * 2)
    var done = 0
    while (done < frames) {
        val n = minOf(block, frames - done)
        render(buf, n)
        buf.copyInto(out, done * 2, 0, n * 2)
        done += n
    }
    return out
}

internal fun FloatArray.peak(fromFrame: Int = 0, toFrame: Int = size / 2): Float {
    var p = 0f
    for (i in fromFrame * 2 until toFrame * 2) {
        val a = abs(this[i])
        if (a > p) p = a
    }
    return p
}

internal fun FloatArray.rms(fromFrame: Int = 0, toFrame: Int = size / 2, channel: Int = -1): Double {
    var sum = 0.0
    var count = 0
    for (f in fromFrame until toFrame) {
        for (c in 0..1) {
            if (channel >= 0 && c != channel) continue
            val v = this[f * 2 + c].toDouble()
            sum += v * v
            count++
        }
    }
    return sqrt(sum / count)
}

/** Magnitude of the [hz] component of [x] by the Goertzel recurrence. */
internal fun goertzel(x: FloatArray, from: Int, to: Int, hz: Double, rate: Int): Double {
    val w = 2.0 * PI * hz / rate
    val coeff = 2.0 * cos(w)
    var s1 = 0.0
    var s2 = 0.0
    for (i in from until to) {
        val s = x[i] + coeff * s1 - s2
        s2 = s1
        s1 = s
    }
    val re = s1 - s2 * cos(w)
    val im = s2 * sin(w)
    return sqrt(re * re + im * im) / (to - from)
}

internal fun mono(stereo: FloatArray): FloatArray = FloatArray(stereo.size / 2) { stereo[it * 2] }
