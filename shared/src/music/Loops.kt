package org.meshtastic.aether.music

import kotlin.math.max

/** The first [PAD_LOOPS] bed loops each hold one chord voice on the pad; the rest shimmer the top voice. */
private const val BED_LOOPS = 5
private const val PAD_LOOPS = 4
private const val TOP_VOICE = PAD_LOOPS - 1
private const val MEMORY_LOOPS = 3

/** Each loop first fires a random time after the start, at least this many seconds in. */
private const val BED_FIRST_SECONDS = 1.0
private const val MEMORY_FIRST_SECONDS = 5.0

private const val LOOP_DAY_WEIGHT = 0.6
private const val LOOP_FIRE_BASE = 0.35
private const val MIN_LOOP_NOTE_SECONDS = 1.0
private const val PAD_LOOP_LEVEL = 0.03f
private const val SHIMMER_LOOP_LEVEL = 0.05f
private const val PAD_LOOP_HOLD = 0.4
private const val SHIMMER_LOOP_HOLD = 0.3
private const val PAD_LOOP_VELOCITY = 0.4f
private const val SHIMMER_LOOP_VELOCITY = 0.3f
private const val LOOP_PAN_SPREAD = 0.4f

/** Memory loops recall a fragment of one of the most recently heard nodes' motifs while the mesh is quiet. */
private const val REMEMBERED_NODES = 3
private const val FRAGMENT_NOTES = 2
private const val MEMORY_VELOCITY = 0.15f
private const val MEMORY_SECONDS = 3.0

private fun <T> MutableList<T>.swap(i: Int, j: Int) {
    val t = this[i]
    this[i] = this[j]
    this[j] = t
}

/**
 * Loops on prime periods that never line up: bed loops that hold chord voices on the pad or shimmer, and memory loops
 * that recall the motifs of recently heard nodes. The period order is shuffled from the seed.
 */
internal class Loops(private val score: Score, private val rng: Rng, private val nodes: NodeRegistry) {
    private class Loop(var nextFrame: Long, val baseSeconds: Double, val index: Int)

    private val periods = PERIODS.toMutableList().also { list ->
        for (i in list.lastIndex downTo 1) list.swap(i, rng.nextInt(i + 1))
    }
    private val bed = ArrayList<Loop>()
    private val memory = ArrayList<Loop>()

    fun start(nowFrame: Long) {
        for (i in 0 until BED_LOOPS) {
            bed += Loop(nowFrame + score.sec(rng.range(BED_FIRST_SECONDS, periods[i])), periods[i], i)
        }
        for (i in BED_LOOPS until BED_LOOPS + MEMORY_LOOPS) {
            memory += Loop(nowFrame + score.sec(rng.range(MEMORY_FIRST_SECONDS, periods[i])), periods[i], i)
        }
    }

    /** Fires every loop due before [to]. */
    fun run(nowFrame: Long, to: Long, day: DayState) {
        val active = (LOOP_DAY_WEIGHT * day.density + (1 - LOOP_DAY_WEIGHT) * score.mood.density).coerceIn(0.0, 1.0)
        for (loop in bed) {
            while (loop.nextFrame < to) {
                val at = max(loop.nextFrame, nowFrame)
                val fires = rng.nextDouble() < LOOP_FIRE_BASE + (1 - LOOP_FIRE_BASE) * active
                if (fires) fireBed(loop, at, day)
                loop.nextFrame = at + score.sec(period(loop.baseSeconds, day))
            }
        }
        for (loop in memory) {
            while (loop.nextFrame < to) {
                val at = max(loop.nextFrame, nowFrame)
                fireMemory(loop, at)
                loop.nextFrame = at + score.sec(period(loop.baseSeconds, day))
            }
        }
    }

    private fun fireBed(loop: Loop, at: Long, day: DayState) {
        val levels = score.levels(at)
        val tones = score.chord.voiceSemitones
        if (loop.index < PAD_LOOPS) {
            val length = score.clip(at, score.sec(loop.baseSeconds * PAD_LOOP_HOLD))
            if (levels.getValue(Voice.Pad) > PAD_LOOP_LEVEL && length >= score.sec(MIN_LOOP_NOTE_SECONDS)) {
                score.notes += NoteEvent(
                    at, Voice.Pad, score.padHz(tones[loop.index]), PAD_LOOP_VELOCITY,
                    (loop.index - (PAD_LOOPS - 1) / 2f) * LOOP_PAN_SPREAD, Score.LOOP_SEND, length,
                    day.brightness.toFloat(),
                )
            }
        } else {
            val length = score.clip(at, score.sec(loop.baseSeconds * SHIMMER_LOOP_HOLD))
            if (levels.getValue(Voice.Shimmer) > SHIMMER_LOOP_LEVEL && length >= score.sec(MIN_LOOP_NOTE_SECONDS)) {
                score.notes += NoteEvent(
                    at, Voice.Shimmer, score.padHz(tones[TOP_VOICE]), SHIMMER_LOOP_VELOCITY, 0f, Score.SHIMMER_SEND,
                    length, Score.SHIMMER_BRIGHTNESS,
                )
            }
        }
    }

    private fun fireMemory(loop: Loop, at: Long) {
        val recent = nodes.recentlyHeard(REMEMBERED_NODES)
        if (!score.mood.quiet || recent.isEmpty()) return
        val id = recent[loop.index % recent.size]
        val motif = Motifs.of(id)
        val from = loop.index % (motif.size - FRAGMENT_NOTES + 1)
        val band = Ornaments.band(id)
        val pan = nodes.panOf(id)
        val chord = score.chord
        for (j in 0 until FRAGMENT_NOTES) {
            val k = from + j
            val intended = at + score.sec(if (j == 0) 0.0 else motif.gaps[k])
            score.emit(
                NoteEvent(
                    intended, Voice.Bell, Ornaments.pitchHz(motif.roles[k], band, chord, score.keyHz),
                    MEMORY_VELOCITY, pan, Score.LOOP_SEND, score.sec(MEMORY_SECONDS), Score.MEMORY_BRIGHTNESS, id,
                ),
            )
        }
    }

    private fun period(base: Double, day: DayState): Double =
        Tempo.loopPeriodSeconds(base, day.density, score.phraseSpeed())

    private companion object {
        /** Prime loop periods in seconds, so no two loops line up for long. */
        val PERIODS = listOf(17.0, 19.0, 23.0, 29.0, 31.0, 37.0, 41.0, 43.0, 47.0, 53.0, 59.0)
    }
}
