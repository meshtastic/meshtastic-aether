package org.meshtastic.aether.music

/** The chime waits up to this long for the key to settle after the hour turns, then gives up. */
private const val CHIME_WAIT_SECONDS = 60.0
private const val CHIME_STEP_SECONDS = 1.5
private const val CHIME_SECONDS = 3.0
private const val CHIME_VELOCITY = 0.2f
private const val CHIME_SEND = 0.8f
private const val CHIME_BRIGHTNESS = 0.3f
private const val HOURS_ON_CLOCK = 12

/** Chime and swell notes alternate the key's root an octave up and its fifth above that. */
private const val OCTAVE_RATIO = 2.0
private const val TWELFTH_RATIO = 3.0

private const val DAWN_MINUTE = 7 * Day.MINUTES_PER_HOUR
private const val DUSK_MINUTE = 19 * Day.MINUTES_PER_HOUR
private const val SHIMMER_SWELL_SECONDS = 16.0
private const val SHIMMER_SWELL_STEP_SECONDS = 1.5
private const val SHIMMER_SWELL_VELOCITY = 0.3f
private const val SHIMMER_SWELL_PAN = 0.3f

/**
 * The hour chime (one strike per clock hour) and the dawn and dusk shimmer swells, all on the key's root and fifth and
 * only while the form is audible.
 */
internal class DayEvents(private val score: Score) {
    private var lastHour = -1
    private var lastMinute = -1
    private var chimeHour = -1
    private var chimeUntilFrame = 0L

    fun run(nowFrame: Long, moment: LocalMoment, day: DayState) {
        val minute = Day.minuteOf(moment)
        val hour = minute / Day.MINUTES_PER_HOUR
        val prevHour = lastHour
        val prevMinute = lastMinute
        lastHour = hour
        lastMinute = minute
        if (prevHour >= 0 && hour != prevHour) {
            chimeHour = hour
            chimeUntilFrame = nowFrame + score.sec(CHIME_WAIT_SECONDS)
        }
        if (chimeHour >= 0) chime(nowFrame, day)
        if (prevMinute >= 0 && score.bedVisible(nowFrame)) swellAtDawnOrDusk(nowFrame, prevMinute, minute)
    }

    /** Rings the pending hour once the day's key is sounding; drops it after [CHIME_WAIT_SECONDS]. */
    private fun chime(nowFrame: Long, day: DayState) {
        if (nowFrame > chimeUntilFrame) {
            chimeHour = -1
        } else if (day.rootSemitones == score.keySemitones) {
            val count = if (chimeHour % HOURS_ON_CLOCK == 0) HOURS_ON_CLOCK else chimeHour % HOURS_ON_CLOCK
            chimeHour = -1
            if (score.bedVisible(nowFrame)) ring(nowFrame, count)
        }
    }

    private fun ring(nowFrame: Long, count: Int) {
        for (i in 0 until count) {
            val ratio = if (i % 2 == 0) OCTAVE_RATIO else TWELFTH_RATIO
            score.emit(
                NoteEvent(
                    nowFrame + score.sec(CHIME_STEP_SECONDS * i), Voice.Bell, score.keyHz * ratio, CHIME_VELOCITY, 0f,
                    CHIME_SEND, score.sec(CHIME_SECONDS), CHIME_BRIGHTNESS, null,
                ),
            )
        }
    }

    /** Dawn rises from the root to the fifth, dusk falls from the fifth to the root, panned apart. */
    private fun swellAtDawnOrDusk(nowFrame: Long, prevMinute: Int, minute: Int) {
        val dawn = prevMinute < DAWN_MINUTE && minute >= DAWN_MINUTE
        val dusk = prevMinute < DUSK_MINUTE && minute >= DUSK_MINUTE
        if (!dawn && !dusk) return
        val ratios = if (dawn) {
            doubleArrayOf(OCTAVE_RATIO, TWELFTH_RATIO)
        } else {
            doubleArrayOf(TWELFTH_RATIO, OCTAVE_RATIO)
        }
        ratios.forEachIndexed { i, ratio ->
            val at = nowFrame + score.sec(SHIMMER_SWELL_STEP_SECONDS * i)
            val length = score.clip(at, score.sec(SHIMMER_SWELL_SECONDS))
            if (length > 0) {
                score.notes += NoteEvent(
                    at, Voice.Shimmer, score.keyHz * ratio, SHIMMER_SWELL_VELOCITY,
                    if (i == 0) -SHIMMER_SWELL_PAN else SHIMMER_SWELL_PAN, Score.SHIMMER_SEND, length,
                    Score.SHIMMER_BRIGHTNESS,
                )
            }
        }
    }
}
