package org.meshtastic.aether.mood

import kotlin.math.exp
import kotlin.math.ln
import org.meshtastic.aether.mesh.LinkState
import org.meshtastic.aether.mesh.MeshEvent

/**
 * The mood signals at one instant. Each signal is 0..1 with a confidence 0..1; a signal with no data holds its neutral
 * (0.5) and confidence 0. The trends are -1..1: [pressureTrend], [temperatureTrend] and [batteryTrend] are per-node
 * 3 h changes (median) as a fraction of a full-scale change, [activityTrend] is the change of relative activity over
 * 30 minutes.
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

/** A signal at or above this confidence is trusted enough to change the phrase speed. */
internal const val TRUSTED_CONFIDENCE = 0.5

/** Three distinct nodes (or senders) behind a signal make it full confidence. */
internal const val FULL_CONFIDENCE_NODES = 3.0

/** Channel utilization (percent) that reads fully loaded; firmware itself backs off well below this. */
internal const val UTIL_FULL_PCT = 50.0

/**
 * Folds mesh events into slow, self-normalized signals and the rotation and parameter values derived from them, plus
 * the short-window pace the composer thins and gates by. Time is the events' own epoch millis; [advance] moves the
 * clock and reads the snapshot. While the link is warming up, packets feed nothing (a connect burst is not the mesh's
 * pace). Single-threaded.
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

    private val activityRange = RollingRange(
        minSpan = 0.5,
        defaultLo = 0.0,
        defaultHi = ln(1.0 + BUSY_PACKETS_PER_MINUTE),
    )
    private val reachRange = RollingRange(minSpan = 1.0, defaultLo = 0.0, defaultHi = MAX_HOPS.toDouble())
    private val snrRange = RollingRange(minSpan = 4.0, defaultLo = -20.0, defaultHi = 10.0)
    private val sendersRange = RollingRange(minSpan = 0.7, defaultLo = 0.0, defaultHi = ln(1.0 + BUSY_SENDERS))

    private val activityTrend = ActivityTrend()
    private val readings = NodeReadings()
    private val phraseBand = PhraseBand()
    private var sendersCount = 0
    private var sendersAt = Long.MIN_VALUE

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
                readings.forget(e.node)
            }
            is MeshEvent.Environment -> {
                sync(e.atMillis)
                readings.onEnvironment(e)
            }
            is MeshEvent.Health -> {
                sync(e.atMillis)
                readings.onHealth(e)
            }
            is MeshEvent.Vitals -> {
                sync(e.atMillis)
                onVitals(e)
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
            hopsSum.add(it.coerceIn(0, MAX_HOPS).toDouble())
            hopsWeight.add(1.0)
        }
        e.snr?.let {
            if (it.isFinite()) {
                snrSum.add(it.toDouble().coerceIn(SNR_FLOOR_DB, SNR_CEILING_DB))
                snrWeight.add(1.0)
            }
        }
    }

    private fun onVitals(e: MeshEvent.Vitals) {
        val pct = e.channelUtilPct ?: return
        if (pct.isFinite()) util = util?.let { u -> u + UTIL_SMOOTHING * (pct - u) } ?: pct.toDouble()
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

    /**
     * Moves the clock to [nowMillis] (never backwards) and returns the signals, rotation target and parameters at that
     * time.
     */
    fun advance(nowMillis: Long): MoodSnapshot {
        sync(nowMillis)
        val now = clock
        val s = readSignals(now)

        val choice =
            RotationTargets.chooseDirection(s.pressureTrend, s.temperatureTrend, s.batteryTrend, s.activityTrend)
        val tilt = RotationTargets.cappedTilt(
            RotationTargets.tiltDegrees(
                s.pressureTrend.value, s.pressureTrend.confidence, s.reach.value, s.reach.confidence,
            ),
        )
        val wobble = RotationTargets.cappedWobble(tilt, RotationTargets.wobbleDegrees(s.wind, s.senders, s.channel))
        val rotation = RotationTarget(
            targetPeriodSeconds = RotationTargets.periodSeconds(s.activity.value, s.activity.confidence),
            direction = choice.direction,
            tiltDegrees = tilt,
            wobbleDegrees = wobble,
            impulse = 0.0,
            directionStrength = choice.strength,
        )

        // The 5 minute activity is too slow to tell a burst from a lull: the 60 s pace, on the same self-normalized
        // scale, drives quiet and density.
        val pace = if (packets > 0) activityRange.normalize(ln(1.0 + slow.value * SECONDS_PER_MINUTE)) else 0.0
        val rateN = pace * s.activity.confidence
        val utilN = ((util ?: 0.0) / UTIL_FULL_PCT).coerceIn(0.0, 1.0)
        val brightness = s.link.value
        return MoodSnapshot(
            now, s.activity.value, s.activity.confidence, s.reach.value, s.reach.confidence,
            s.link.value, s.link.confidence, s.senders.value, s.senders.confidence,
            s.warmth.value, s.warmth.confidence, s.wind.value, s.wind.confidence,
            s.pressureTrend.value, s.pressureTrend.confidence, s.temperatureTrend.value, s.temperatureTrend.confidence,
            s.batteryTrend.value, s.batteryTrend.confidence, s.activityTrend.value, s.activityTrend.confidence,
            batteryMovedNodes = readings.batteryMoved,
            quiet = !warming && (s.activity.confidence <= 0.0 || pace < QUIET_ACTIVITY),
            warmingUp = warming,
            density = clamp01(
                DENSITY_PACE * rateN + DENSITY_SENDERS * s.senders.value * s.senders.confidence + DENSITY_UTIL * utilN,
            ),
            brightness = brightness,
            tension = clamp01(TENSION_PACE * rateN + TENSION_UTIL * utilN + TENSION_DARK * (1.0 - brightness)),
            rotation = rotation,
            params = paramsFor(s, choice, rotation),
        )
    }

    private fun readSignals(now: Long): MoodSignals {
        val hasPackets = packets > 0
        val x = ln(1.0 + rate.value * SECONDS_PER_MINUTE)
        val confidence = dataConfidence(now)
        var activity = MoodSignal.NEUTRAL.value
        if (hasPackets) {
            activityRange.observe(x, now)
            activity = activityRange.normalize(x)
        }
        val before = if (hasPackets) activityTrend.record(now, x) else null
        val trend = if (before == null) {
            MoodSignal.FLAT
        } else {
            MoodSignal((activity - activityRange.normalize(before)).coerceIn(-1.0, 1.0), confidence)
        }
        val reach = relativeMean(hopsSum, hopsWeight, reachRange, now)
        val link = relativeMean(snrSum, snrWeight, snrRange, now)
        val senders = sendersSignal(now)
        readings.refresh(now)
        return MoodSignals(
            activity = MoodSignal(activity, confidence),
            activityTrend = trend,
            reach = reach,
            link = link,
            senders = senders,
            warmth = readings.warmth,
            wind = readings.wind,
            channel = readings.channel,
            pressureTrend = readings.pressureTrend,
            temperatureTrend = readings.temperatureTrend,
            batteryTrend = readings.batteryTrend,
        )
    }

    /** Trust in the packet signals: ramps with packets heard, fades once the last one is hours old. */
    private fun dataConfidence(now: Long): Double {
        if (packets <= 0) return 0.0
        val sinceLast = (now - lastPacketMs).coerceAtLeast(0L) / MS_PER_SECOND
        return ramp(packets.toDouble(), FULL_CONFIDENCE_PACKETS) *
            exp(-maxOf(0.0, sinceLast - STALE_GRACE_S) / STALE_TAU_S)
    }

    /** Distinct recent senders (recounted at most every 5 s) against their own range. */
    private fun sendersSignal(now: Long): MoodSignal {
        if (sendersAt == Long.MIN_VALUE || now - sendersAt >= SENDERS_REFRESH_MS) {
            sendersAt = now
            sendersCount = activeNodes(SENDERS_WINDOW_MS)
        }
        val confidence = ramp(sendersCount.toDouble(), FULL_CONFIDENCE_NODES)
        if (sendersCount <= 0) return MoodSignal(MoodSignal.NEUTRAL.value, confidence)
        val sx = ln(1.0 + sendersCount)
        sendersRange.observe(sx, now)
        return MoodSignal(sendersRange.normalize(sx), confidence)
    }

    private fun paramsFor(
        s: MoodSignals,
        choice: RotationTargets.DirectionChoice,
        rotation: RotationTarget,
    ): ParamValues {
        val lean = ((centered(s.link.value) * s.link.confidence + centered(s.warmth.value) * s.warmth.confidence) / 2.0)
            .coerceIn(-1.0, 1.0)
        val activityEff = lerp(MoodSignal.NEUTRAL.value, s.activity.value, s.activity.confidence)
        val penta = s.senders.confidence * s.senders.value * (1.0 - activityEff)
        val gravity = ParamValues.NEUTRAL_GRAVITY + (s.link.value - MoodSignal.NEUTRAL.value) * s.link.confidence
        return ParamValues(
            rotationPeriodSeconds = rotation.targetPeriodSeconds,
            rotationDirection = rotation.direction,
            tiltDegrees = rotation.tiltDegrees,
            wobbleDegrees = rotation.wobbleDegrees,
            phraseSpeed = phraseBand.speedFor(s.activity.value, s.activity.confidence),
            modeLean = lean,
            scalePentatonicBias = penta.coerceIn(0.0, 1.0),
            drivers = driversFor(s, choice),
            directionStrength = choice.strength,
            gravity = gravity.coerceIn(0.0, 1.0),
        )
    }

    private companion object {
        const val ACTIVITY_TAU = 300.0
        const val FAST_TAU = 2.0
        const val SLOW_TAU = 60.0
        const val REACH_TAU = 900.0
        const val LINK_TAU = 900.0
        const val FULL_CONFIDENCE_PACKETS = 10.0
        const val STALE_GRACE_S = 6.0 * 3600
        const val STALE_TAU_S = 6.0 * 3600
        const val SENDERS_WINDOW_MS = 45L * 60_000
        const val SENDERS_REFRESH_MS = 5_000L
        const val MS_PER_SECOND = 1000.0
        const val SECONDS_PER_MINUTE = 60.0

        /** Default ranges before the mesh's own history: 20 packets a minute and 30 senders read as busy. */
        const val BUSY_PACKETS_PER_MINUTE = 20.0
        const val BUSY_SENDERS = 30.0

        /** Firmware's hop limit ceiling. */
        const val MAX_HOPS = 7
        const val SNR_FLOOR_DB = -30.0
        const val SNR_CEILING_DB = 20.0

        /** Relative pace under this reads quiet: the middle of the mesh's own 24 h range. */
        const val QUIET_ACTIVITY = 0.5
        const val UTIL_SMOOTHING = 0.2

        /** Blend weights of density and tension; each set sums to 1. */
        const val DENSITY_PACE = 0.5
        const val DENSITY_SENDERS = 0.3
        const val DENSITY_UTIL = 0.2
        const val TENSION_PACE = 0.4
        const val TENSION_UTIL = 0.3
        const val TENSION_DARK = 0.3
    }
}

private const val MIN_WEIGHT = 1e-6

/** A decayed packet weight past this moves the reach and link ranges. */
private const val RANGE_MIN_WEIGHT = 0.5
private const val FULL_CONFIDENCE_WEIGHT = 3.0

/** The decayed mean [sum] / [weight] against [range]; neutral while the weight is negligible. */
private fun relativeMean(sum: Decay, weight: Decay, range: RollingRange, now: Long): MoodSignal {
    val w = weight.value
    if (w < MIN_WEIGHT) return MoodSignal.NEUTRAL
    val mean = sum.value / w
    if (w >= RANGE_MIN_WEIGHT) range.observe(mean, now)
    return MoodSignal(range.normalize(mean), ramp(w, FULL_CONFIDENCE_WEIGHT))
}

/** Log activity stamped once a minute in a ring, so the value about [TREND_MINUTES] back can be read. */
private class ActivityTrend {
    private val x = DoubleArray(TREND_SLOTS)
    private val stamp = LongArray(TREND_SLOTS) { Long.MIN_VALUE }

    /**
     * Stores [value] for the minute of [now] and returns the one stored [TREND_MINUTES] to [TREND_MINUTES] plus
     * [TREND_SLACK] minutes back, nearest first; null when none was.
     */
    fun record(now: Long, value: Double): Double? {
        val m = now.floorDiv(MS_PER_MINUTE)
        val slot = m.mod(TREND_SLOTS)
        x[slot] = value
        stamp[slot] = m
        for (back in TREND_MINUTES..TREND_MINUTES + TREND_SLACK) {
            val s = (m - back).mod(TREND_SLOTS)
            if (stamp[s] == m - back) return x[s]
        }
        return null
    }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
        const val TREND_SLOTS = 40
        const val TREND_MINUTES = 30
        const val TREND_SLACK = 5
    }
}

/** Hysteretic tempo band so a value on a threshold does not flicker; neutral 1.0 until activity is trusted. */
private class PhraseBand {
    private var band = NEUTRAL_BAND

    fun speedFor(activity: Double, confidence: Double): Double {
        if (confidence < TRUSTED_CONFIDENCE) {
            band = NEUTRAL_BAND
        } else {
            while (band < BAND_EDGES.size && activity > BAND_EDGES[band] + BAND_HYST) band++
            while (band > 0 && activity < BAND_EDGES[band - 1] - BAND_HYST) band--
        }
        return BAND_SPEEDS[band]
    }

    private companion object {
        val BAND_EDGES = doubleArrayOf(0.2, 0.45, 0.75)
        const val BAND_HYST = 0.04
        val BAND_SPEEDS = doubleArrayOf(0.5, 0.75, 1.0, 1.5)

        /** The band whose speed is 1.0. */
        const val NEUTRAL_BAND = 2
    }
}
