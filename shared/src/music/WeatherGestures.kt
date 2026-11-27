package org.meshtastic.aether.music

import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.mood.MeshMood
import kotlin.math.min

/** A node's reading counts toward the weather for this long. */
private const val WEATHER_MS = 3600L * 1000

/** A weather quantity with no recent reading scales to the middle of its range. */
private const val NEUTRAL_READING = 0.5

private const val GESTURE_SAMPLE_MS = 60_000L
private const val LOW_SWELL_WAIT_MS = 10L * 60 * 1000
private const val LOW_SWELL_SECONDS = 14.0
private const val LOW_SWELL_VELOCITY = 0.4f
private const val LOW_SWELL_SEND = 0.7f
private const val LOW_SWELL_BRIGHTNESS = 0.2f
private const val GUST_DELTA_MPS = 3f
private const val GUST_GAP_MS = 30_000L
private const val GUST_VELOCITY = 0.2f
private const val GUST_VELOCITY_PER_MPS = 0.03f
private const val GUST_MAX_VELOCITY = 0.5f
private const val GUST_SECONDS = 3.0
private const val GUST_SEND = 0.6f
private const val GUST_BRIGHTNESS = 0.3f

/**
 * A warming crossing sounds the brightest color the scale holds (the Lydian raised fourth first), a cooling one the
 * darkest (the Aeolian minor sixth first). The lists share no interval, so the two directions never sound alike, and
 * neither holds the minor second.
 */
private val WARM_COLORS = intArrayOf(6, 9, 4, 2, 7)
private val COOL_COLORS = intArrayOf(8, 10, 3, 5, 0)

/** The color tone for a temperature crossing in [mode], in semitones above the key: always a tone of the scale. */
internal fun colorSemitone(mode: Mode, rising: Boolean): Int =
    (if (rising) WARM_COLORS else COOL_COLORS).firstOrNull { it in mode.semitones }
        ?: if (rising) WARM_COLORS.last() else COOL_COLORS.last()
private const val COLOR_VELOCITY = 0.25f
private const val COLOR_SEND = 0.8f
private const val COLOR_SECONDS = 3.0
private const val COLOR_BRIGHTNESS = 0.5f

/**
 * The weather the mesh reports, kept per node and played as gestures: a pressure drop is a low pad swell, a wind gust
 * a breath of noise, a temperature crossing a bell on the new mode color.
 */
internal class WeatherGestures(private val score: Score, private val mood: MeshMood, private val nodes: NodeRegistry) {
    private class Reading(val value: Float, val atMillis: Long)

    private val scaler = WeatherScaler()
    private val readings = HashMap<Long, HashMap<Quantity, Reading>>()
    private val pendingGusts = ArrayList<Pair<Long, Float>>()
    private val pendingColors = ArrayList<Int>()
    private val pressure = PressureWatch()
    private val temperature = Crossing()
    private var lowSwellUntilMs = Long.MIN_VALUE
    private var nextGestureMs = Long.MIN_VALUE
    private var lastGustMs = Long.MIN_VALUE / 2

    fun onEnvironment(e: MeshEvent.Environment) {
        observe(e.node, Quantity.Temperature, e.temperatureC, e.atMillis)
        observe(e.node, Quantity.Humidity, e.humidityPct, e.atMillis)
        observe(e.node, Quantity.Pressure, e.pressureHpa, e.atMillis)
        observe(e.node, Quantity.Wind, e.windMps, e.atMillis)
        observe(e.node, Quantity.Lux, e.lux, e.atMillis)
    }

    fun forget(node: Long) {
        readings.remove(node)
    }

    /** The current [q] scaled to 0..1 against its own range, or the middle when no node reported it lately. */
    fun scaled(q: Quantity, nowMs: Long): Double =
        median(q, nowMs)?.let { scaler.scale(q, it).toDouble() } ?: NEUTRAL_READING

    fun run(nowFrame: Long, moment: LocalMoment) {
        val ms = moment.epochMillis
        if (!mood.warmingUp && ms >= nextGestureMs) sample(ms)
        playLowSwell(nowFrame, ms)
        playGusts(nowFrame, ms)
        playColors(nowFrame)
    }

    private fun observe(node: Long, q: Quantity, v: Float?, at: Long) {
        if (v == null || !v.isFinite()) return
        scaler.observe(q, v, at)
        if (q == Quantity.Wind && !mood.warmingUp) {
            val prev = readings[node]?.get(q)
            if (prev != null && at - prev.atMillis <= WEATHER_MS && v - prev.value >= GUST_DELTA_MPS) {
                pendingGusts += node to v - prev.value
            }
        }
        readings.getOrPut(node) { HashMap() }[q] = Reading(v, at)
    }

    /** Median of each node's latest reading of [q], over the nodes that reported within the last hour. */
    private fun median(q: Quantity, nowMs: Long): Float? {
        val values = readings.values.mapNotNull { it[q] }.filter { nowMs - it.atMillis <= WEATHER_MS }
            .map { it.value }.sorted()
        if (values.isEmpty()) return null
        val mid = values.size / 2
        return if (values.size % 2 == 1) values[mid] else (values[mid - 1] + values[mid]) / 2f
    }

    private fun sample(ms: Long) {
        nextGestureMs = ms + GESTURE_SAMPLE_MS
        median(Quantity.Pressure, ms)?.let { if (pressure.sample(ms, it)) lowSwellUntilMs = ms + LOW_SWELL_WAIT_MS }
        median(Quantity.Temperature, ms)?.let {
            val dir = temperature.update(scaler.scale(Quantity.Temperature, it))
            if (dir != 0) pendingColors += dir
        }
    }

    private fun playLowSwell(nowFrame: Long, ms: Long) {
        if (lowSwellUntilMs < ms || score.levels(nowFrame).getValue(Voice.Pad) <= Score.AUDIBLE_LEVEL) return
        val length = score.clip(nowFrame, score.sec(LOW_SWELL_SECONDS))
        if (length <= 0) return
        lowSwellUntilMs = Long.MIN_VALUE
        score.notes += NoteEvent(
            nowFrame, Voice.Pad, score.keyHz * score.chord.ratio(score.chord.rootSemitone) / 2, LOW_SWELL_VELOCITY, 0f,
            LOW_SWELL_SEND, length, LOW_SWELL_BRIGHTNESS,
        )
    }

    private fun playGusts(nowFrame: Long, ms: Long) {
        for ((node, delta) in pendingGusts) {
            if (ms - lastGustMs < GUST_GAP_MS) continue
            val velocity = min(GUST_MAX_VELOCITY, GUST_VELOCITY + GUST_VELOCITY_PER_MPS * delta)
            val gust = NoteEvent(
                nowFrame, Voice.Breath, Ornaments.BREATH_HZ, velocity, nodes.panOf(node), GUST_SEND,
                score.sec(GUST_SECONDS), GUST_BRIGHTNESS, node,
            )
            if (score.emit(gust)) lastGustMs = ms
        }
        pendingGusts.clear()
    }

    private fun playColors(nowFrame: Long) {
        for (dir in pendingColors) {
            val semitone = colorSemitone(score.chord.mode, rising = dir > 0)
            score.emit(
                NoteEvent(
                    nowFrame, Voice.Bell, score.keyHz * score.chord.ratio(semitone) * 2, COLOR_VELOCITY, 0f, COLOR_SEND,
                    score.sec(COLOR_SECONDS), COLOR_BRIGHTNESS, null,
                ),
            )
        }
        pendingColors.clear()
    }
}
