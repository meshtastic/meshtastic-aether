package org.meshtastic.aether.mood

import kotlin.math.exp
import kotlin.math.ln
import org.meshtastic.aether.mesh.LinkState
import org.meshtastic.aether.mesh.MeshEvent

/**
 * The pass-1 signals at one instant. Each signal is 0..1 with a confidence 0..1; a signal with no data holds its neutral
 * (0.5) and confidence 0. The trends are -1..1: [pressureTrend], [temperatureTrend] and [batteryTrend] are per-node
 * 3 h changes (median) as a fraction of a full-scale change, [activityTrend] is the change of relative activity over 30 minutes.
 */
data class MoodSnapshot(
    val nowMillis: Long,
    val activity: Double, val activityConfidence: Double,
    val reach: Double, val reachConfidence: Double,
    val linkQuality: Double, val linkQualityConfidence: Double,
    val senders: Double, val sendersConfidence: Double,
    /** Median temperature over reporting nodes against its own 24 h range. */
    val warmth: Double, val warmthConfidence: Double,
    /** Median wind speed as a fraction of 15 m/s. */
    val wind: Double, val windConfidence: Double,
    val pressureTrend: Double, val pressureTrendConfidence: Double,
    val temperatureTrend: Double, val temperatureTrendConfidence: Double,
    val batteryTrend: Double, val batteryTrendConfidence: Double,
    val activityTrend: Double, val activityTrendConfidence: Double,
    /** Nodes whose battery level changed over the 3 h window: the only ones the battery trend counts. */
    val batteryMovedNodes: Int,
    /** True when the mesh is quiet against its own history (or nothing is known yet) and the link is not warming up. */
    val quiet: Boolean,
    val warmingUp: Boolean,
    /** 0..1 busyness: relative activity, relative sender count and our channel utilization. */
    val density: Double,
    /** 0..1 from relative link quality; 0.5 with no data. */
    val brightness: Double,
    /** 0..1 from relative activity, channel utilization and a poor link. */
    val tension: Double,
    val rotation: RotationTarget,
    val params: ParamValues,
) {
    companion object {
        val NEUTRAL = MoodSnapshot(
            0L, 0.5, 0.0, 0.5, 0.0, 0.5, 0.0, 0.5, 0.0, 0.5, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, true, false, 0.0, 0.5, 0.15,
            RotationTarget.NEUTRAL, ParamValues.NEUTRAL,
        )
    }
}

/**
 * Folds mesh events into slow, self-normalized signals and the rotation and parameter values derived from them, plus
 * the short-window pace the composer thins and gates by. Time is the events' own epoch millis; [advance] moves the clock
 * and reads the snapshot. While the link is warming up, packets feed nothing (a connect burst is not the mesh's pace).
 * Single-threaded.
 */
class MeshMood {
    private var clock = Long.MIN_VALUE
    private val rate = Decay(ACTIVITY_TAU)
    private val fast = Decay(FAST_TAU)
    private val slow = Decay(SLOW_TAU)
    private val hopsSum = Decay(REACH_TAU)
    private val hopsWeight = Decay(REACH_TAU)
    private val snrSum = Decay(LINK_TAU)
    private val snrWeight = Decay(LINK_TAU)
    private var packets = 0L
    private var lastPacketMs = Long.MIN_VALUE
    private val lastHeard = HashMap<Long, Long>()
    private var warming = false
    private var util: Double? = null

    private val activityRange = RollingRange(0.5, 0.0, ln(21.0))
    private val reachRange = RollingRange(1.0, 0.0, 7.0)
    private val snrRange = RollingRange(4.0, -20.0, 10.0)
    private val sendersRange = RollingRange(0.7, 0.0, ln(31.0))
    private val warmthRange = RollingRange(5.0, -10.0, 35.0)

    private val trendX = DoubleArray(TREND_SLOTS)
    private val trendStamp = LongArray(TREND_SLOTS) { Long.MIN_VALUE }

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
    private var weatherAt = Long.MIN_VALUE
    private var pressureTrendV = 0.0
    private var pressureConf = 0.0
    private var temperatureTrendV = 0.0
    private var temperatureTrendConf = 0.0
    private var batteryTrendV = 0.0
    private var batteryTrendConf = 0.0
    private var batteryMoved = 0
    private var warmthV = 0.5
    private var warmthConf = 0.0
    private var windV = 0.0
    private var windConf = 0.0
    private var sendersCount = 0
    private var sendersAt = Long.MIN_VALUE
    private var band = 2

    /** True between `Link(WarmingUp)` and the next link state. */
    val warmingUp: Boolean get() = warming

    /** Packets per second over about 2 s: what ornament thinning reacts to. */
    val fastRate: Double get() = fast.value

    /** Distinct nodes heard within the last [windowMs] before the clock. */
    fun activeNodes(windowMs: Long): Int {
        var n = 0
        for (t in lastHeard.values) if (clock - t <= windowMs) n++
        return n
    }

    fun onEvent(e: MeshEvent) {
        when (e) {
            is MeshEvent.PacketHeard -> {
                sync(e.atMillis)
                if (!warming) onPacket(e)
            }
            is MeshEvent.NodeSeen -> sync(e.atMillis)
            is MeshEvent.NodeGone -> {
                sync(e.atMillis)
                lastHeard.remove(e.node)
                pressure.remove(e.node)
                temperatureSeries.remove(e.node)
                batterySeries.remove(e.node)
                temperature.remove(e.node)
                windSpeed.remove(e.node)
            }
            is MeshEvent.Environment -> {
                sync(e.atMillis)
                onEnvironment(e)
            }
            is MeshEvent.Health -> {
                sync(e.atMillis)
                e.batteryPct?.let { if (it in 0..100) sample(batterySeries, e.node, it.toDouble(), e.atMillis) }
            }
            is MeshEvent.Vitals -> {
                sync(e.atMillis)
                e.channelUtilPct?.let { if (it.isFinite()) util = util?.let { u -> u + UTIL_SMOOTHING * (it - u) } ?: it.toDouble() }
            }
            is MeshEvent.Link -> {
                sync(e.atMillis)
                warming = e.state == LinkState.WarmingUp
            }
            else -> Unit
        }
    }

    private fun onPacket(e: MeshEvent.PacketHeard) {
        packets++
        lastPacketMs = maxOf(lastPacketMs, e.atMillis)
        rate.add(1.0 / ACTIVITY_TAU)
        fast.add(1.0 / FAST_TAU)
        slow.add(1.0 / SLOW_TAU)
        lastHeard[e.from] = e.atMillis
        e.hopsTaken?.let {
            hopsSum.add(it.coerceIn(0, 7).toDouble())
            hopsWeight.add(1.0)
        }
        e.snr?.let {
            if (it.isFinite()) {
                snrSum.add(it.toDouble().coerceIn(-30.0, 20.0))
                snrWeight.add(1.0)
            }
        }
    }

    private fun onEnvironment(e: MeshEvent.Environment) {
        e.pressureHpa?.let { p -> if (p.isFinite() && p in 800f..1100f) sample(pressure, e.node, p.toDouble(), e.atMillis) }
        e.temperatureC?.let {
            if (it.isFinite() && it in -60f..70f) {
                record(temperature, e.node, it.toDouble(), e.atMillis)
                sample(temperatureSeries, e.node, it.toDouble(), e.atMillis)
            }
        }
        e.windMps?.let { if (it.isFinite() && it in 0f..100f) record(windSpeed, e.node, it.toDouble(), e.atMillis) }
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
        if (r == null) map[node] = Reading(v, at) else {
            r.value = v
            r.at = at
        }
    }

    private fun sync(ms: Long) {
        if (clock != Long.MIN_VALUE && ms <= clock) return
        clock = ms
        rate.advance(ms)
        fast.advance(ms)
        slow.advance(ms)
        hopsSum.advance(ms)
        hopsWeight.advance(ms)
        snrSum.advance(ms)
        snrWeight.advance(ms)
    }

    /** Moves the clock to [nowMillis] (never backwards) and returns the signals, rotation target and parameters at that time. */
    fun advance(nowMillis: Long): MoodSnapshot {
        sync(nowMillis)
        val now = clock
        val hasPackets = packets > 0

        val ratePm = rate.value * 60.0
        val x = ln(1.0 + ratePm)
        val sinceLast = if (hasPackets) (now - lastPacketMs).coerceAtLeast(0L) / 1000.0 else 0.0
        val dataConf = if (hasPackets) ramp(packets.toDouble(), 10.0) * exp(-maxOf(0.0, sinceLast - STALE_GRACE_S) / STALE_TAU_S) else 0.0
        var activity = 0.5
        if (hasPackets) {
            activityRange.observe(x, now)
            activity = activityRange.normalize(x)
        }
        val (trend, trendConf) = activityTrendOf(now, x, activity, dataConf, hasPackets)

        val reachW = hopsWeight.value
        var reach = 0.5
        if (reachW >= MIN_WEIGHT) {
            val mean = hopsSum.value / reachW
            if (reachW >= 0.5) reachRange.observe(mean, now)
            reach = reachRange.normalize(mean)
        }
        val reachConf = if (reachW >= MIN_WEIGHT) ramp(reachW, 3.0) else 0.0

        val snrW = snrWeight.value
        var link = 0.5
        if (snrW >= MIN_WEIGHT) {
            val mean = snrSum.value / snrW
            if (snrW >= 0.5) snrRange.observe(mean, now)
            link = snrRange.normalize(mean)
        }
        val linkConf = if (snrW >= MIN_WEIGHT) ramp(snrW, 3.0) else 0.0

        refreshSenders(now)
        var senders = 0.5
        if (sendersCount > 0) {
            val sx = ln(1.0 + sendersCount)
            sendersRange.observe(sx, now)
            senders = sendersRange.normalize(sx)
        }
        val sendersConf = ramp(sendersCount.toDouble(), 3.0)

        refreshWeather(now)

        val choice = RotationTargets.chooseDirection(
            pressureTrendV, pressureConf, temperatureTrendV, temperatureTrendConf, batteryTrendV, batteryTrendConf, trend, trendConf,
        )
        val rotation = RotationTarget(
            RotationTargets.periodSeconds(activity, dataConf), choice.direction, 0.0, 0.0, 0.0, choice.strength,
        )
        val tilt = RotationTargets.cappedTilt(RotationTargets.tiltDegrees(pressureTrendV, pressureConf, reach, reachConf))
        val wobble = RotationTargets.cappedWobble(
            tilt, RotationTargets.wobbleDegrees(windV, windConf, senders, sendersConf),
        )
        val finalRotation = rotation.copy(tiltDegrees = tilt, wobbleDegrees = wobble)

        val lean = clamp((((link - 0.5) * 2.0) * linkConf + ((warmthV - 0.5) * 2.0) * warmthConf) / 2.0)
        val activityEff = lerp(0.5, activity, dataConf)
        val penta = sendersConf * senders * (1.0 - activityEff)

        // The 5 minute activity is too slow to tell a burst from a lull: the 60 s pace, on the same self-normalized scale, drives quiet and density.
        val pace = if (hasPackets) activityRange.normalize(ln(1.0 + slow.value * 60.0)) else 0.0
        val rateN = pace * dataConf
        val utilN = ((util ?: 0.0) / UTIL_FULL_PCT).coerceIn(0.0, 1.0)
        val density = clamp01(0.5 * rateN + 0.3 * senders * sendersConf + 0.2 * utilN)
        val brightness = link
        val tension = clamp01(0.4 * rateN + 0.3 * utilN + 0.3 * (1.0 - brightness))
        val quiet = !warming && (dataConf <= 0.0 || pace < QUIET_ACTIVITY)

        val tiltSource = if (pressureConf > 0) "pressure trend" else if (reachConf > 0) "reach" else null
        val wobbleSource = if (windConf > 0) "wind" else if (sendersConf > 0) "senders" else null
        val leanSource = if (linkConf > 0 && warmthConf > 0) "link quality and warmth" else if (linkConf > 0) "link quality" else if (warmthConf > 0) "warmth" else null
        val shown = HashMap<String, String>()
        if (dataConf > 0) shown["turn time"] = formatDecimal(activity, 2)
        if (dataConf >= 0.5) shown["phrase speed"] = formatDecimal(activity, 2)
        val directionSignal = when (choice.source) {
            RotationTargets.DirectionSource.Pressure -> pressureTrendV
            RotationTargets.DirectionSource.Temperature -> temperatureTrendV
            RotationTargets.DirectionSource.Battery -> batteryTrendV
            RotationTargets.DirectionSource.Activity -> trend
            RotationTargets.DirectionSource.None -> null
        }
        if (directionSignal != null) shown["rotation direction"] = (if (directionSignal > 0) "+" else "") + formatDecimal(directionSignal, 2)
        if (tiltSource != null) shown["tilt"] = formatDecimal(if (pressureConf > 0) pressureTrendV else reach, 2)
        if (wobbleSource != null) shown["wobble"] = formatDecimal(if (windConf > 0) windV else senders, 2)
        if (leanSource != null) {
            shown["mode lean"] = when {
                linkConf > 0 && warmthConf > 0 -> "${formatDecimal(link, 2)} and ${formatDecimal(warmthV, 2)}"
                linkConf > 0 -> formatDecimal(link, 2)
                else -> formatDecimal(warmthV, 2)
            }
        }
        if (sendersConf > 0) shown["pentatonic bias"] = formatDecimal(senders, 2)

        val params = ParamValues(
            finalRotation.targetPeriodSeconds, finalRotation.direction, tilt, wobble,
            phraseSpeedFor(activity, dataConf), lean, penta.coerceIn(0.0, 1.0),
            ParamDrivers(
                rotationSpeed = if (dataConf > 0) "activity" else null,
                rotationDirection = choice.source.label,
                tilt = tiltSource,
                wobble = wobbleSource,
                phraseSpeed = if (dataConf >= 0.5) "activity" else null,
                modeLean = leanSource,
                scalePentatonicBias = if (sendersConf > 0) "senders" else null,
                readings = shown,
            ),
            choice.strength,
        )
        return MoodSnapshot(
            now, activity, dataConf, reach, reachConf, link, linkConf, senders, sendersConf, warmthV, warmthConf, windV, windConf,
            pressureTrendV, pressureConf, temperatureTrendV, temperatureTrendConf, batteryTrendV, batteryTrendConf, trend, trendConf,
            batteryMoved, quiet, warming, density, brightness, tension,
            finalRotation, params,
        )
    }

    private fun clamp(v: Double) = v.coerceIn(-1.0, 1.0)

    /** Hysteretic tempo band so a value on a threshold does not flicker; neutral 1.0 until activity is trusted. */
    private fun phraseSpeedFor(activity: Double, conf: Double): Double {
        if (conf < 0.5) {
            band = 2
        } else {
            while (band < 3 && activity > BAND_EDGES[band] + BAND_HYST) band++
            while (band > 0 && activity < BAND_EDGES[band - 1] - BAND_HYST) band--
        }
        return BAND_SPEEDS[band]
    }

    private fun activityTrendOf(now: Long, x: Double, activity: Double, conf: Double, hasPackets: Boolean): Pair<Double, Double> {
        if (!hasPackets) return Pair(0.0, 0.0)
        val m = now.floorDiv(60_000L)
        val slot = m.mod(TREND_SLOTS)
        trendX[slot] = x
        trendStamp[slot] = m
        var old = Long.MIN_VALUE
        for (back in TREND_MINUTES..TREND_MINUTES + TREND_SLACK) {
            val s = (m - back).mod(TREND_SLOTS)
            if (trendStamp[s] == m - back) {
                old = m - back
                break
            }
        }
        if (old == Long.MIN_VALUE) return Pair(0.0, 0.0)
        val before = activityRange.normalize(trendX[old.mod(TREND_SLOTS)])
        return Pair((activity - before).coerceIn(-1.0, 1.0), conf)
    }

    private fun refreshSenders(now: Long) {
        if (sendersAt != Long.MIN_VALUE && now - sendersAt < 5_000L) return
        sendersAt = now
        sendersCount = activeNodes(SENDERS_WINDOW_MS)
    }

    /**
     * Each node's change over [TREND_WINDOW_MS] (scaled to exactly that window) for the nodes that reported within
     * [freshMs]; a node with under that much history is left out, and with [skipFlat] so is one whose value did not move.
     * Returns the count; the deltas are in the first entries of [out].
     */
    private fun deltas(map: HashMap<Long, Series>, now: Long, freshMs: Long, skipFlat: Boolean, out: DoubleArray): Int {
        var nd = 0
        for (s in map.values) {
            if (s.at.isEmpty()) continue
            val lastIdx = s.at.lastIndex
            if (now - s.at[lastIdx] > freshMs) continue
            var i = lastIdx - 1
            while (i >= 0 && s.at[lastIdx] - s.at[i] < TREND_WINDOW_MS) i--
            if (i < 0) continue
            val change = s.value[lastIdx] - s.value[i]
            if (skipFlat && change == 0.0) continue
            out[nd++] = change * TREND_WINDOW_MS / (s.at[lastIdx] - s.at[i]).toDouble()
        }
        return nd
    }

    private fun refreshWeather(now: Long) {
        if (weatherAt != Long.MIN_VALUE && now - weatherAt < 30_000L) return
        weatherAt = now

        run {
            val d = DoubleArray(pressure.size)
            val nd = deltas(pressure, now, FRESH_MS, false, d)
            pressureConf = ramp(nd.toDouble(), 3.0)
            pressureTrendV = if (nd > 0) (median(d, nd) / RotationTargets.PRESSURE_FULL_SCALE_HPA).coerceIn(-1.0, 1.0) else 0.0
        }
        run {
            val d = DoubleArray(temperatureSeries.size)
            val n = deltas(temperatureSeries, now, TREND_FRESH_MS, false, d)
            temperatureTrendConf = ramp(n.toDouble(), 3.0)
            temperatureTrendV = if (n > 0) (median(d, n) / RotationTargets.TEMPERATURE_FULL_SCALE_C).coerceIn(-1.0, 1.0) else 0.0
        }
        run {
            val d = DoubleArray(batterySeries.size)
            val n = deltas(batterySeries, now, TREND_FRESH_MS, true, d)
            batteryMoved = n
            batteryTrendConf = ramp(n.toDouble(), 3.0)
            batteryTrendV = if (n > 0) (median(d, n) / RotationTargets.BATTERY_FULL_SCALE_PCT).coerceIn(-1.0, 1.0) else 0.0
        }

        val temps = freshValues(temperature, now, WARMTH_FRESH_MS)
        warmthConf = ramp(temps.size.toDouble(), 2.0)
        if (temps.isNotEmpty()) {
            val med = median(temps, temps.size)
            warmthRange.observe(med, now)
            warmthV = warmthRange.normalize(med)
        } else {
            warmthV = 0.5
        }

        val winds = freshValues(windSpeed, now, FRESH_MS)
        windConf = ramp(winds.size.toDouble(), 2.0)
        windV = if (winds.isNotEmpty()) clamp01(median(winds, winds.size) / RotationTargets.WIND_FULL_SCALE_MPS) else 0.0
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
        const val ACTIVITY_TAU = 300.0
        const val FAST_TAU = 2.0
        const val SLOW_TAU = 60.0
        const val REACH_TAU = 900.0
        const val LINK_TAU = 900.0
        const val MIN_WEIGHT = 1e-6
        const val STALE_GRACE_S = 6.0 * 3600
        const val STALE_TAU_S = 6.0 * 3600
        const val SENDERS_WINDOW_MS = 45L * 60_000
        const val SAMPLE_MS = 5L * 60_000
        const val SERIES_KEEP_MS = 6L * 3_600_000
        const val TREND_WINDOW_MS = 3.0 * 3_600_000
        const val FRESH_MS = 3_600_000L
        const val TREND_FRESH_MS = 2L * 3_600_000
        const val WARMTH_FRESH_MS = 3L * 3_600_000
        const val TREND_SLOTS = 40
        const val TREND_MINUTES = 30
        const val TREND_SLACK = 5

        /** Relative pace under this reads quiet: the middle of the mesh's own 24 h range. */
        const val QUIET_ACTIVITY = 0.5

        /** Channel utilization (percent) that reads fully loaded; firmware itself backs off well below this. */
        const val UTIL_FULL_PCT = 50.0
        const val UTIL_SMOOTHING = 0.2
        val BAND_EDGES = doubleArrayOf(0.2, 0.45, 0.75)
        const val BAND_HYST = 0.04
        val BAND_SPEEDS = doubleArrayOf(0.5, 0.75, 1.0, 1.5)
    }
}
