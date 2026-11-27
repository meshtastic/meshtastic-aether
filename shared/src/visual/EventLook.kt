package org.meshtastic.aether.visual

import kotlin.math.log10

/**
 * The per-node visual attributes of the mesh-driven design (Layer 1, visual column) as plain bounded functions: core,
 * tint, depth, brightness and halo. Every one returns its neutral value when the attribute is absent. The battery
 * flicker is [BatteryFlicker] and the per-packet ripple is [RippleLook].
 */
object EventLook {
    private val INFRASTRUCTURE = setOf("ROUTER", "ROUTER_CLIENT", "ROUTER_LATE", "REPEATER")
    private val MOBILE = setOf("TRACKER", "TAK_TRACKER", "TAK", "LOST_AND_FOUND")
    private const val INFRASTRUCTURE_CORE_SIZE = 1.35
    private const val MOBILE_CORE_SIZE = 0.75
    private const val INFRASTRUCTURE_CORE_ALPHA = 0.8

    /** Core radius multiplier: routers and repeaters larger, trackers smaller, everything else and unknown 1.0. */
    fun coreSize(role: String?): Double = when (role?.uppercase()) {
        in INFRASTRUCTURE -> INFRASTRUCTURE_CORE_SIZE
        in MOBILE -> MOBILE_CORE_SIZE
        else -> 1.0
    }

    /** Core opacity multiplier: a soft core for infrastructure, a crisp (full) one otherwise. */
    fun coreAlpha(role: String?): Double = if (role?.uppercase() in INFRASTRUCTURE) INFRASTRUCTURE_CORE_ALPHA else 1.0

    // 32-bit FNV-1a.
    private const val FNV_OFFSET_BASIS = 2166136261L
    private const val FNV_PRIME = 16777619L
    private const val UINT32_MASK = 0xFFFFFFFFL
    private const val MAX_HARDWARE_HUE_DEG = 8
    private const val HARDWARE_HUE_STEPS = 2L * MAX_HARDWARE_HUE_DEG + 1

    /** Halo tint offset in whole degrees, -8..8, a stable hash of the hardware model name; 0 when unknown. */
    fun hardwareHueDegrees(hardware: String?): Int {
        if (hardware.isNullOrEmpty()) return 0
        var h = FNV_OFFSET_BASIS
        for (ch in hardware) h = ((h xor ch.code.toLong()) * FNV_PRIME) and UINT32_MASK
        return (h % HARDWARE_HUE_STEPS).toInt() - MAX_HARDWARE_HUE_DEG
    }

    /** RSSI at or below this reads as far and gets no bias. */
    const val RSSI_FAR_DBM = -120

    /** RSSI at or above this reads as nearest. */
    const val RSSI_NEAR_DBM = -30

    /** The most an orb is pulled toward the camera by signal strength, in layout z units. */
    const val MAX_RSSI_BIAS = 0.15

    /** Layout z offset (negative is toward the camera) for a strong signal; 0 when unknown or weak. */
    fun rssiDepthBias(rssi: Double?): Double {
        if (rssi == null || !rssi.isFinite()) return 0.0
        val t = ((rssi - RSSI_FAR_DBM) / (RSSI_NEAR_DBM - RSSI_FAR_DBM)).coerceIn(0.0, 1.0)
        return -MAX_RSSI_BIAS * t
    }

    /** Altitude above our own (or sea level) that gives the full lift, in meters. */
    const val ALTITUDE_FULL_M = 300.0

    /** The most high ground is lifted toward the camera, in layout z units. */
    const val MAX_ALTITUDE_LIFT = 0.2

    /**
     * Layout z offset for [altitudeM] relative to [referenceM]: higher ground sits nearer the viewer; 0 when unknown.
     */
    fun altitudeLift(altitudeM: Int?, referenceM: Int?): Double {
        if (altitudeM == null) return 0.0
        val rel = (altitudeM - (referenceM ?: 0)) / ALTITUDE_FULL_M
        return -MAX_ALTITUDE_LIFT * rel.coerceIn(-1.0, 1.0)
    }

    const val BRIGHTNESS_MIN = 0.85
    const val BRIGHTNESS_MAX = 1.15
    private const val VOLTS_LOW = 3.3
    private const val VOLTS_HIGH = 4.2

    /** Meshtastic reports a battery level above 100 percent (101) for external power. */
    private const val FULL_BATTERY_PCT = 100

    /**
     * Orb brightness 0.85..1.15: external power is the top, else voltage from 3.3 V (dim) to 4.2 V (bright); 1.0 when
     * unknown.
     */
    fun brightness(voltage: Float?, batteryPct: Int?): Double = when {
        batteryPct != null && batteryPct > FULL_BATTERY_PCT -> BRIGHTNESS_MAX
        voltage == null || !voltage.isFinite() || voltage <= 0f -> 1.0
        else -> BRIGHTNESS_MIN + (BRIGHTNESS_MAX - BRIGHTNESS_MIN) * voltageLevel(voltage)
    }

    private fun voltageLevel(voltage: Float): Double =
        ((voltage - VOLTS_LOW) / (VOLTS_HIGH - VOLTS_LOW)).coerceIn(0.0, 1.0)

    private const val UPTIME_LOW_S = 3600.0
    private const val UPTIME_HIGH_S = 30.0 * 86400
    private const val HALO_SCALE_MIN = 0.9
    private const val HALO_SCALE_RANGE = 0.2

    /** Halo size multiplier 0.9..1.1: log of uptime from one hour (small) to thirty days (large); 1.0 when unknown. */
    fun haloScale(uptimeSeconds: Long?): Double {
        if (uptimeSeconds == null) return 1.0
        val x = ((log10(uptimeSeconds.coerceAtLeast(1L).toDouble()) - log10(UPTIME_LOW_S)) /
            (log10(UPTIME_HIGH_S) - log10(UPTIME_LOW_S))).coerceIn(0.0, 1.0)
        return HALO_SCALE_MIN + HALO_SCALE_RANGE * x
    }
}
