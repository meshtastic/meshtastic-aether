package org.meshtastic.ambient.synth

import org.meshtastic.ambient.audio.BlockRenderer
import org.meshtastic.ambient.music.ComposerOutput
import org.meshtastic.ambient.music.ControlEvent
import org.meshtastic.ambient.music.NoteEvent
import org.meshtastic.ambient.music.NoteSink
import org.meshtastic.ambient.music.Texture
import org.meshtastic.ambient.music.Voice
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.exp

/**
 * The app's one synthesizer: [schedule] queues events by sample frame, [render] processes them sample-accurately.
 *
 * [schedule] may be called from one producer thread while another thread renders; the hand-off is a lock-free ring and
 * a full ring drops the event. The render path allocates nothing. Time is the count of frames rendered so far.
 */
@OptIn(ExperimentalAtomicApi::class)
class Synth(val sampleRate: Int) : NoteSink, BlockRenderer {
    private val voices = Array(VOICE_COUNT) { SynthVoice(sampleRate, 0x1234567 + it * 7919) }
    private val voiceKinds = Voice.entries.toTypedArray()

    private val noteRing = arrayOfNulls<NoteEvent>(NOTE_RING)
    private val noteHead = AtomicInt(0)
    private val noteTail = AtomicInt(0)
    private val controlRing = arrayOfNulls<ControlEvent.SetTexture>(CONTROL_RING)
    private val controlHead = AtomicInt(0)
    private val controlTail = AtomicInt(0)

    private val pendingNotes = arrayOfNulls<NoteEvent>(PENDING_NOTES)
    private var pendingNoteCount = 0
    private val pendingControls = arrayOfNulls<ControlEvent.SetTexture>(PENDING_CONTROLS)
    private var pendingControlCount = 0

    private var nowFrame = 0L

    private val dropped = AtomicInt(0)

    /** Notes dropped because a ring was full or no voice could be taken; counted from both the producer and render threads. */
    val droppedNotes: Int get() = dropped.load()

    // Smoothed texture and its targets.
    private var brightness = 0.5f
    private var reverbSize = 0.5f
    private var reverbWet = 0.35f
    private var detune = 0.2f
    private var shimmer = 0f
    private var noise = 0f
    private val layer = FloatArray(voiceKinds.size) { 1f }
    private var tBrightness = brightness
    private var tReverbSize = reverbSize
    private var tReverbWet = reverbWet
    private var tDetune = detune
    private var tShimmer = shimmer
    private var tNoise = noise
    private val tLayer = FloatArray(voiceKinds.size) { 1f }

    private val limiter = PeakLimiter(sampleRate)
    private val reverb = Reverb(sampleRate)
    private val ensemble = Ensemble(sampleRate)
    private val shifter = PitchShifter(sampleRate)
    private val noiseRng = Rng(0x5eed1234)
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

    /** Largest absolute master value before the limiter, over everything rendered. */
    internal val peakBeforeLimiter: Float get() = limiter.peakIn

    /** Non-finite master samples replaced by silence. */
    internal val nonFiniteHits: Int get() = limiter.nonFiniteHits

    internal val activeVoices: Int get() = voices.count { it.active }

    /** Notes accepted from the rings that are still waiting for their frame. */
    internal val pendingNoteTotal: Int get() = pendingNoteCount

    /** Pitch of every sounding voice that is not on its way out, in voice order. */
    internal fun soundingPitches(): DoubleArray = voices.filter { it.active && !it.isFading }.map { it.pitchHz }.toDoubleArray()

    internal fun reverbStats(): FloatArray = reverb.stateStats()

    init {
        noiseFilter.setFq((3000.0 / sampleRate).toFloat(), 0.7f)
        reverb.setInputGain(INPUT_GAIN)
        applyTextureNow()
    }

    override fun schedule(output: ComposerOutput) {
        for (n in output.notes) {
            val tail = noteTail.load()
            if (tail - noteHead.load() >= NOTE_RING) {
                countDrop()
                continue
            }
            noteRing[tail and (NOTE_RING - 1)] = n
            noteTail.store(tail + 1)
        }
        for (c in output.controls) {
            if (c !is ControlEvent.SetTexture) continue
            val tail = controlTail.load()
            if (tail - controlHead.load() >= CONTROL_RING) continue
            controlRing[tail and (CONTROL_RING - 1)] = c
            controlTail.store(tail + 1)
        }
    }

    override fun render(out: FloatArray, frames: Int) {
        drainRings()
        var done = 0
        while (done < frames) {
            while (pendingNoteCount > 0 && pendingNotes[0]!!.atFrame <= nowFrame) {
                trigger(popNote())
            }
            while (pendingControlCount > 0 && pendingControls[0]!!.atFrame <= nowFrame) {
                setTargets(popControl().texture)
            }
            var chunk = minOf(frames - done, MAX_CHUNK)
            if (pendingNoteCount > 0) chunk = minOf(chunk.toLong(), pendingNotes[0]!!.atFrame - nowFrame).toInt()
            if (pendingControlCount > 0) chunk = minOf(chunk.toLong(), pendingControls[0]!!.atFrame - nowFrame).toInt()
            renderChunk(chunk, out, done)
            nowFrame += chunk
            done += chunk
        }
    }

    private fun drainRings() {
        var head = noteHead.load()
        val tail = noteTail.load()
        while (head != tail) {
            val n = noteRing[head and (NOTE_RING - 1)]!!
            noteRing[head and (NOTE_RING - 1)] = null
            head++
            insertNote(n)
        }
        noteHead.store(head)
        var ch = controlHead.load()
        val ct = controlTail.load()
        while (ch != ct) {
            val c = controlRing[ch and (CONTROL_RING - 1)]!!
            controlRing[ch and (CONTROL_RING - 1)] = null
            ch++
            insertControl(c)
        }
        controlHead.store(ch)
    }

    private fun insertNote(n: NoteEvent) {
        if (pendingNoteCount == PENDING_NOTES) {
            countDrop()
            return
        }
        var i = pendingNoteCount
        while (i > 0 && pendingNotes[i - 1]!!.atFrame > n.atFrame) {
            pendingNotes[i] = pendingNotes[i - 1]
            i--
        }
        pendingNotes[i] = n
        pendingNoteCount++
    }

    private fun insertControl(c: ControlEvent.SetTexture) {
        if (pendingControlCount == PENDING_CONTROLS) return
        var i = pendingControlCount
        while (i > 0 && pendingControls[i - 1]!!.atFrame > c.atFrame) {
            pendingControls[i] = pendingControls[i - 1]
            i--
        }
        pendingControls[i] = c
        pendingControlCount++
    }

    private fun popNote(): NoteEvent {
        val n = pendingNotes[0]!!
        for (i in 1 until pendingNoteCount) pendingNotes[i - 1] = pendingNotes[i]
        pendingNoteCount--
        pendingNotes[pendingNoteCount] = null
        return n
    }

    private fun popControl(): ControlEvent.SetTexture {
        val c = pendingControls[0]!!
        for (i in 1 until pendingControlCount) pendingControls[i - 1] = pendingControls[i]
        pendingControlCount--
        pendingControls[pendingControlCount] = null
        return c
    }

    private fun setTargets(t: Texture) {
        tBrightness = unit(t.brightness)
        tReverbSize = unit(t.reverbSize)
        tReverbWet = unit(t.reverbWet)
        tDetune = unit(t.detune)
        tShimmer = unit(t.shimmer)
        tNoise = unit(t.noise)
        for (i in voiceKinds.indices) tLayer[i] = unit(t.layerLevels[voiceKinds[i]] ?: 1f)
    }

    private fun unit(x: Float): Float = if (x != x) 0f else clamp(x, 0f, 1f)

    private fun applyTextureNow() {
        brightness = tBrightness
        reverbSize = tReverbSize
        reverbWet = tReverbWet
        detune = tDetune
        shimmer = tShimmer
        noise = tNoise
        for (i in layer.indices) layer[i] = tLayer[i]
    }

    private fun countDrop() {
        while (true) {
            val v = dropped.load()
            if (dropped.compareAndSet(v, v + 1)) return
        }
    }

    private fun trigger(note: NoteEvent) {
        var pick = -1
        for (i in voices.indices) {
            if (!voices[i].active) {
                pick = i
                break
            }
        }
        if (pick < 0) pick = pickVictim()
        if (pick < 0 || !voices[pick].begin(note)) countDrop()
    }

    /**
     * Picks the voice to steal, or -1. Never a voice that is attacking or already fading. In order: the oldest releasing
     * ornament, the quietest other ornament, then (only if no ornament qualifies) the same two choices among the Drone
     * and Pad bed.
     */
    private fun pickVictim(): Int {
        var best = -1
        var bestTier = Int.MAX_VALUE
        for (i in voices.indices) {
            val v = voices[i]
            if (v.isFading || v.isAttacking) continue
            val tier = (if (v.isBed) 2 else 0) + (if (v.isReleasing) 0 else 1)
            if (tier > bestTier) continue
            if (tier < bestTier || (if (v.isReleasing) v.age > voices[best].age else v.loudness < voices[best].loudness)) {
                best = i
                bestTier = tier
            }
        }
        return best
    }

    private fun renderChunk(n: Int, out: FloatArray, offset: Int) {
        val smooth = 1f - exp(-n.toFloat() / (sampleRate * SMOOTH_SECONDS))
        brightness += (tBrightness - brightness) * smooth
        reverbSize += (tReverbSize - reverbSize) * smooth
        reverbWet += (tReverbWet - reverbWet) * smooth
        detune += (tDetune - detune) * smooth
        shimmer += (tShimmer - shimmer) * smooth
        noise += (tNoise - noise) * smooth
        for (i in layer.indices) layer[i] += (tLayer[i] - layer[i]) * smooth

        dryL.fill(0f, 0, n)
        dryR.fill(0f, 0, n)
        padL.fill(0f, 0, n)
        padR.fill(0f, 0, n)
        sendBus.fill(0f, 0, n)

        for (v in voices) {
            if (!v.active) continue
            v.render(n, mono, brightness, detune)
            val meshBed = v.fromNode && (v.kind == Voice.Drone || v.kind == Voice.Shimmer)
            val g = if (meshBed) 1f else layer[v.kind.ordinal]
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
            } else {
                for (i in 0 until n) {
                    dryL[i] += mono[i] * gl
                    dryR[i] += mono[i] * gr
                    sendBus[i] += mono[i] * sg
                }
            }
        }

        if (noise > 1.0e-4f) {
            val level = noise * NOISE_LEVEL
            for (i in 0 until n) {
                val s = noiseFilter.lowPass(noiseRng.nextSigned()) * level
                dryL[i] += s
                dryR[i] += s
                sendBus[i] += s * 0.5f
            }
        }

        ensemble.amount = detune * 0.6f
        ensemble.depth = 0.25f + 0.6f * detune
        ensemble.process(padL, padR, n)

        reverb.setRt60(rt60ForSize(reverbSize))
        reverb.process(sendBus, wetL, wetR, n)

        val wet = reverbWet * WET_GAIN
        val sh = shimmer * wet
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
        private const val SMOOTH_SECONDS = 2.0f
        private const val INPUT_GAIN = 0.3f
        private const val WET_GAIN = 1.0f
        /** 0.25 plus about 8 dB of makeup, applied ahead of the limiter whose ceiling is unchanged. */
        private const val MASTER_GAIN = 0.63f
        private const val NOISE_LEVEL = 0.08f
    }
}

private const val MIN_RT60 = 10.0
private const val MAX_RT60 = 26.0

/** Reverb tail (seconds to fall 60 dB) for a `Texture.reverbSize` of 0..1. */
internal fun rt60ForSize(size: Float): Double = MIN_RT60 + (MAX_RT60 - MIN_RT60) * size.toDouble()
