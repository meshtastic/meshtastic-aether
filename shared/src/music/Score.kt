package org.meshtastic.aether.music

import org.meshtastic.aether.mood.MoodSnapshot
import org.meshtastic.aether.mood.ParamValues
import kotlin.math.min
import kotlin.math.pow

/**
 * What every part of the [Composer] shares while it writes one window: the frame clock, the key and register the bed
 * sits in, the tick bed notes end at, the ornament slots, the mood, and the notes and controls written so far.
 */
internal class Score(val sampleRate: Int, val form: Form, val harmony: Harmony) {
    val slots = SlotAllocator(Ornaments.SLOT_CAP)
    var keyHz = Theory.C3_HZ
    var keySemitones = Double.NaN
    var registerShift = 0

    /** The next tick, the only place a chord or key can change; every bed note ends by it. */
    var nextTickFrame = 0L

    var mood: MoodSnapshot = MoodSnapshot.NEUTRAL
        private set
    var params: ParamValues = MoodSnapshot.NEUTRAL.params
        private set
    var notes = ArrayList<NoteEvent>()
        private set
    var controls = ArrayList<ControlEvent>()
        private set

    val chord: Chord get() = harmony.current

    /** Starts a window under [mood] and [params], with nothing written yet. */
    fun begin(mood: MoodSnapshot, params: ParamValues) {
        this.mood = mood
        this.params = params
        notes = ArrayList()
        controls = ArrayList()
    }

    /** The window's notes and controls, each in frame order. */
    fun output(): ComposerOutput {
        notes.sortBy { it.atFrame }
        controls.sortBy { it.atFrame }
        return ComposerOutput(notes, controls)
    }

    fun sec(s: Double): Long = (s * sampleRate).toLong()

    /** Length of a bed note starting at [start] cut at the next tick. */
    fun clip(start: Long, wanted: Long): Long = min(wanted, nextTickFrame - start)

    /** Pads sit whole octaves from the key so they stay in tune with the drone and bells at every minute of the day. */
    fun padHz(semitone: Int): Double = keyHz * chord.ratio(semitone) * 2.0.pow(registerShift)

    fun levels(frame: Long): Map<Voice, Float> = form.levels(frame / sampleRate.toDouble())

    /** Phrase tempo band 0.5, 0.75, 1 or 1.5: divides motif step length, the answer delay and gap, and loop periods. */
    fun phraseSpeed(): Double = Tempo.clampSpeed(params.phraseSpeed)

    /** Plays [note] on a free ornament slot at or after its frame and within the TTL; false when it was dropped. */
    fun emit(note: NoteEvent): Boolean {
        val at = slots.allocate(note.atFrame, note.durationFrames, note.atFrame + sec(Ornaments.TTL_SECONDS))
            ?: return false
        notes += note.copy(atFrame = at)
        return true
    }

    /** Whether the form can be heard at all at [frame]. */
    fun bedVisible(frame: Long): Boolean = Form.visible(levels(frame)) > Form.FLOOR

    companion object {
        /** A bed layer quieter than this is not worth a note. */
        const val AUDIBLE_LEVEL = 0.02f

        /** The tick: every this many seconds the drone and pad are struck again. */
        const val SWELL_SECONDS = 20.0

        /** Reverb sends and brightness shared by more than one kind of note. */
        const val PAD_SEND = 0.6f
        const val LOOP_SEND = 0.7f
        const val SHIMMER_SEND = 0.9f
        const val SHIMMER_BRIGHTNESS = 0.9f
        const val MEMORY_BRIGHTNESS = 0.3f
    }
}
