package org.meshtastic.aether.music

private const val TEXTURE_SECONDS = 5.0
private const val MIN_FORCED_SECONDS = 45.0
private const val CHORD_MIN_SECONDS = 120.0
private const val CHORD_MAX_SECONDS = 480.0

private const val REGISTER_FIRST_SPLIT = -0.5
private const val REGISTER_ENTER_LOW = -0.65
private const val REGISTER_ENTER_HIGH = -0.35

private const val DRONE_VELOCITY = 0.7f
private const val DRONE_SEND = 0.5f
private const val DRONE_BRIGHTNESS = 0.2f
private const val PAD_VELOCITY = 0.5f
private const val PAD_STAGGER_SECONDS = 0.3
private const val PAD_VOICES = 4

/**
 * The drone and pad struck at every tick in the current chord, and the chord and key changes between them. Every
 * [Score.SWELL_SECONDS] the bed is struck again and each bed note ends at the next tick, so a chord or key change
 * never sits on top of the one before. A chord changes when its one duration draw has run out, when the day's key
 * has changed, or when a text asked for it and the last change was at least [MIN_FORCED_SECONDS] ago.
 */
internal class Bed(
    private val score: Score,
    private val rng: Rng,
    private val sessionVoices: SessionVoices,
    private val mixer: TextureMixer,
) {
    /** Set by a text: change the chord at the next tick that is far enough from the last change. */
    var chordRequested = false

    private val scales = ScalePicker()
    private var chordStarted = false
    private var lastChordFrame = 0L
    private var nextChordFrame = 0L
    private var nextTextureFrame = 0L

    /** Each pad voice's last pitch, so a chord change staggers only the voices that move. */
    private val lastPadHz = DoubleArray(PAD_VOICES)

    fun start(nowFrame: Long) {
        nextChordFrame = nowFrame
        score.nextTickFrame = nowFrame
        nextTextureFrame = nowFrame
    }

    fun tick(at: Long, day: DayState, moment: LocalMoment) {
        val keyChanged = day.rootSemitones != score.keySemitones
        val textDue = chordRequested && at - lastChordFrame >= score.sec(MIN_FORCED_SECONDS)
        val chordDue = !chordStarted || keyChanged || textDue || at >= nextChordFrame
        if (chordDue) changeChord(at, day, moment)
        score.nextTickFrame = at + score.sec(Score.SWELL_SECONDS)
        val levels = score.levels(at)
        if (levels.getValue(Voice.Drone) > Score.AUDIBLE_LEVEL) playDrone(at)
        if (levels.getValue(Voice.Pad) > Score.AUDIBLE_LEVEL) {
            playPads(at, day, staggered = chordDue)
            sessionVoices.play(at)
        }
    }

    /** A texture change once [TEXTURE_SECONDS] have passed without a chord change bringing one. */
    fun refreshTexture(nowFrame: Long, windowEnd: Long, day: DayState, moment: LocalMoment) {
        if (nextTextureFrame >= windowEnd) return
        val at = maxOf(nextTextureFrame, nowFrame)
        setTexture(at, day, moment)
        nextTextureFrame = at + score.sec(TEXTURE_SECONDS)
    }

    private fun setTexture(at: Long, day: DayState, moment: LocalMoment) {
        val texture = mixer.texture(day, at / score.sampleRate.toDouble(), moment.epochMillis)
        score.controls += ControlEvent.SetTexture(at, texture)
    }

    private fun changeChord(at: Long, day: DayState, moment: LocalMoment) {
        score.keySemitones = day.rootSemitones
        score.keyHz = day.rootHz
        score.registerShift = nextRegister(day.registerOctaves, chordStarted)
        val p = score.params
        score.harmony.next(scales.pick(day.mode, p.modeLean, p.scalePentatonicBias))
        chordStarted = true
        chordRequested = false
        lastChordFrame = at
        nextChordFrame = at + score.sec(rng.range(CHORD_MIN_SECONDS, CHORD_MAX_SECONDS))
        nextTextureFrame = at + score.sec(TEXTURE_SECONDS)
        setTexture(at, day, moment)
    }

    /**
     * Whole-octave register for [target] (0 by day to -1 at night): it only flips past [REGISTER_ENTER_LOW] or
     * [REGISTER_ENTER_HIGH].
     */
    private fun nextRegister(target: Double, running: Boolean): Int = when {
        !running -> if (target < REGISTER_FIRST_SPLIT) -1 else 0
        target < REGISTER_ENTER_LOW -> -1
        target > REGISTER_ENTER_HIGH -> 0
        else -> score.registerShift
    }

    private fun playDrone(at: Long) {
        score.notes += NoteEvent(
            at, Voice.Drone, score.keyHz * score.chord.ratio(score.chord.rootSemitone) / 2, DRONE_VELOCITY, 0f,
            DRONE_SEND, score.clip(at, score.sec(Score.SWELL_SECONDS)), DRONE_BRIGHTNESS,
        )
    }

    /**
     * The pad's chord tones. On a new chord the voices that move enter [staggered], one after another; every tone that
     * holds its pitch strikes at the tick, where its predecessor ends, so the synth carries it on without a dip.
     */
    private fun playPads(at: Long, day: DayState, staggered: Boolean) {
        score.chord.voiceSemitones.forEachIndexed { i, s ->
            val hz = score.padHz(s)
            val moved = hz != lastPadHz[i]
            lastPadHz[i] = hz
            val start = at + if (staggered && moved) score.sec(PAD_STAGGER_SECONDS * i) else 0L
            score.notes += NoteEvent(
                start, Voice.Pad, hz, PAD_VELOCITY, PAD_PANS[i], Score.PAD_SEND,
                score.clip(start, score.sec(Score.SWELL_SECONDS)), day.brightness.toFloat(),
            )
        }
    }

    private companion object {
        val PAD_PANS = floatArrayOf(-0.5f, -0.17f, 0.17f, 0.5f)
    }
}
