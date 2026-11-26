package org.meshtastic.ambient.mood

import org.meshtastic.ambient.mesh.MeshEvent
import org.meshtastic.ambient.mesh.PacketKind

internal const val MINUTE = 60_000L
internal const val HOUR = 60 * MINUTE

internal fun packet(
    from: Long,
    at: Long,
    kind: PacketKind = PacketKind.Position,
    snr: Float? = 5f,
    hops: Int? = 2,
    toUs: Boolean = false,
) = MeshEvent.PacketHeard(from, kind, snr, null, hops, false, at, toUs = toUs)

internal fun environment(node: Long, at: Long, pressure: Float? = null, temperature: Float? = null, wind: Float? = null) =
    MeshEvent.Environment(node, temperature, null, pressure, wind, null, at)

/** Feeds [perMinute] packets per minute (fractions carry over) from [nodes] rotating senders for [minutes], ticking every 30 s. */
internal class Sim(val mood: MeshMood = MeshMood(), private val nodes: Int = 10) {
    var now = 0L
        private set
    private var carry = 0.0
    private var next = 0L
    var last: MoodSnapshot = MoodSnapshot.NEUTRAL
        private set

    fun run(
        minutes: Long,
        perMinute: Double,
        hops: Int? = 2,
        snr: Float? = 5f,
        kind: PacketKind = PacketKind.Position,
    ): MoodSnapshot {
        val end = now + minutes * MINUTE
        while (now < end) {
            now += 30_000L
            carry += perMinute / 2.0
            while (carry >= 1.0) {
                carry -= 1.0
                mood.onEvent(packet(1 + next++ % nodes, now, kind, snr, hops))
            }
            last = mood.advance(now)
        }
        return last
    }
}

internal fun MoodSnapshot.allFinite(): Boolean = listOf(
    packetsPerMinute, activity, activityConfidence, surge, surgeConfidence, silence, silenceConfidence, reach, reachConfidence,
    linkQuality, linkQualityConfidence, senders, sendersConfidence, chat, chatConfidence, arrival, arrivalConfidence,
    warmth, warmthConfidence, wind, windConfidence, pressureTrend, pressureTrendConfidence, activityTrend, activityTrendConfidence,
    rotation.targetPeriodSeconds, rotation.tiltDegrees, rotation.wobbleDegrees, rotation.impulse, rotation.directionStrength,
    params.rotationPeriodSeconds, params.tiltDegrees, params.wobbleDegrees, params.phraseSpeed, params.modeLean, params.scalePentatonicBias,
).all { it.isFinite() }
