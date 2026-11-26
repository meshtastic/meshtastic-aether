package org.meshtastic.ambient.mood

import kotlin.math.exp
import kotlin.math.ln
import org.meshtastic.ambient.mesh.LinkState
import org.meshtastic.ambient.mesh.MeshEvent
import org.meshtastic.ambient.mesh.PacketKind

/**
 * The pass-1 signals at one instant. Each signal is 0..1 with a confidence 0..1; a signal with no data holds its neutral
 * (0.5, or 0 for surge, chat and arrival) and confidence 0. [pressureTrend] and [activityTrend] are -1..1.
 */
data class MoodSnapshot(
    val nowMillis: Long,
    /** Packets per minute, smoothed (tau 5 min), for display. */
    val packetsPerMinute: Double,
    val activity: Double, val activityConfidence: Double,
    val surge: Double, val surgeConfidence: Double,
    val silence: Double, val silenceConfidence: Double,
    val reach: Double, val reachConfidence: Double,
    val linkQuality: Double, val linkQualityConfidence: Double,
    val senders: Double, val sendersConfidence: Double,
    val chat: Double, val chatConfidence: Double,
    val arrival: Double, val arrivalConfidence: Double,
    /** Median temperature over reporting nodes against its own 24 h range. */
    val warmth: Double, val warmthConfidence: Double,
    /** Median wind speed as a fraction of 15 m/s. */
    val wind: Double, val windConfidence: Double,
    /** Median over nodes of the pressure change over 3 h, as a fraction of 6 hPa, signed. */
    val pressureTrend: Double, val pressureTrendConfidence: Double,
    /** Change of relative activity over the last 30 minutes, signed. */
    val activityTrend: Double, val activityTrendConfidence: Double,
    val rotation: RotationTarget,
    val params: ParamValues,
) {
    companion object {
        val NEUTRAL = MoodSnapshot(
            0L, 0.0, 0.5, 0.0, 0.0, 0.0, 0.5, 0.0, 0.5, 0.0, 0.5, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, RotationTarget.NEUTRAL, ParamValues.NEUTRAL,
        )
    }
}

/**
 * Folds mesh events into slow, self-normalized signals and the rotation and parameter values derived from them.
 * Time is the events' own epoch millis; [advance] moves the clock and reads the snapshot. Single-threaded.
 */
class MeshMood {
    private var clock = Long.MIN_VALUE
    private val rate = Decay(ACTIVITY_TAU)
    private val surgeAcc = Decay(SURGE_TAU)
    private val chatAcc = Decay(CHAT_TAU)
    private val arrivalAcc = Decay(ARRIVAL_TAU)
    private val hopsSum = Decay(REACH_TAU)
    private val hopsWeight = Decay(REACH_TAU)
    private val snrSum = Decay(LINK_TAU)
    private val snrWeight = Decay(LINK_TAU)
    private var packets = 0L
    private var lastPacketMs = Long.MIN_VALUE
    private val lastHeard = HashMap<Long, Long>()
    private val known = HashSet<Long>()
    private var warming = false

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
    private val temperature = HashMap<Long, Reading>()
    private val windSpeed = HashMap<Long, Reading>()
    private var weatherAt = Long.MIN_VALUE
    private var pressureTrendV = 0.0
    private var pressureConf = 0.0
    private var warmthV = 0.5
    private var warmthConf = 0.0
    private var windV = 0.0
    private var windConf = 0.0
    private var sendersCount = 0
    private var sendersAt = Long.MIN_VALUE
    private var band = 2

    fun onEvent(e: MeshEvent) {
        when (e) {
            is MeshEvent.PacketHeard -> {
                sync(e.atMillis)
                onPacket(e)
            }
            is MeshEvent.NodeSeen -> {
                sync(e.atMillis)
                if (known.add(e.node) && e.firstThisSession && !warming) arrivalAcc.add(1.0)
            }
            is MeshEvent.NodeGone -> {
                sync(e.atMillis)
                lastHeard.remove(e.node)
                pressure.remove(e.node)
                temperature.remove(e.node)
                windSpeed.remove(e.node)
            }
            is MeshEvent.Environment -> {
                sync(e.atMillis)
                onEnvironment(e)
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
        surgeAcc.add(SURGE_STEP)
        lastHeard[e.from] = e.atMillis
        if (known.add(e.from) && !warming) arrivalAcc.add(1.0)
        if (e.kind == PacketKind.Text || e.toUs) chatAcc.add(1.0)
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
        e.pressureHpa?.let { p ->
            if (p.isFinite() && p in 800f..1100f) {
                val s = pressure.getOrPut(e.node) { Series() }
                if (s.at.isNotEmpty() && e.atMillis - s.at.last() < SAMPLE_MS) {
                    s.value[s.value.lastIndex] = p.toDouble()
                } else {
                    s.at.addLast(e.atMillis)
                    s.value.addLast(p.toDouble())
                }
                while (s.at.isNotEmpty() && s.at.first() < e.atMillis - PRESSURE_KEEP_MS) {
                    s.at.removeFirst()
                    s.value.removeFirst()
                }
            }
        }
        e.temperatureC?.let { if (it.isFinite() && it in -60f..70f) record(temperature, e.node, it.toDouble(), e.atMillis) }
        e.windMps?.let { if (it.isFinite() && it in 0f..100f) record(windSpeed, e.node, it.toDouble(), e.atMillis) }
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
        surgeAcc.advance(ms)
        chatAcc.advance(ms)
        arrivalAcc.advance(ms)
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

        val surgeConf = if (hasPackets) ramp(packets.toDouble(), 3.0) else 0.0
        val surge = 1.0 - exp(-surgeAcc.value)
        val silenceConf = surgeConf
        val silence = if (hasPackets) clamp01(ln(maxOf(sinceLast, 10.0) / 10.0) / ln(60.0)) else 0.5

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

        val chat = 1.0 - exp(-chatAcc.value / 2.0)
        val arrival = 1.0 - exp(-arrivalAcc.value / 2.0)

        refreshWeather(now)

        val rotation = RotationTarget(
            RotationTargets.periodSeconds(activity, dataConf),
            RotationTargets.direction(pressureTrendV, pressureConf, trend, trendConf),
            0.0, 0.0, surge * surgeConf,
            RotationTargets.directionStrength(pressureTrendV, pressureConf, trend, trendConf),
        )
        val tilt = RotationTargets.cappedTilt(RotationTargets.tiltDegrees(pressureTrendV, pressureConf, reach, reachConf))
        val wobble = RotationTargets.cappedWobble(tilt, RotationTargets.wobbleDegrees(windV, windConf))
        val finalRotation = rotation.copy(tiltDegrees = tilt, wobbleDegrees = wobble)

        val lean = clamp((((link - 0.5) * 2.0) * linkConf + ((warmthV - 0.5) * 2.0) * warmthConf) / 2.0)
        val activityEff = lerp(0.5, activity, dataConf)
        val penta = sendersConf * senders * (1.0 - activityEff)

        val params = ParamValues(
            finalRotation.targetPeriodSeconds, finalRotation.direction, tilt, wobble,
            phraseSpeedFor(activity, dataConf), lean, penta.coerceIn(0.0, 1.0),
            ParamDrivers(
                rotationSpeed = if (dataConf > 0) "activity" else null,
                rotationDirection = if (pressureConf > 0) "pressure trend" else if (trendConf > 0) "activity trend" else null,
                tilt = if (pressureConf > 0) "pressure trend" else if (reachConf > 0) "reach" else null,
                wobble = if (windConf > 0) "wind" else null,
                phraseSpeed = if (dataConf >= 0.5) "activity" else null,
                modeLean = if (linkConf > 0 && warmthConf > 0) "link quality and warmth" else if (linkConf > 0) "link quality" else if (warmthConf > 0) "warmth" else null,
                scalePentatonicBias = if (sendersConf > 0) "senders" else null,
            ),
        )
        return MoodSnapshot(
            now, ratePm, activity, dataConf, surge, surgeConf, silence, silenceConf, reach, reachConf, link, linkConf,
            senders, sendersConf, chat, dataConf, arrival, dataConf, warmthV, warmthConf, windV, windConf,
            pressureTrendV, pressureConf, trend, trendConf,
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
        var n = 0
        val it = lastHeard.values.iterator()
        while (it.hasNext()) if (now - it.next() <= SENDERS_WINDOW_MS) n++
        sendersCount = n
    }

    private fun refreshWeather(now: Long) {
        if (weatherAt != Long.MIN_VALUE && now - weatherAt < 30_000L) return
        weatherAt = now

        val deltas = DoubleArray(pressure.size)
        var nd = 0
        for (s in pressure.values) {
            if (s.at.isEmpty()) continue
            val lastIdx = s.at.lastIndex
            if (now - s.at[lastIdx] > FRESH_MS) continue
            var i = lastIdx - 1
            while (i >= 0 && s.at[lastIdx] - s.at[i] < TREND_WINDOW_MS) i--
            if (i < 0) continue
            deltas[nd++] = (s.value[lastIdx] - s.value[i]) * TREND_WINDOW_MS / (s.at[lastIdx] - s.at[i]).toDouble()
        }
        pressureConf = ramp(nd.toDouble(), 3.0)
        pressureTrendV = if (nd > 0) (median(deltas, nd) / RotationTargets.PRESSURE_FULL_SCALE_HPA).coerceIn(-1.0, 1.0) else 0.0

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
        const val SURGE_TAU = 20.0
        const val SURGE_STEP = 0.3
        const val CHAT_TAU = 600.0
        const val ARRIVAL_TAU = 900.0
        const val REACH_TAU = 900.0
        const val LINK_TAU = 900.0
        const val MIN_WEIGHT = 1e-6
        const val STALE_GRACE_S = 6.0 * 3600
        const val STALE_TAU_S = 6.0 * 3600
        const val SENDERS_WINDOW_MS = 45L * 60_000
        const val SAMPLE_MS = 5L * 60_000
        const val PRESSURE_KEEP_MS = 6L * 3_600_000
        const val TREND_WINDOW_MS = 3.0 * 3_600_000
        const val FRESH_MS = 3_600_000L
        const val WARMTH_FRESH_MS = 3L * 3_600_000
        const val TREND_SLOTS = 40
        const val TREND_MINUTES = 30
        const val TREND_SLACK = 5
        val BAND_EDGES = doubleArrayOf(0.2, 0.45, 0.75)
        const val BAND_HYST = 0.04
        val BAND_SPEEDS = doubleArrayOf(0.5, 0.75, 1.0, 1.5)
    }
}
