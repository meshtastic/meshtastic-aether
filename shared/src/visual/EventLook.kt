package org.meshtastic.aether.visual

import org.meshtastic.aether.mesh.PacketKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10

/** How a ripple's ring is drawn. */
enum class RingStyle { Solid, Dashed }

/**
 * The per-event visual attributes of the mesh-driven design (Layer 1, visual column) as plain bounded functions.
 * Every one returns its neutral value when the attribute is absent.
 */
object EventLook {
    private val INFRASTRUCTURE = setOf("ROUTER", "ROUTER_CLIENT", "ROUTER_LATE", "REPEATER")
    private val MOBILE = setOf("TRACKER", "TAK_TRACKER", "TAK", "LOST_AND_FOUND")

    /** Core radius multiplier: routers and repeaters larger, trackers smaller, everything else and unknown 1.0. */
    fun coreSize(role: String?): Double = when (role?.uppercase()) {
        in INFRASTRUCTURE -> 1.35
        in MOBILE -> 0.75
        else -> 1.0
    }

    /** Core opacity multiplier: a soft core for infrastructure, a crisp (full) one otherwise. */
    fun coreAlpha(role: String?): Double = if (role?.uppercase() in INFRASTRUCTURE) 0.8 else 1.0

    /** Halo tint offset in whole degrees, -8..8, a stable hash of the hardware model name; 0 when unknown. */
    fun hardwareHueDegrees(hardware: String?): Int {
        if (hardware.isNullOrEmpty()) return 0
        var h = 2166136261L
        for (ch in hardware) h = ((h xor ch.code.toLong()) * 16777619L) and 0xFFFFFFFFL
        return (h % 17L).toInt() - 8
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

    /** Layout z offset for [altitudeM] relative to [referenceM]: higher ground sits nearer the viewer; 0 when unknown. */
    fun altitudeLift(altitudeM: Int?, referenceM: Int?): Double {
        if (altitudeM == null) return 0.0
        val rel = (altitudeM - (referenceM ?: 0)) / ALTITUDE_FULL_M
        return -MAX_ALTITUDE_LIFT * rel.coerceIn(-1.0, 1.0)
    }

    /** At or above this battery percent an orb does not flicker. */
    const val FLICKER_BELOW_PCT = 30

    /** A flicker never runs faster than this. */
    const val MAX_FLICKER_HZ = 2.0

    /** The deepest dip of a flicker, as a fraction of the orb's brightness. */
    const val MAX_FLICKER_DEPTH = 0.12

    private fun lowness(batteryPct: Int?): Double {
        if (batteryPct == null || batteryPct >= FLICKER_BELOW_PCT) return 0.0
        return (1.0 - batteryPct.coerceAtLeast(0).toDouble() / FLICKER_BELOW_PCT).coerceIn(0.0, 1.0)
    }

    /** Flicker rate in Hz: 0 at 30 percent and above (and on external power or unknown), rising to 2 Hz at empty. */
    fun flickerHz(batteryPct: Int?): Double = MAX_FLICKER_HZ * lowness(batteryPct)

    /** Flicker depth 0..0.12, growing with how low the battery is. */
    fun flickerDepth(batteryPct: Int?): Double = MAX_FLICKER_DEPTH * lowness(batteryPct)

    /** Brightness multiplier 1-depth..1 at flicker [phase] (radians): a smooth cosine dip, never a flash. */
    fun flickerFactor(batteryPct: Int?, phase: Double): Double =
        1.0 - flickerDepth(batteryPct) * (0.5 - 0.5 * cos(phase))

    const val BRIGHTNESS_MIN = 0.85
    const val BRIGHTNESS_MAX = 1.15
    private const val VOLTS_LOW = 3.3
    private const val VOLTS_HIGH = 4.2

    /** Orb brightness 0.85..1.15: external power is the top, else voltage from 3.3 V (dim) to 4.2 V (bright); 1.0 when unknown. */
    fun brightness(voltage: Float?, batteryPct: Int?): Double {
        if (batteryPct != null && batteryPct > 100) return BRIGHTNESS_MAX
        if (voltage == null || !voltage.isFinite() || voltage <= 0f) return 1.0
        val t = ((voltage - VOLTS_LOW) / (VOLTS_HIGH - VOLTS_LOW)).coerceIn(0.0, 1.0)
        return BRIGHTNESS_MIN + (BRIGHTNESS_MAX - BRIGHTNESS_MIN) * t
    }

    private const val UPTIME_LOW_S = 3600.0
    private const val UPTIME_HIGH_S = 30.0 * 86400

    /** Halo size multiplier 0.9..1.1: log of uptime from one hour (small) to thirty days (large); 1.0 when unknown. */
    fun haloScale(uptimeSeconds: Long?): Double {
        if (uptimeSeconds == null) return 1.0
        val x = ((log10(uptimeSeconds.coerceAtLeast(1L).toDouble()) - log10(UPTIME_LOW_S)) /
            (log10(UPTIME_HIGH_S) - log10(UPTIME_LOW_S))).coerceIn(0.0, 1.0)
        return 0.9 + 0.2 * x
    }

    /** The ripple width in dp before the fading term: texts are heavier. */
    fun kindWidthDp(kind: PacketKind): Double = if (kind == PacketKind.Text) 2.5 else 1.5

    /** Path-like packets draw a dashed ring; everything else is solid. */
    fun ringStyle(kind: PacketKind): RingStyle = when (kind) {
        PacketKind.Routing, PacketKind.Traceroute, PacketKind.NeighborInfo -> RingStyle.Dashed
        else -> RingStyle.Solid
    }

    const val MAX_PAYLOAD_BYTES = 233

    /** Ripple width multiplier 0.85..1.2 from payload size; 1.0 when unknown. */
    fun payloadWidthTrim(payloadBytes: Int?): Double {
        if (payloadBytes == null) return 1.0
        return 0.85 + 0.35 * (payloadBytes.toDouble() / MAX_PAYLOAD_BYTES).coerceIn(0.0, 1.0)
    }

    /** Rings of a text ripple from its length: 1 up to 40 characters, 2 up to 100, else 3; always 1 for other kinds. */
    fun ringCount(kind: PacketKind, textLength: Int?): Int = when {
        kind != PacketKind.Text || textLength == null || textLength <= 40 -> 1
        textLength <= 100 -> 2
        else -> 3
    }

    /** Hue offset in whole degrees for a channel index: 3 degrees per index up to 7; 0 for channel 0 or unknown. */
    fun channelHueDegrees(channel: Int?): Int = (channel ?: 0).coerceIn(0, 7) * 3

    /** How far a ripple that came through MQTT is pulled toward the cool tint. */
    const val MQTT_COOL_MIX = 0.35f

    fun mqttMix(viaMqtt: Boolean): Float = if (viaMqtt) MQTT_COOL_MIX else 0f

    /** The ripple stroke width in dp: the kind's base times the payload [trim] plus a thinning term that shrinks over [progress] 0..1. */
    fun rippleWidthDp(kind: PacketKind, trim: Double, progress: Double): Double =
        kindWidthDp(kind) * trim + 2.0 * (1.0 - progress)

    /** Advances a flicker phase by [dtSeconds] at [hz], wrapped to 0..2 pi. */
    fun advanceFlicker(phase: Double, hz: Double, dtSeconds: Double): Double =
        (phase + 2 * PI * hz * dtSeconds).mod(2 * PI)
}
