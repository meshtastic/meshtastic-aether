package org.meshtastic.aether.synth

import org.meshtastic.aether.music.NoteEvent
import org.meshtastic.aether.music.Ornaments
import org.meshtastic.aether.music.Voice
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private const val FADE_SECONDS = 0.005

// Pitch range: the lowest note asked for, and the highest as a share of the sample rate, under Nyquist.
private const val LOWEST_HZ = 16.0
private const val HIGHEST_SHARE_OF_RATE = 0.45

/** Constant-power pan: a pan of -1..1, plus one, times this is the angle 0..pi/2. */
private const val PAN_TO_ANGLE = PI.toFloat() / 4f

// A voice's timbre follows the texture's brightness and, more, its own note's.
private const val TEXTURE_BRIGHTNESS_SHARE = 0.35f
private const val NOTE_BRIGHTNESS_SHARE = 0.65f

// Detune between a voice's oscillators: 2 cents plus up to 14 more with the texture's detune.
private const val DETUNE_RANGE_CENTS = 14.0
private const val CENTS_PER_SEMITONE = 100.0

// Bell: two FM pairs, the carrier pair and an inharmonic partial pair.
private const val BELL_RATIO = 3.5
private const val BELL_PARTIAL_RATIO = 2.76
private const val BELL_CARRIER_GAIN = 0.5f
private const val BELL_PARTIAL_INDEX = 0.5f
private const val BELL_PARTIAL_GAIN = 0.18f

// FM index, from the timbre and the velocity.
private const val FM_INDEX_FLOOR = 0.4f
private const val FM_INDEX_RANGE = 4.5f
private const val FM_VELOCITY_FLOOR = 0.3f
private const val FM_VELOCITY_RANGE = 0.7f

// Struck and plucked: the excitation's level into the resonator, and the string's output level.
private const val STRIKE_GAIN = 3.5f
private const val PLUCK_GAIN = 3.0f
private const val STRING_GAIN = 0.9f

private val MALLET_BODY = ModalBody(structure = 0.3f, position = 0.18f, modes = 14)
private val MARIMBA_BODY = ModalBody(structure = 0.45f, position = 0.14f, modes = 10)
private val VIBES_BODY = ModalBody(structure = 0.2f, position = 0.25f, modes = 6)
private val KALIMBA_BODY = ModalBody(structure = 0.85f, position = 0.3f, modes = 4)
private val GONG_BODY = ModalBody(structure = 0.7f, position = 0.35f, modes = 16)

// How long a modal body rings, as a share of the note.
private const val MARIMBA_RING_PER_NOTE = 0.5f
private const val VIBES_RING_PER_NOTE = 1.6f
private const val KALIMBA_RING_PER_NOTE = 0.8f
private const val GONG_RING_PER_NOTE = 3f
private const val KALIMBA_GAIN = 1.4f
private const val GONG_GAIN = 0.5f
private const val VIBES_TREMOLO_HZ = 5.0
private const val VIBES_TREMOLO_DEPTH = 0.25f

// String damping, from the note's length: a floor, the rise per second, and a ceiling.
private const val PLUCK_DAMPING_FLOOR = 0.25f
private const val PLUCK_DAMPING_PER_SECOND = 0.1f
private const val PLUCK_DAMPING_CEILING = 0.9f
private const val HARP_DAMPING_FLOOR = 0.5f
private const val HARP_DAMPING_PER_SECOND = 0.08f
private const val HARP_DAMPING_CEILING = 0.95f

private const val BREATH_GAIN = 0.4f

/** Three detuned saws summed at a third each. */
private const val THREE_SAW_MIX = 0.33f
private const val PAD_GAIN = 0.5f

// Drone: a sine, a detuned sine and a little saw.
private const val DRONE_SINE_LEVEL = 0.6f
private const val DRONE_DETUNED_LEVEL = 0.4f
private const val DRONE_SAW_LEVEL = 0.12f
private const val DRONE_GAIN = 0.6f

// Shimmer: sines an octave, a twelfth and two octaves above the note.
private const val TWELFTH = 3.0
private const val DOUBLE_OCTAVE = 4.0
private const val SHIMMER_OCTAVE_LEVEL = 0.5f
private const val SHIMMER_TWELFTH_LEVEL = 0.3f
private const val SHIMMER_DOUBLE_OCTAVE_LEVEL = 0.2f
private const val SHIMMER_GAIN = 0.35f

// Glass: the fundamental and two inharmonic partials that die faster than it.
private const val GLASS_FUNDAMENTAL_LEVEL = 0.6f
private const val GLASS_PARTIAL_2 = 2.32
private const val GLASS_PARTIAL_2_LEVEL = 0.25f
private const val GLASS_PARTIAL_3 = 4.25
private const val GLASS_PARTIAL_3_LEVEL = 0.12f

private const val BOW_VIBRATO_HZ = 5.2
private const val BOW_VIBRATO_DEPTH = 0.004
private const val BOWED_GAIN = 0.5f

private const val CHOIR_DETUNE = 1.0035
private const val CHOIR_FORMANT_HZ = 900.0
private const val CHOIR_FORMANT_LEVEL = 0.5f
private const val CHOIR_GAIN = 0.5f

private const val SUB_FUNDAMENTAL_LEVEL = 0.8f
private const val SUB_OCTAVE_LEVEL = 0.15f

// Tone filter cutoffs that follow the timbre: a floor and a range in Hz, plus the note's pitch times a key tracking.
private const val PAD_CUTOFF_HZ = 350.0
private const val PAD_CUTOFF_RANGE_HZ = 6500.0
private const val DRONE_CUTOFF_HZ = 120.0
private const val DRONE_CUTOFF_RANGE_HZ = 1800.0
private const val BOWED_CUTOFF_HZ = 300.0
private const val BOWED_CUTOFF_RANGE_HZ = 3500.0
private const val CHOIR_CUTOFF_HZ = 500.0
private const val CHOIR_CUTOFF_RANGE_HZ = 4000.0
private const val MIN_CUTOFF = 0.001
private const val MAX_CUTOFF = 0.45

/** A stage that lasts [fraction] of a note of [durSec] seconds, held between [shortest] and [longest] seconds. */
private fun noteShare(
    durSec: Float,
    fraction: Float,
    shortest: Float = 0f,
    longest: Float = Float.POSITIVE_INFINITY,
): Float = clamp(durSec * fraction, shortest, longest)

/** [hz] normalized to [sampleRate] and held inside the tone filter's range. */
private fun cutoff(hz: Double, sampleRate: Double): Float = clamp(hz / sampleRate, MIN_CUTOFF, MAX_CUTOFF).toFloat()

/** One polyphony slot. Renders a mono block per call; [Synth] pans, sends and mixes it. */
internal class SynthVoice(private val sampleRate: Int, seed: Int) {
    private val sr = sampleRate.toDouble()
    private val rng = Rng(seed)
    private val env = Adsr(sampleRate)
    private val filter = Svf()
    private val formant = Svf()
    private val burst = NoiseBurst(sampleRate, rng)
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

    /** Places this voice for headphones; see [BinauralPanner]. */
    val binaural = BinauralPanner(sampleRate)

    /** -1 (west) to 1 (east): the note's pan scaled to the full side of the head. */
    var lateral = 0f
        private set

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

    /** True when the note stands for a mesh node (an ornament), false for the bed. */
    var fromNode = false
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
    private var notePan = 0f
    private var freq = 0.0
    private var amp = 0f
    private var bright = 0f
    private var gain = 1f

    // Fixed for a block by render, read per sample by nextSample.
    private var blockF = 0.0
    private var ratioUp = 1.0
    private var ratioDown = 1.0
    private var fmIndex = 0f

    /**
     * Starts [note] on this voice, fading the current note out over [FADE_SECONDS] first if it is still sounding.
     * Returns false, leaving the voice untouched, when it is already fading toward another note. With [carryOn] the
     * sounding bed tone instead goes on as [note]: its new length, level, send and brightness, rising back from where
     * it is.
     */
    fun begin(note: NoteEvent, carryOn: Boolean = false): Boolean {
        if (fading) return false
        if (carryOn && active) {
            amp = clamp(note.velocity, 0f, 1f)
            bright = clamp(note.brightness, 0f, 1f)
            send = clamp(note.reverbSend, 0f, 1f)
            framesLeft = max(note.durationFrames, 1L)
            startPadBed((framesLeft / sr).toFloat(), fromLevel = true)
        } else if (active) {
            pending = note
            fading = true
            fadeGain = 1f
        } else {
            start(note)
        }
        return true
    }

    private fun start(note: NoteEvent) {
        kind = note.voice
        freq = clamp(note.pitchHz, LOWEST_HZ, sr * HIGHEST_SHARE_OF_RATE)
        amp = clamp(note.velocity, 0f, 1f)
        bright = clamp(note.brightness, 0f, 1f)
        framesLeft = max(note.durationFrames, 1L)
        val durSec = (framesLeft / sr).toFloat()
        val pan = clamp(note.pan, -1f, 1f)
        notePan = pan
        val angle = (pan + 1f) * PAN_TO_ANGLE
        panLeft = cos(angle)
        panRight = sin(angle)
        lateral = (pan / Ornaments.MAX_PAN).coerceIn(-1f, 1f)
        binaural.reset()
        send = clamp(note.reverbSend, 0f, 1f)
        fromNode = note.node != null
        gain = 1f
        filter.reset()
        when (kind) {
            Voice.Mallet, Voice.Marimba, Voice.Vibes, Voice.Kalimba, Voice.Gong -> startStruck(durSec)
            Voice.Pluck, Voice.Harp -> startPlucked(durSec)
            Voice.Pad, Voice.Drone, Voice.Shimmer, Voice.Choir -> startPadBed(durSec)
            else -> startSolo(durSec)
        }
        active = true
        age = 0L
    }

    /** A modal body struck by a fading noise burst. */
    private fun startStruck(durSec: Float) {
        when (kind) {
            Voice.Mallet -> {
                env.gate(attackSec = 0.001f, releaseSec = 0.25f)
                val ring = noteShare(durSec, fraction = 1f, shortest = 0.15f, longest = 20f)
                modal.configure(freq, MALLET_BODY, bright, ring)
                burst.start(frames = (sr * 0.004).toInt(), cutoffHz = 600.0 + 7000.0 * bright)
            }
            Voice.Marimba -> {
                env.gate(attackSec = 0.001f, releaseSec = 0.2f)
                val ring = noteShare(durSec, MARIMBA_RING_PER_NOTE, shortest = 0.15f, longest = 2.5f)
                modal.configure(freq, MARIMBA_BODY, brightness = bright * 0.8f, ring)
                burst.start(frames = (sr * 0.003).toInt(), cutoffHz = 600.0 + 3500.0 * bright)
            }
            Voice.Vibes -> {
                env.gate(attackSec = 0.001f, releaseSec = 0.4f)
                val ring = noteShare(durSec, VIBES_RING_PER_NOTE, shortest = 0.5f, longest = 12f)
                modal.configure(freq, VIBES_BODY, brightness = bright * 0.7f, ring)
                burst.start(frames = (sr * 0.004).toInt(), cutoffHz = 500.0 + 2500.0 * bright)
                sine1.reset(0.0)
            }
            Voice.Kalimba -> {
                env.gate(attackSec = 0.001f, releaseSec = 0.2f)
                val ring = noteShare(durSec, KALIMBA_RING_PER_NOTE, shortest = 0.3f, longest = 4f)
                modal.configure(freq, KALIMBA_BODY, brightness = bright * 0.8f, ring)
                burst.start(frames = (sr * 0.002).toInt(), cutoffHz = 900.0 + 5000.0 * bright)
                gain = KALIMBA_GAIN
            }
            else -> {
                env.gate(attackSec = 0.02f, releaseSec = 0.6f)
                val ring = noteShare(durSec, GONG_RING_PER_NOTE, shortest = 2f, longest = 20f)
                modal.configure(freq, GONG_BODY, brightness = 0.6f + 0.4f * bright, ring)
                burst.start(frames = (sr * 0.02).toInt(), cutoffHz = 300.0 + 3000.0 * bright)
                gain = GONG_GAIN
            }
        }
    }

    /** A string plucked by a flat noise burst one period long. */
    private fun startPlucked(durSec: Float) {
        env.gate(attackSec = 0.001f, releaseSec = 0.3f)
        if (kind == Voice.Pluck) {
            val damping = clamp(
                PLUCK_DAMPING_FLOOR + PLUCK_DAMPING_PER_SECOND * durSec,
                PLUCK_DAMPING_FLOOR,
                PLUCK_DAMPING_CEILING,
            )
            string.configure(freq, nonLinearity = -0.15f, bright, damping)
            burst.start(frames = (sr / freq).toInt(), cutoffHz = 800.0 + 8000.0 * bright)
        } else {
            val damping = clamp(
                HARP_DAMPING_FLOOR + HARP_DAMPING_PER_SECOND * durSec,
                HARP_DAMPING_FLOOR,
                HARP_DAMPING_CEILING,
            )
            string.configure(freq, nonLinearity = 0f, bright, damping)
            burst.start(frames = (sr / freq).toInt(), cutoffHz = 1000.0 + 9000.0 * bright)
        }
    }

    /** The dry voices with no resonator: Bell and Glass decay on their own, Breath, Bowed and Sub are held. */
    private fun startSolo(durSec: Float) {
        when (kind) {
            Voice.Bell -> {
                val decay = noteShare(durSec, fraction = 0.9f, shortest = 0.3f, longest = 40f)
                env.trigger(attackSec = 0.002f, decaySec = decay, sustainLevel = 0f, releaseSec = 0.4f)
                fm1.reset()
                fm2.reset()
            }
            Voice.Glass -> {
                val decay = noteShare(durSec, fraction = 1.1f, shortest = 0.4f, longest = 30f)
                env.trigger(attackSec = 0.003f, decaySec = decay, sustainLevel = 0f, releaseSec = 0.4f)
                sine1.reset(0.0)
                sine2.reset(0.0)
                sine3.reset(0.0)
            }
            Voice.Breath -> {
                env.gate(
                    attackSec = noteShare(durSec, fraction = 0.35f, shortest = 0.05f),
                    releaseSec = noteShare(durSec, fraction = 0.5f, shortest = 0.1f),
                )
                filter.setFq(f = ((500.0 + 5500.0 * bright) / sr).toFloat(), q = 2.5f)
            }
            Voice.Bowed -> {
                env.gate(
                    attackSec = noteShare(durSec, fraction = 0.25f, shortest = 0.05f, longest = 0.4f),
                    releaseSec = noteShare(durSec, fraction = 0.4f, shortest = 0.15f, longest = 3f),
                )
                saw1.reset(0.0)
                sine1.reset(0.0)
            }
            else -> {
                env.gate(
                    attackSec = 0.03f,
                    releaseSec = noteShare(durSec, fraction = 0.4f, shortest = 0.2f, longest = 3f),
                )
                sine1.reset(0.0)
                sine2.reset(0.0)
            }
        }
    }

    /** The voices on the chorused pad bus, whose attack and release grow with the note. */
    /** The pan the current note asked for, -1..1. */
    val pan: Float get() = notePan

    /**
     * The bed envelopes: a held gate whose attack and release are shares of the note. From silence the oscillators
     * restart; [fromLevel] carries the voice on as it is.
     */
    private fun startPadBed(durSec: Float, fromLevel: Boolean = false) {
        val attack = when (kind) {
            Voice.Pad -> noteShare(durSec, fraction = 0.3f, longest = 6f)
            Voice.Drone -> noteShare(durSec, fraction = 0.35f, longest = 8f)
            Voice.Shimmer -> noteShare(durSec, fraction = 0.4f, longest = 6f)
            else -> noteShare(durSec, fraction = 0.35f, longest = 2f)
        }
        val release = when (kind) {
            Voice.Pad -> noteShare(durSec, fraction = 0.4f, shortest = 0.2f, longest = 8f)
            Voice.Drone -> noteShare(durSec, fraction = 0.4f, shortest = 0.2f, longest = 10f)
            Voice.Shimmer -> noteShare(durSec, fraction = 0.5f, shortest = 0.2f, longest = 10f)
            else -> noteShare(durSec, fraction = 0.5f, shortest = 0.3f, longest = 4f)
        }
        if (fromLevel) {
            env.regate(attack, release)
            return
        }
        env.gate(attack, release)
        when (kind) {
            Voice.Pad -> {
                saw1.reset(0.0)
                saw2.reset(startPhase = 0.33)
                saw3.reset(startPhase = 0.66)
            }
            Voice.Drone -> {
                sine1.reset(0.0)
                sine2.reset(startPhase = 0.25)
                saw1.reset(0.0)
            }
            Voice.Shimmer -> {
                sine1.reset(0.0)
                sine2.reset(startPhase = 0.3)
                sine3.reset(startPhase = 0.6)
            }
            else -> {
                saw1.reset(0.0)
                saw2.reset(startPhase = 0.33)
                saw3.reset(startPhase = 0.66)
                formant.reset()
                formant.setFq((CHOIR_FORMANT_HZ / sr).toFloat(), q = 3f)
            }
        }
    }

    /** Writes [n] mono samples to [out]; [brightness] and [detune] are the smoothed texture values. */
    fun render(n: Int, out: FloatArray, brightness: Float, detune: Float) {
        if (!active) {
            out.fill(0f, 0, n)
            return
        }
        blockF = freq / sr
        val tex = clamp(TEXTURE_BRIGHTNESS_SHARE * brightness + NOTE_BRIGHTNESS_SHARE * bright, 0f, 1f)
        val detuneCents = 2.0 + DETUNE_RANGE_CENTS * detune
        ratioUp = semitonesToRatio(detuneCents / CENTS_PER_SEMITONE)
        ratioDown = 1.0 / ratioUp
        tuneFilter(tex)
        var fadeDone = false
        fmIndex = (FM_INDEX_FLOOR + FM_INDEX_RANGE * tex) * (FM_VELOCITY_FLOOR + FM_VELOCITY_RANGE * amp)

        for (i in 0 until n) {
            if (framesLeft > 0L) {
                framesLeft--
                if (framesLeft == 0L) env.release()
            }
            var s = nextSample(env.next())
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
        endBlock(fadeDone)
    }

    /** Points the tone filter of the voices that have one at a cutoff that follows the timbre [tex]. */
    private fun tuneFilter(tex: Float) {
        when (kind) {
            Voice.Pad ->
                filter.setFq(cutoff(PAD_CUTOFF_HZ + PAD_CUTOFF_RANGE_HZ * tex * tex + freq * 2.0, sr), q = 0.8f)
            Voice.Drone ->
                filter.setFq(cutoff(DRONE_CUTOFF_HZ + DRONE_CUTOFF_RANGE_HZ * tex + freq, sr), q = 0.7f)
            Voice.Bowed ->
                filter.setFq(cutoff(BOWED_CUTOFF_HZ + BOWED_CUTOFF_RANGE_HZ * tex + freq * 2.0, sr), q = 0.9f)
            Voice.Choir ->
                filter.setFq(cutoff(CHOIR_CUTOFF_HZ + CHOIR_CUTOFF_RANGE_HZ * tex + freq * 2.0, sr), q = 0.8f)
            else -> Unit
        }
    }

    /** The next sample of the note before the fade and velocity, at envelope level [e]. */
    private fun nextSample(e: Float): Float {
        val f = blockF
        return when (kind) {
            Voice.Bell -> {
                val idx = fmIndex * e
                val carrier = fm1.next(f, BELL_RATIO, idx) * BELL_CARRIER_GAIN
                (carrier + fm2.next(f * BELL_PARTIAL_RATIO, 1.0, idx * BELL_PARTIAL_INDEX) * BELL_PARTIAL_GAIN * e) * e
            }
            Voice.Mallet, Voice.Marimba, Voice.Kalimba, Voice.Gong ->
                modal.process(burst.nextFading() * STRIKE_GAIN) * e * gain
            Voice.Vibes -> {
                val s = modal.process(burst.nextFading() * STRIKE_GAIN) * e * gain
                s * (1f - VIBES_TREMOLO_DEPTH * ((1f + sine1.next(VIBES_TREMOLO_HZ / sr)) / 2))
            }
            Voice.Pluck, Voice.Harp -> string.process(burst.nextFlat() * PLUCK_GAIN) * STRING_GAIN * e
            Voice.Breath -> filter.bandPass(rng.nextSigned()) * e * BREATH_GAIN
            Voice.Pad -> {
                val raw = (saw1.saw(f) + saw2.saw(f * ratioUp) + saw3.saw(f * ratioDown)) * THREE_SAW_MIX
                filter.lowPass(raw) * e * PAD_GAIN
            }
            Voice.Drone -> {
                val raw = sine1.next(f) * DRONE_SINE_LEVEL + sine2.next(f * ratioUp) * DRONE_DETUNED_LEVEL +
                    saw1.saw(f) * DRONE_SAW_LEVEL
                filter.lowPass(raw) * e * DRONE_GAIN
            }
            Voice.Shimmer -> {
                val raw = sine1.next(f * 2.0) * SHIMMER_OCTAVE_LEVEL +
                    sine2.next(f * TWELFTH * ratioUp) * SHIMMER_TWELFTH_LEVEL +
                    sine3.next(f * DOUBLE_OCTAVE * ratioDown) * SHIMMER_DOUBLE_OCTAVE_LEVEL
                raw * e * SHIMMER_GAIN
            }
            Voice.Glass -> {
                val raw = sine1.next(f) * GLASS_FUNDAMENTAL_LEVEL +
                    sine2.next(f * GLASS_PARTIAL_2) * GLASS_PARTIAL_2_LEVEL * e +
                    sine3.next(f * GLASS_PARTIAL_3) * GLASS_PARTIAL_3_LEVEL * e * e
                raw * e
            }
            Voice.Bowed -> {
                val raw = saw1.saw(f * (1.0 + BOW_VIBRATO_DEPTH * sine1.next(BOW_VIBRATO_HZ / sr)))
                filter.lowPass(raw) * e * BOWED_GAIN
            }
            Voice.Choir -> {
                val raw = (saw1.saw(f * CHOIR_DETUNE) + saw2.saw(f) + saw3.saw(f / CHOIR_DETUNE)) * THREE_SAW_MIX
                (filter.lowPass(raw) + CHOIR_FORMANT_LEVEL * formant.bandPass(raw)) * e * CHOIR_GAIN
            }
            Voice.Sub -> (sine1.next(f) * SUB_FUNDAMENTAL_LEVEL + sine2.next(f * 2.0) * SUB_OCTAVE_LEVEL) * e
        }
    }

    /** After a block: a finished fade starts the waiting note, and a silent envelope frees the voice. */
    private fun endBlock(fadeDone: Boolean) {
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
}
