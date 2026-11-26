package org.meshtastic.aether.music

import kotlin.math.ln
import kotlin.math.pow

/**
 * Per-packet mappings from a node's own attributes to its note. Each is bounded, and each returns today's constant
 * (zero offset, scale 1, four notes) when the attribute is absent.
 */
object Traits {
    /** RSSI range the proximity mapping spans; stronger than [RSSI_NEAR_DBM] is as near as it gets. */
    const val RSSI_FAR_DBM = -120
    const val RSSI_NEAR_DBM = -30
    const val RSSI_SEND_SWING = 0.2f
    const val RSSI_BRIGHTNESS_SWING = 0.2f

    private fun proximity(rssi: Int): Double = ((rssi - RSSI_FAR_DBM).toDouble() / (RSSI_NEAR_DBM - RSSI_FAR_DBM)).coerceIn(0.0, 1.0)

    /** Reverb send change for RSSI: a near (strong) node is drier by up to half of [RSSI_SEND_SWING], a far one wetter. */
    fun rssiSendDelta(rssi: Int?): Float = if (rssi == null) 0f else (RSSI_SEND_SWING * (0.5 - proximity(rssi))).toFloat()

    /** Brightness (filter cutoff) change for RSSI: near is brighter by up to half of [RSSI_BRIGHTNESS_SWING], far is duller. */
    fun rssiBrightnessDelta(rssi: Int?): Float = if (rssi == null) 0f else (RSSI_BRIGHTNESS_SWING * (proximity(rssi) - 0.5)).toFloat()

    /** Battery percent at or above which a node does not waver; 101 is the proto's "externally powered". */
    const val STEADY_BATTERY_PCT = 50
    const val MAX_DETUNE_CENTS = 15.0

    /** Detune of the wavering twin note in cents: 0 at or above [STEADY_BATTERY_PCT] (or unknown), [MAX_DETUNE_CENTS] at 0 percent. */
    fun batteryDetuneCents(batteryPct: Int?): Double {
        if (batteryPct == null || batteryPct >= STEADY_BATTERY_PCT) return 0.0
        return MAX_DETUNE_CENTS * ((STEADY_BATTERY_PCT - batteryPct.coerceAtLeast(0)).toDouble() / STEADY_BATTERY_PCT)
    }

    /** Ratio of a note detuned by [cents]. */
    fun centsRatio(cents: Double): Double = 2.0.pow(cents / 1200.0)

    /** A node this far (metres) above the mesh's mean altitude plays an octave higher; this far below, an octave lower. */
    const val ALTITUDE_STEP_M = 150.0

    /** Octave offset -1, 0 or 1 for [altitudeM] against the mean altitude [referenceM]; 0 when either is unknown. */
    fun registerOffset(altitudeM: Int?, referenceM: Double?): Int {
        if (altitudeM == null || referenceM == null) return 0
        val d = altitudeM - referenceM
        return if (d >= ALTITUDE_STEP_M) 1 else if (d <= -ALTITUDE_STEP_M) -1 else 0
    }

    const val UPTIME_LONGEST_HOURS = 720.0
    const val UPTIME_MAX_SCALE = 1.5

    /** Note length multiplier 1..1.5, rising with the log of uptime and full at 30 days; 1 when unknown. */
    fun uptimeLengthScale(uptimeSeconds: Long?): Double {
        if (uptimeSeconds == null || uptimeSeconds <= 0) return 1.0
        val hours = uptimeSeconds / 3600.0
        val u = (ln(1.0 + hours) / ln(1.0 + UPTIME_LONGEST_HOURS)).coerceIn(0.0, 1.0)
        return 1.0 + (UPTIME_MAX_SCALE - 1.0) * u
    }

    const val LIPO_EMPTY_V = 3.3f
    const val LIPO_FULL_V = 4.2f
    const val POWER_BRIGHTNESS_SWING = 0.1f

    /**
     * Brightness change from supply: external power (battery 101) is the brightest, [POWER_BRIGHTNESS_SWING] up; a cell
     * voltage maps 3.3 V to that much down and 4.2 V to that much up. 0 when neither is reported.
     */
    fun powerBrightnessDelta(voltage: Float?, batteryPct: Int?): Float {
        if (batteryPct != null && batteryPct > 100) return POWER_BRIGHTNESS_SWING
        if (voltage == null || !voltage.isFinite() || voltage <= 0f) return 0f
        val frac = ((voltage - LIPO_EMPTY_V) / (LIPO_FULL_V - LIPO_EMPTY_V)).coerceIn(0f, 1f)
        return POWER_BRIGHTNESS_SWING * (2f * frac - 1f)
    }

    /** The largest LoRa payload, and the payload at which the velocity trim is neutral (half of it). */
    const val MAX_PAYLOAD_BYTES = 237
    const val PAYLOAD_TRIM_SPAN = 0.2f

    /** Velocity multiplier 0.9..1.1 rising with payload size; 1 when unknown. */
    fun payloadVelocityTrim(bytes: Int?): Float {
        if (bytes == null) return 1f
        val frac = (bytes.toFloat() / MAX_PAYLOAD_BYTES).coerceIn(0f, 1f)
        return 1f - PAYLOAD_TRIM_SPAN / 2 + PAYLOAD_TRIM_SPAN * frac
    }

    const val TEXT_NOTES_NEUTRAL = 4
    const val TEXT_NOTES_MIN = 3
    const val TEXT_NOTES_MAX = 6

    /** Notes in a text's phrase: 3 under 40 characters, then one more per 60 up to 6; 4 when the length is unknown. */
    fun textPhraseNotes(textLength: Int?): Int {
        if (textLength == null) return TEXT_NOTES_NEUTRAL
        if (textLength < 40) return TEXT_NOTES_MIN
        return (4 + (textLength - 40) / 60).coerceAtMost(TEXT_NOTES_MAX)
    }

    const val CHANNEL_PAN_STEP = 0.08f

    /** Pan offset by channel index: 0 and unknown stay centred, odd channels step east, even channels west, 0.08 per pair. */
    fun channelPanOffset(channel: Int?): Float {
        if (channel == null || channel <= 0) return 0f
        val magnitude = CHANNEL_PAN_STEP * ((channel + 1) / 2)
        return if (channel % 2 == 1) magnitude else -magnitude
    }

    /** The shimmer layer an MQTT packet adds sits this far under the note it rides on. */
    const val MQTT_SHIMMER_GAIN = 0.5f

    /** The wavering twin of a low-battery note sits this far under it. */
    const val TWIN_GAIN = 0.6f

}
