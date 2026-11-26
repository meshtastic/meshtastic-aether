package org.meshtastic.ambient.music

import kotlin.math.exp

/** Smoothed view of how busy the mesh is; all times are epoch millis from the events themselves. */
class Crowd {
    private var lastMs = Long.MIN_VALUE
    private var fast = 0.0
    private var slow = 0.0
    private var snr: Double? = null
    private var util: Double? = null
    private val heard = HashMap<Long, Long>()

    /** True between `Link(WarmingUp)` and the next link state; ornaments are suppressed meanwhile. */
    var warmingUp = false

    /** Packets per second over about 2 s: what thinning reacts to. */
    val fastRate: Double get() = fast

    /** Packets per second over about a minute: what tension and quiet detection use. */
    val rate: Double get() = slow

    fun advanceTo(ms: Long) {
        if (lastMs == Long.MIN_VALUE) lastMs = ms
        val dt = (ms - lastMs) / 1000.0
        if (dt > 0) {
            fast *= exp(-dt / FAST_TAU)
            slow *= exp(-dt / SLOW_TAU)
            lastMs = ms
        }
    }

    fun onPacket(ms: Long, from: Long, snrDb: Float?) {
        advanceTo(ms)
        fast += 1.0 / FAST_TAU
        slow += 1.0 / SLOW_TAU
        heard[from] = ms
        if (snrDb != null) snr = snr?.let { it + 0.1 * (snrDb - it) } ?: snrDb.toDouble()
    }

    fun onVitals(channelUtilPct: Float?) {
        if (channelUtilPct != null) util = util?.let { it + 0.2 * (channelUtilPct - it) } ?: channelUtilPct.toDouble()
    }

    fun forget(node: Long) {
        heard.remove(node)
    }

    fun activeNodes(nowMs: Long): Int = heard.values.count { nowMs - it <= ACTIVE_MS }

    private val rateN get() = 1 - exp(-slow / 0.5)
    private val snrN get() = ((snr ?: -5.0) + 20.0).div(30.0).coerceIn(0.0, 1.0)
    private val utilN get() = ((util ?: 0.0) / 50.0).coerceIn(0.0, 1.0)

    fun density(nowMs: Long): Double = 0.5 * rateN + 0.3 * (activeNodes(nowMs) / 30.0).coerceAtMost(1.0) + 0.2 * utilN

    val brightness: Double get() = snrN

    val tension: Double get() = 0.4 * rateN + 0.3 * utilN + 0.3 * (1 - snrN)

    private companion object {
        const val FAST_TAU = 2.0
        const val SLOW_TAU = 60.0
        const val ACTIVE_MS = 15L * 60 * 1000
    }
}
