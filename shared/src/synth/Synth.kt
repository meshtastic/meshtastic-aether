package org.meshtastic.aether.synth

import org.meshtastic.aether.audio.BlockRenderer
import org.meshtastic.aether.music.ComposerOutput
import org.meshtastic.aether.music.ControlEvent
import org.meshtastic.aether.music.NoteEvent
import org.meshtastic.aether.music.NoteSink
import org.meshtastic.aether.music.Voice
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.abs
import kotlin.math.exp

/**
 * The app's one synthesizer: [schedule] queues events by sample frame, [render] processes them sample-accurately.
 *
 * [schedule] may be called from one producer thread while another thread renders; the hand-off is a lock-free ring and
 * a full ring drops the event. The render path allocates nothing. Time is the count of frames rendered so far.
 */
@OptIn(ExperimentalAtomicApi::class)
class Synth(val sampleRate: Int) : NoteSink, BlockRenderer {
    private val voices = Array(VOICE_COUNT) { SynthVoice(sampleRate, VOICE_SEED + it * VOICE_SEED_STRIDE) }

    private val notes = NoteQueue(NOTE_RING, PENDING_NOTES)
    private val controls = TextureQueue(CONTROL_RING, PENDING_CONTROLS)

    private var nowFrame = 0L

    private val dropped = AtomicInt(0)

    /**
     * Notes dropped because a ring was full or no voice could be taken; counted from both the producer and render
     * threads.
     */
    val droppedNotes: Int get() = dropped.load()

    private val texture = SmoothedTexture(sampleRate)

    private val limiter = PeakLimiter(sampleRate)
    private val reverb = Reverb(sampleRate)
    private val ensemble = Ensemble(sampleRate)
    private val shifter = PitchShifter(sampleRate)
    private val noiseRng = Rng(NOISE_SEED)
    private val noiseFilter = Svf()

    private val mono = FloatArray(MAX_CHUNK)
    private val dryL = FloatArray(MAX_CHUNK)
    private val dryR = FloatArray(MAX_CHUNK)
    private val padL = FloatArray(MAX_CHUNK)
    private val padR = FloatArray(MAX_CHUNK)
    private val sendBus = FloatArray(MAX_CHUNK)
    private val wetL = FloatArray(MAX_CHUNK)
    private val wetR = FloatArray(MAX_CHUNK)
    private val shifted = FloatArray(2)
    private val limited = FloatArray(2)
    private val earL = FloatArray(MAX_CHUNK)
    private val earR = FloatArray(MAX_CHUNK)

    /**
     * Headphones place dry voices to the side of the head instead of panning them; set from any thread, eased in over
     * the next chunks.
     */
    @kotlin.concurrent.Volatile
    var headphones = false
    private var binauralMix = 0f

    /** Non-finite master samples replaced by silence. */
    internal val nonFiniteHits: Int get() = limiter.nonFiniteHits

    internal val activeVoices: Int get() = voices.count { it.active }

    /** Notes accepted from the rings that are still waiting for their frame. */
    internal val pendingNoteTotal: Int get() = notes.size

    /** Pitch of every sounding voice that is not on its way out, in voice order. */
    internal fun soundingPitches(): DoubleArray =
        voices.filter { it.active && !it.isFading }.map { it.pitchHz }.toDoubleArray()

    init {
        noiseFilter.setFq((NOISE_CUTOFF_HZ / sampleRate).toFloat(), NOISE_Q)
        reverb.inputGain = INPUT_GAIN
        texture.applyNow()
    }

    override fun schedule(output: ComposerOutput) {
        for (n in output.notes) {
            if (!notes.offer(n)) dropped.addAndFetch(1)
        }
        for (c in output.controls) {
            if (c is ControlEvent.SetTexture) controls.offer(c)
        }
    }

    override fun render(out: FloatArray, frames: Int) {
        dropped.addAndFetch(notes.drain())
        controls.drain()
        var done = 0
        while (done < frames) {
            while (notes.isDue(nowFrame)) trigger(notes.pop())
            while (controls.isDue(nowFrame)) texture.setTargets(controls.pop().texture)
            var chunk = minOf(frames - done, MAX_CHUNK)
            chunk = notes.clampChunk(chunk, nowFrame)
            chunk = controls.clampChunk(chunk, nowFrame)
            renderChunk(chunk, out, done)
            nowFrame += chunk
            done += chunk
        }
    }

    private fun trigger(note: NoteEvent) {
        for (v in voices) {
            if (continues(v, note) && v.begin(note, carryOn = true)) return
        }
        var pick = -1
        for (i in voices.indices) {
            if (!voices[i].active) {
                pick = i
                break
            }
        }
        if (pick < 0) pick = pickVictim()
        if (pick < 0 || !voices[pick].begin(note)) dropped.addAndFetch(1)
    }

    /**
     * Picks the voice to steal, or -1. Never a voice that is attacking or already fading. In order: the oldest
     * releasing ornament, the quietest other ornament, then (only if no ornament qualifies) the same two choices among
     * the Drone and Pad bed.
     */
    private fun pickVictim(): Int {
        var best = -1
        var bestTier = Int.MAX_VALUE
        for (i in voices.indices) {
            val v = voices[i]
            if (v.isFading || v.isAttacking) continue
            val tier = (if (v.isBed) 2 else 0) + (if (v.isReleasing) 0 else 1)
            if (tier < bestTier || (tier == bestTier && v.isBetterVictimThan(voices[best]))) {
                best = i
                bestTier = tier
            }
        }
        return best
    }

    private fun renderChunk(n: Int, out: FloatArray, offset: Int) {
        texture.ease(n)
        easeOutputMode(n)

        dryL.fill(0f, 0, n)
        dryR.fill(0f, 0, n)
        padL.fill(0f, 0, n)
        padR.fill(0f, 0, n)
        sendBus.fill(0f, 0, n)

        for (v in voices) {
            if (v.active) mixVoice(v, n)
        }
        if (texture.noise > NOISE_GATE) addNoise(n)
        mixMaster(n, out, offset)
    }

    /** Glides [binauralMix] toward the current output mode, snapping to the end once it is inaudibly close. */
    private fun easeOutputMode(n: Int) {
        val target = if (headphones) 1f else 0f
        binauralMix += (target - binauralMix) * (1f - exp(-n.toFloat() / (sampleRate * OUTPUT_MODE_SECONDS)))
        if (binauralMix < MIX_SNAP) binauralMix = 0f else if (binauralMix > 1f - MIX_SNAP) binauralMix = 1f
    }

    /** Renders [v] and adds it to the pad bus, or to the dry bus panned or placed for headphones, and to the send. */
    private fun mixVoice(v: SynthVoice, n: Int) {
        v.render(n, mono, texture.brightness, texture.detune)
        val meshBed = v.fromNode && (v.kind == Voice.Drone || v.kind == Voice.Shimmer)
        val g = if (meshBed) 1f else texture.layer(v.kind)
        val pad = v.kind == Voice.Pad || v.kind == Voice.Drone || v.kind == Voice.Shimmer || v.kind == Voice.Choir
        val gl = v.panLeft * g
        val gr = v.panRight * g
        val sg = v.send * g
        if (pad) {
            for (i in 0 until n) {
                padL[i] += mono[i] * gl
                padR[i] += mono[i] * gr
                sendBus[i] += mono[i] * sg
            }
        } else if (binauralMix > 0f) {
            v.binaural.process(mono, n, v.lateral, earL, earR)
            val m = binauralMix
            for (i in 0 until n) {
                dryL[i] += mono[i] * gl * (1f - m) + earL[i] * g * m
                dryR[i] += mono[i] * gr * (1f - m) + earR[i] * g * m
                sendBus[i] += mono[i] * sg
            }
        } else {
            for (i in 0 until n) {
                dryL[i] += mono[i] * gl
                dryR[i] += mono[i] * gr
                sendBus[i] += mono[i] * sg
            }
        }
    }

    private fun addNoise(n: Int) {
        val level = texture.noise * NOISE_LEVEL
        for (i in 0 until n) {
            val s = noiseFilter.lowPass(noiseRng.nextSigned()) * level
            dryL[i] += s
            dryR[i] += s
            sendBus[i] += s * NOISE_SEND
        }
    }

    /** Chorus on the pads, reverb and shimmer on the send, then the sum through the limiter into [out]. */
    private fun mixMaster(n: Int, out: FloatArray, offset: Int) {
        val detune = texture.detune
        ensemble.amount = detune * ENSEMBLE_AMOUNT
        ensemble.depth = ENSEMBLE_DEPTH_FLOOR + ENSEMBLE_DEPTH_RANGE * detune
        ensemble.process(padL, padR, n)

        reverb.setRt60(rt60ForSize(texture.reverbSize))
        reverb.process(sendBus, wetL, wetR, n)

        val wet = texture.reverbWet * WET_GAIN
        val sh = texture.shimmer * wet
        for (i in 0 until n) {
            shifter.process(wetL[i], wetR[i], shifted)
            val l = dryL[i] + padL[i] + wetL[i] * wet + shifted[0] * sh
            val r = dryR[i] + padR[i] + wetR[i] * wet + shifted[1] * sh
            limiter.process(l * MASTER_GAIN, r * MASTER_GAIN, limited)
            val o = (offset + i) * 2
            out[o] = limited[0]
            out[o + 1] = limited[1]
        }
    }

    companion object {
        const val VOICE_COUNT = 24
        private const val MAX_CHUNK = 64

        private const val NOTE_RING = 4096
        private const val CONTROL_RING = 256
        private const val PENDING_NOTES = 2048
        private const val PENDING_CONTROLS = 128

        /** Each voice's noise seed; the stride is prime so no two voices share a sequence. */
        private const val VOICE_SEED = 0x1234567
        private const val VOICE_SEED_STRIDE = 7919
        private const val NOISE_SEED = 0x5eed1234

        /** Time constant of the crossfade between stereo and headphones, so switching never clicks. */
        private const val OUTPUT_MODE_SECONDS = 0.3f

        /** Distance from either output mode at which the crossfade snaps to it. */
        private const val MIX_SNAP = 1.0e-4f
        private const val INPUT_GAIN = 0.3f
        private const val WET_GAIN = 1.0f

        /** 0.25 plus about 8 dB of makeup, applied ahead of the limiter whose ceiling is unchanged. */
        private const val MASTER_GAIN = 0.63f

        // The noise texture: a low-passed hiss, skipped while its level is inaudible, half as loud into the reverb.
        private const val NOISE_LEVEL = 0.08f
        private const val NOISE_CUTOFF_HZ = 3000.0
        private const val NOISE_Q = 0.7f
        private const val NOISE_GATE = 1.0e-4f
        private const val NOISE_SEND = 0.5f

        // The pad chorus follows the detune: wetter and deeper as it rises.
        private const val ENSEMBLE_AMOUNT = 0.6f
        private const val ENSEMBLE_DEPTH_FLOOR = 0.25f
        private const val ENSEMBLE_DEPTH_RANGE = 0.6f
    }
}

/** The voice to steal between two in the same tier: the older if releasing, otherwise the quieter. */
private fun SynthVoice.isBetterVictimThan(other: SynthVoice): Boolean =
    if (isReleasing) age > other.age else loudness < other.loudness

private const val MIN_RT60 = 10.0
private const val MAX_RT60 = 26.0

/** Reverb tail (seconds to fall 60 dB) for a `Texture.reverbSize` of 0..1. */
internal fun rt60ForSize(size: Float): Double = MIN_RT60 + (MAX_RT60 - MIN_RT60) * size.toDouble()

/** Two bed pitches this close (as a share of the pitch) are the same tone struck again. */
private const val SAME_PITCH = 1e-9

/**
 * Whether [note] is [v]'s sounding bed tone struck again: the same Pad, Drone or Shimmer, not a node's, at the same
 * pitch, pan and velocity. The voice then carries on instead of another starting, so the bed's tick never dips; a
 * note that differs in level starts its own voice rather than step this one.
 */
private fun continues(v: SynthVoice, note: NoteEvent): Boolean =
    v.active && !v.isFading && !v.fromNode && note.node == null && note.voice == v.kind &&
        (v.kind == Voice.Pad || v.kind == Voice.Drone || v.kind == Voice.Shimmer) &&
        abs(note.pitchHz - v.pitchHz) <= v.pitchHz * SAME_PITCH && note.pan.coerceIn(-1f, 1f) == v.pan &&
        note.velocity.coerceIn(0f, 1f) == v.velocity
