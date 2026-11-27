package org.meshtastic.aether.mood

import org.meshtastic.aether.mesh.MeshEvent

/**
 * The per-node telemetry the mood reads: pressure, temperature and battery as sampled series for their 3 h trends,
 * and the latest temperature, wind and channel use for the medians. [refresh] recomputes the signals at most every
 * 30 s; between refreshes they hold. Single-threaded.
 */
internal class NodeReadings {
    private class Series {
        val at = ArrayDeque<Long>()
        val value = ArrayDeque<Double>()
    }

    private class Reading(var value: Double, var at: Long)

    private val pressure = HashMap<Long, Series>()
    private val temperatureSeries = HashMap<Long, Series>()
    private val batterySeries = HashMap<Long, Series>()
    private val temperature = HashMap<Long, Reading>()
    private val windSpeed = HashMap<Long, Reading>()
    private val channelUse = HashMap<Long, Reading>()
    private val warmthRange = RollingRange(minSpan = 5.0, defaultLo = -10.0, defaultHi = 35.0)
    private var refreshedAt = Long.MIN_VALUE

    /** Per-node 3 h changes (median) as a fraction of each one's full-scale change, -1..1. */
    var pressureTrend = MoodSignal.FLAT
        private set
    var temperatureTrend = MoodSignal.FLAT
        private set
    var batteryTrend = MoodSignal.FLAT
        private set

    /** Nodes whose battery level changed over the 3 h window: the only ones the battery trend counts. */
    var batteryMoved = 0
        private set

    /** Median temperature over reporting nodes against its own 24 h range. */
    var warmth = MoodSignal.NEUTRAL
        private set

    /** Median wind speed as a fraction of [RotationTargets.WIND_FULL_SCALE_MPS]. */
    var wind = MoodSignal.FLAT
        private set

    /** Median channel utilization as a fraction of [UTIL_FULL_PCT]. */
    var channel = MoodSignal.FLAT
        private set

    fun onEnvironment(e: MeshEvent.Environment) {
        e.pressureHpa?.let { p ->
            if (p.isFinite() && p in PRESSURE_MIN_HPA..PRESSURE_MAX_HPA) {
                sample(pressure, e.node, p.toDouble(), e.atMillis)
            }
        }
        e.temperatureC?.let {
            if (it.isFinite() && it in TEMPERATURE_MIN_C..TEMPERATURE_MAX_C) {
                record(temperature, e.node, it.toDouble(), e.atMillis)
                sample(temperatureSeries, e.node, it.toDouble(), e.atMillis)
            }
        }
        e.windMps?.let {
            if (it.isFinite() && it in 0f..WIND_MAX_MPS) record(windSpeed, e.node, it.toDouble(), e.atMillis)
        }
    }

    fun onHealth(e: MeshEvent.Health) {
        e.batteryPct?.let { if (it in 0..FULL_PCT) sample(batterySeries, e.node, it.toDouble(), e.atMillis) }
        e.channelUtilPct?.let {
            if (it.isFinite() && it in 0f..FULL_PCT.toFloat()) record(channelUse, e.node, it.toDouble(), e.atMillis)
        }
    }

    fun forget(node: Long) {
        pressure.remove(node)
        temperatureSeries.remove(node)
        batterySeries.remove(node)
        temperature.remove(node)
        windSpeed.remove(node)
        channelUse.remove(node)
    }

    fun refresh(now: Long) {
        if (refreshedAt != Long.MIN_VALUE && now - refreshedAt < REFRESH_MS) return
        refreshedAt = now

        pressureTrend = trendOf(deltas(pressure, now, FRESH_MS, false), RotationTargets.PRESSURE_FULL_SCALE_HPA)
        temperatureTrend =
            trendOf(deltas(temperatureSeries, now, TREND_FRESH_MS, false), RotationTargets.TEMPERATURE_FULL_SCALE_C)
        val battery = deltas(batterySeries, now, TREND_FRESH_MS, true)
        batteryMoved = battery.size
        batteryTrend = trendOf(battery, RotationTargets.BATTERY_FULL_SCALE_PCT)

        val temps = freshValues(temperature, now, WARMTH_FRESH_MS)
        val warmthConf = ramp(temps.size.toDouble(), FULL_WEATHER_NODES)
        warmth = if (temps.isNotEmpty()) {
            val med = median(temps, temps.size)
            warmthRange.observe(med, now)
            MoodSignal(warmthRange.normalize(med), warmthConf)
        } else {
            MoodSignal(MoodSignal.NEUTRAL.value, warmthConf)
        }

        val winds = freshValues(windSpeed, now, FRESH_MS)
        wind = MoodSignal(
            if (winds.isNotEmpty()) clamp01(median(winds, winds.size) / RotationTargets.WIND_FULL_SCALE_MPS) else 0.0,
            ramp(winds.size.toDouble(), FULL_WEATHER_NODES),
        )
        val uses = freshValues(channelUse, now, FRESH_MS)
        channel = MoodSignal(
            if (uses.isNotEmpty()) clamp01(median(uses, uses.size) / UTIL_FULL_PCT) else 0.0,
            ramp(uses.size.toDouble(), FULL_CONFIDENCE_NODES),
        )
    }

    /** The median of [deltas] over [fullScale], with confidence by how many nodes it rests on. */
    private fun trendOf(deltas: DoubleArray, fullScale: Double): MoodSignal {
        val confidence = ramp(deltas.size.toDouble(), FULL_CONFIDENCE_NODES)
        val value = if (deltas.isNotEmpty()) (median(deltas, deltas.size) / fullScale).coerceIn(-1.0, 1.0) else 0.0
        return MoodSignal(value, confidence)
    }

    /** Keeps one sample per 5 minutes per node for the last 6 hours, the latest within a slot replacing the earlier. */
    private fun sample(map: HashMap<Long, Series>, node: Long, v: Double, at: Long) {
        val s = map.getOrPut(node) { Series() }
        if (s.at.isNotEmpty() && at - s.at.last() < SAMPLE_MS) {
            s.value[s.value.lastIndex] = v
        } else {
            s.at.addLast(at)
            s.value.addLast(v)
        }
        while (s.at.isNotEmpty() && s.at.first() < at - SERIES_KEEP_MS) {
            s.at.removeFirst()
            s.value.removeFirst()
        }
    }

    private fun record(map: HashMap<Long, Reading>, node: Long, v: Double, at: Long) {
        val r = map[node]
        if (r == null) {
            map[node] = Reading(v, at)
        } else {
            r.value = v
            r.at = at
        }
    }

    /**
     * Each node's change over [TREND_WINDOW_MS] for the nodes that reported within [freshMs]; a node with under that
     * much history is left out, and with [skipFlat] so is one whose value did not move.
     */
    private fun deltas(map: HashMap<Long, Series>, now: Long, freshMs: Long, skipFlat: Boolean): DoubleArray {
        val out = DoubleArray(map.size)
        var n = 0
        for (s in map.values) {
            out[n] = s.windowChange(now, freshMs, skipFlat) ?: continue
            n++
        }
        return out.copyOf(n)
    }

    /**
     * This series' change scaled to exactly [TREND_WINDOW_MS], measured from its last sample back to the latest sample
     * at least a window older; null when the last sample is older than [freshMs], when there is no window of history,
     * or, with [skipFlat], when the value did not move.
     */
    private fun Series.windowChange(now: Long, freshMs: Long, skipFlat: Boolean): Double? {
        val last = at.lastIndex
        if (last < 0 || now - at[last] > freshMs) return null
        var first = last - 1
        while (first >= 0 && at[last] - at[first] < TREND_WINDOW_MS) first--
        return when {
            first < 0 -> null
            skipFlat && value[last] == value[first] -> null
            else -> (value[last] - value[first]) * TREND_WINDOW_MS / (at[last] - at[first]).toDouble()
        }
    }

    private fun freshValues(map: HashMap<Long, Reading>, now: Long, maxAge: Long): DoubleArray {
        var n = 0
        for (r in map.values) if (now - r.at <= maxAge) n++
        val out = DoubleArray(n)
        var i = 0
        for (r in map.values) if (now - r.at <= maxAge) out[i++] = r.value
        return out
    }

    private companion object {
        const val REFRESH_MS = 30_000L
        const val SAMPLE_MS = 5L * 60_000
        const val SERIES_KEEP_MS = 6L * 3_600_000
        const val TREND_WINDOW_MS = 3.0 * 3_600_000
        const val FRESH_MS = 3_600_000L
        const val TREND_FRESH_MS = 2L * 3_600_000
        const val WARMTH_FRESH_MS = 3L * 3_600_000

        /** Two nodes reporting temperature or wind make a full-confidence reading. */
        const val FULL_WEATHER_NODES = 2.0

        /** Readings outside these are sensor faults, not weather. */
        const val PRESSURE_MIN_HPA = 800f
        const val PRESSURE_MAX_HPA = 1100f
        const val TEMPERATURE_MIN_C = -60f
        const val TEMPERATURE_MAX_C = 70f
        const val WIND_MAX_MPS = 100f
        const val FULL_PCT = 100
    }
}
