package org.meshtastic.ambient.synth

import org.meshtastic.ambient.music.NoteEvent
import org.meshtastic.ambient.music.Voice
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One polyphony slot. Renders a mono block per call; [Synth] pans, sends and mixes it. */
internal class SynthVoice(private val sampleRate: Int, seed: Int) {
    private val rng = Rng(seed)
    private val env = Adsr(sampleRate)
    private val filter = Svf()
    private val excitationFilter = Svf()
    private val fm1 = FmPair()
    private val fm2 = FmPair()
    private val sine1 = SineOsc()
    private val sine2 = SineOsc()
    private val sine3 = SineOsc()
    private val saw1 = PolyBlepOsc()
    private val saw2 = PolyBlepOsc()
    private val saw3 = PolyBlepOsc()
    private val string = KarplusString(sampleRate, rng)
    private val modal = ModalResonator(sampleRate)

    var active = false
        private set
    var kind = Voice.Bell
        private set
    var panLeft = 1f
        private set
    var panRight = 1f
        private set
    var send = 0f
        private set

    /** Loudness estimate used to rank sustaining ornaments for stealing. */
    val loudness: Float get() = env.level * amp

    val isAttacking: Boolean get() = env.isAttacking
    val isReleasing: Boolean get() = env.isReleasing

    /** True while the voice fades out ahead of a stolen note; it cannot be stolen again. */
    val isFading: Boolean get() = fading

    /** Drone and Pad are the sustained bed; stealing leaves them until nothing else is left. */
    val isBed: Boolean get() = active && (kind == Voice.Drone || kind == Voice.Pad)

    /** Frames since the current note started. */
    var age = 0L
        private set

    /** Fundamental the voice was asked for; 0 while idle. */
    val pitchHz: Double get() = if (active) freq else 0.0

    private var pending: NoteEvent? = null
    private var fading = false
    private var fadeGain = 1f
    private val fadeStep = (1.0 / (sampleRate * FADE_SECONDS)).toFloat()
    private var framesLeft = 0L
    private var freq = 440.0
    private var amp = 0f
    private var bright = 0.5f
    private var burstLeft = 0
    private var burstLength = 1
    private var bellRatio = 3.5

    /**
     * Starts [note] on this voice, fading the current note out over [FADE_SECONDS] first if it is still sounding.
     * Returns false, leaving the voice untouched, when it is already fading toward another note.
     */
    fun begin(note: NoteEvent): Boolean {
        if (fading) return false
        if (active) {
            pending = note
            fading = true
            fadeGain = 1f
        } else {
            start(note)
        }
        return true
    }

    private fun start(note: NoteEvent) {
        val sr = sampleRate.toDouble()
        kind = note.voice
        freq = clamp(note.pitchHz, 16.0, sr * 0.45)
        amp = clamp(note.velocity, 0f, 1f)
        bright = clamp(note.brightness, 0f, 1f)
        framesLeft = max(note.durationFrames, 1L)
        val durSec = (framesLeft / sr).toFloat()
        val pan = clamp(note.pan, -1f, 1f)
        val angle = (pan + 1f) * (PI.toFloat() / 4f)
        panLeft = cos(angle)
        panRight = sin(angle)
        send = clamp(note.reverbSend, 0f, 1f)
        filter.reset()
        when (kind) {
            Voice.Bell -> {
                env.trigger(0.002f, clamp(durSec * 0.9f, 0.3f, 40f), 0f, 0.4f)
                fm1.reset()
                fm2.reset()
                bellRatio = 3.5
            }
            Voice.Mallet -> {
                env.trigger(0.001f, 0.1f, 1f, 0.25f)
                modal.configure(freq, 0.3f, bright, ring60For(durSec), 0.18f, 14)
                excitationFilter.reset()
                excitationFilter.setFq(((600.0 + 7000.0 * bright) / sr).toFloat(), 0.7f)
                burstLength = max(2, (sr * 0.004).toInt())
                burstLeft = burstLength
            }
            Voice.Pluck -> {
                env.trigger(0.001f, 0.1f, 1f, 0.3f)
                val damping = clamp(0.25f + 0.1f * durSec, 0.25f, 0.9f)
                string.configure(freq, -0.15f, bright, damping)
                excitationFilter.reset()
                excitationFilter.setFq(((800.0 + 8000.0 * bright) / sr).toFloat(), 0.7f)
                burstLength = max(2, (sr / freq).toInt())
                burstLeft = burstLength
            }
            Voice.Breath -> {
                env.trigger(max(durSec * 0.35f, 0.05f), 0.1f, 1f, max(durSec * 0.5f, 0.1f))
                filter.setFq(((500.0 + 5500.0 * bright) / sr).toFloat(), 2.5f)
            }
            Voice.Pad -> {
                env.trigger(min(durSec * 0.3f, 6f), 0.1f, 1f, min(max(durSec * 0.4f, 0.2f), 8f))
                saw1.reset(0.0)
                saw2.reset(0.33)
                saw3.reset(0.66)
            }
            Voice.Drone -> {
                env.trigger(min(durSec * 0.35f, 8f), 0.1f, 1f, min(max(durSec * 0.4f, 0.2f), 10f))
                sine1.reset(0.0)
                sine2.reset(0.25)
                saw1.reset(0.0)
            }
            Voice.Shimmer -> {
                env.trigger(min(durSec * 0.4f, 6f), 0.1f, 1f, min(max(durSec * 0.5f, 0.2f), 10f))
                sine1.reset(0.0)
                sine2.reset(0.3)
                sine3.reset(0.6)
            }
        }
        active = true
        age = 0L
    }

    /** Seconds for the fundamental to fall 60 dB, from the note's length (longer notes ring longer). */
    private fun ring60For(durSec: Float): Float = clamp(durSec, 0.15f, 20f)

    /** Writes [n] mono samples to [out]; [brightness] and [detune] are the smoothed texture values. */
    fun render(n: Int, out: FloatArray, brightness: Float, detune: Float) {
        if (!active) {
            out.fill(0f, 0, n)
            return
        }
        val sr = sampleRate.toDouble()
        val f = freq / sr
        val tex = clamp(0.35f * brightness + 0.65f * bright, 0f, 1f)
        val detuneCents = 2.0 + 14.0 * detune
        val ratioUp = semitonesToRatio(detuneCents / 100.0)
        val ratioDown = 1.0 / ratioUp

        if (kind == Voice.Pad) {
            filter.setFq(clamp(((350.0 + 6500.0 * tex * tex) + freq * 2.0) / sr, 0.001, 0.45).toFloat(), 0.8f)
        } else if (kind == Voice.Drone) {
            filter.setFq(clamp((120.0 + 1800.0 * tex + freq) / sr, 0.001, 0.45).toFloat(), 0.7f)
        }
        var fadeDone = false
        val fmIndex = (0.4f + 4.5f * tex) * (0.3f + 0.7f * amp)

        for (i in 0 until n) {
            if (framesLeft > 0L) {
                framesLeft--
                if (framesLeft == 0L) env.release()
            }
            val e = env.next()
            var s: Float
            when (kind) {
                Voice.Bell -> {
                    val idx = fmIndex * e
                    s = fm1.next(f, bellRatio, idx) * 0.5f + fm2.next(f * 2.76, 1.0, idx * 0.5f) * 0.18f * e
                    s *= 1.0f * e
                }
                Voice.Mallet -> {
                    var ex = 0f
                    if (burstLeft > 0) {
                        ex = rng.nextSigned() * (burstLeft.toFloat() / burstLength)
                        burstLeft--
                    }
                    s = modal.process(excitationFilter.lowPass(ex) * 3.5f) * e
                }
                Voice.Pluck -> {
                    var ex = 0f
                    if (burstLeft > 0) {
                        ex = rng.nextSigned()
                        burstLeft--
                    }
                    s = string.process(excitationFilter.lowPass(ex) * 3.0f) * 0.9f * e
                }
                Voice.Breath -> {
                    s = filter.bandPass(rng.nextSigned()) * e * 0.4f
                }
                Voice.Pad -> {
                    val raw = (saw1.saw(f) + saw2.saw(f * ratioUp) + saw3.saw(f * ratioDown)) * 0.33f
                    s = filter.lowPass(raw) * e * 0.5f
                }
                Voice.Drone -> {
                    val raw = sine1.next(f) * 0.6f + sine2.next(f * ratioUp) * 0.4f + saw1.saw(f) * 0.12f
                    s = filter.lowPass(raw) * e * 0.6f
                }
                Voice.Shimmer -> {
                    val raw = sine1.next(f * 2.0) * 0.5f + sine2.next(f * 3.0 * ratioUp) * 0.3f +
                        sine3.next(f * 4.0 * ratioDown) * 0.2f
                    s = raw * e * 0.35f
                }
            }
            if (fading) {
                fadeGain -= fadeStep
                if (fadeGain <= 0f) {
                    fadeGain = 0f
                    fadeDone = true
                }
                s *= fadeGain
            }
            out[i] = s * amp
        }
        age += n
        if (fading) {
            if (fadeDone) {
                fading = false
                active = false
                val p = pending
                pending = null
                if (p != null) start(p)
            }
        } else if (env.isIdle) {
            active = false
        }
    }

    private companion object {
        const val FADE_SECONDS = 0.005
    }
}
