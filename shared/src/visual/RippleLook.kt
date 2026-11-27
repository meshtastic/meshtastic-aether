package org.meshtastic.aether.visual

import org.meshtastic.aether.mesh.PacketKind

/** How a ripple's ring is drawn. */
enum class RingStyle { Solid, Dashed }

/**
 * The per-packet ripple attributes of the mesh-driven design (Layer 1, visual column) as plain bounded functions.
 * Every one returns its neutral value when the attribute is absent.
 */
object RippleLook {
    private const val TEXT_WIDTH_DP = 2.5
    private const val OTHER_WIDTH_DP = 1.5

    /** The ripple width in dp before the fading term: texts are heavier. */
    fun kindWidthDp(kind: PacketKind): Double = if (kind == PacketKind.Text) TEXT_WIDTH_DP else OTHER_WIDTH_DP

    /** Path-like packets draw a dashed ring; everything else is solid. */
    fun ringStyle(kind: PacketKind): RingStyle = when (kind) {
        PacketKind.Routing, PacketKind.Traceroute, PacketKind.NeighborInfo -> RingStyle.Dashed
        else -> RingStyle.Solid
    }

    const val MAX_PAYLOAD_BYTES = 233
    private const val PAYLOAD_TRIM_MIN = 0.85
    private const val PAYLOAD_TRIM_RANGE = 0.35

    /** Ripple width multiplier 0.85..1.2 from payload size; 1.0 when unknown. */
    fun payloadWidthTrim(payloadBytes: Int?): Double {
        if (payloadBytes == null) return 1.0
        return PAYLOAD_TRIM_MIN + PAYLOAD_TRIM_RANGE * (payloadBytes.toDouble() / MAX_PAYLOAD_BYTES).coerceIn(0.0, 1.0)
    }

    private const val ONE_RING_MAX_CHARS = 40
    private const val TWO_RINGS_MAX_CHARS = 100
    private const val MAX_RINGS = 3

    /** Rings of a text ripple from its length: 1 up to 40 characters, 2 up to 100, else 3; always 1 for other kinds. */
    fun ringCount(kind: PacketKind, textLength: Int?): Int = when {
        kind != PacketKind.Text || textLength == null || textLength <= ONE_RING_MAX_CHARS -> 1
        textLength <= TWO_RINGS_MAX_CHARS -> 2
        else -> MAX_RINGS
    }

    private const val MAX_HUED_CHANNEL = 7
    private const val CHANNEL_HUE_STEP_DEG = 3

    /** Hue offset in whole degrees for a channel index: 3 degrees per index up to 7; 0 for channel 0 or unknown. */
    fun channelHueDegrees(channel: Int?): Int = (channel ?: 0).coerceIn(0, MAX_HUED_CHANNEL) * CHANNEL_HUE_STEP_DEG

    /** How far a ripple that came through MQTT is pulled toward the cool tint. */
    const val MQTT_COOL_MIX = 0.35f

    fun mqttMix(viaMqtt: Boolean): Float = if (viaMqtt) MQTT_COOL_MIX else 0f

    /**
     * The ripple stroke width in dp: the kind's base times the payload [trim] plus a thinning term that shrinks over
     * [progress] 0..1.
     */
    fun rippleWidthDp(kind: PacketKind, trim: Double, progress: Double): Double =
        kindWidthDp(kind) * trim + 2.0 * (1.0 - progress)
}
