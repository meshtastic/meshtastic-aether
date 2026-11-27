package org.meshtastic.aether.music

import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.mesh.PacketKind
import kotlin.math.max
import kotlin.math.min

private const val MAX_BAND = Ornaments.BANDS - 1

/** How one packet's notes sound, before any is placed: everything but pitch and start. */
internal data class PacketSound(
    val node: Long,
    val voice: Voice,
    val durationFrames: Long,
    val velocity: Float,
    val send: Float,
    val brightness: Float,
    val pan: Float,
    /** Register band 0..2 after the altitude offset. */
    val band: Int,
    /** Detune of the low-battery twin note; under one cent there is no twin. */
    val twinCents: Double,
    val viaMqtt: Boolean,
)

/**
 * Turns a heard packet into its [PacketSound]: the node's role and hardware pick the voice, its kind the length, the
 * link (SNR, hops, RSSI, MQTT) the loudness, wetness and brightness, and its own traits the rest. A call (a packet to
 * us) is shorter, drier, brighter and a little louder than a broadcast.
 */
internal class PacketVoicing(
    private val score: Score,
    private val traits: TraitBook,
    private val nodes: NodeRegistry,
    private val voiceOf: (node: Long, role: String?, hardware: String?) -> Voice,
) {
    fun soundOf(e: MeshEvent.PacketHeard): PacketSound {
        val tr = traits[e.from]
        val voice = if (e.kind == PacketKind.Encrypted) Voice.Breath else voiceOf(e.from, tr?.role, tr?.hardware)
        val callScale = if (e.toUs) Ornaments.CALL_LENGTH_SCALE else 1.0
        val seconds = Ornaments.articulation(e.kind).seconds * Traits.uptimeLengthScale(tr?.uptimeSeconds) * callScale
        val pan = nodes.panOf(e.from) + Traits.channelPanOffset(e.channel)
        val band = Ornaments.band(e.from) + Traits.registerOffset(tr?.altitudeM, traits.meanAltitude())
        return PacketSound(
            node = e.from,
            voice = voice,
            durationFrames = score.sec(seconds),
            velocity = velocityOf(e),
            send = sendOf(e),
            brightness = brightnessOf(e, tr),
            pan = pan.coerceIn(-Ornaments.MAX_PAN, Ornaments.MAX_PAN),
            band = band.coerceIn(0, MAX_BAND),
            twinCents = Traits.batteryDetuneCents(tr?.batteryPct),
            viaMqtt = e.viaMqtt,
        )
    }

    private fun velocityOf(e: MeshEvent.PacketHeard): Float {
        val base = Ornaments.velocityFor(e.snr)
        val lifted = if (e.toUs) min(1f, base + Ornaments.CALL_VELOCITY_LIFT) else base
        return min(1f, lifted * Traits.payloadVelocityTrim(e.payloadBytes))
    }

    private fun sendOf(e: MeshEvent.PacketHeard): Float {
        val base = Ornaments.reverbSendFor(e.hopsTaken, e.viaMqtt)
        val send = when {
            e.toUs -> max(0f, base - Ornaments.CALL_DRYNESS)
            e.to == Ornaments.BROADCAST -> min(1f, base + Ornaments.BROADCAST_WIDEN)
            else -> base
        }
        return (send + Traits.rssiSendDelta(e.rssi)).coerceIn(0f, 1f)
    }

    private fun brightnessOf(e: MeshEvent.PacketHeard, tr: NodeTraits?): Float {
        val base = Ornaments.brightnessFor(e.kind, e.hopsTaken, e.viaMqtt)
        val lifted = if (e.toUs) min(1f, base + Ornaments.CALL_BRIGHTNESS_LIFT) else base
        val power = Traits.powerBrightnessDelta(tr?.voltage, tr?.batteryPct)
        return (lifted + Traits.rssiBrightnessDelta(e.rssi) + power).coerceIn(0f, 1f)
    }
}
