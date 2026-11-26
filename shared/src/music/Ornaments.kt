package org.meshtastic.ambient.music

import org.meshtastic.ambient.mesh.GeoPoint
import org.meshtastic.ambient.mesh.PacketKind
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow

/** How a portnum sounds: always the Bell, Mallet or Pluck family, only length and brightness differ. */
data class Articulation(val voice: Voice, val seconds: Double, val brightness: Double)

/**
 * Hands out a fixed number of slots; a note that finds none free waits for the earliest one only until its
 * deadline and is dropped past it, never queued further.
 */
class SlotAllocator(capacity: Int) {
    private val busyUntil = LongArray(capacity)

    /** The frame the note will start at (>= [startFrame], <= [deadlineFrame]), or null when it is dropped. */
    fun allocate(startFrame: Long, durationFrames: Long, deadlineFrame: Long): Long? {
        if (startFrame > deadlineFrame) return null
        var free = -1
        for (i in busyUntil.indices) {
            if (busyUntil[i] <= startFrame) {
                free = i
                break
            }
        }
        var at = startFrame
        if (free < 0) {
            free = busyUntil.indices.minBy { busyUntil[it] }
            at = busyUntil[free]
            if (at > deadlineFrame) return null
        }
        busyUntil[free] = at + durationFrames
        return at
    }
}

object Ornaments {
    const val SLOT_CAP = 15
    const val TTL_SECONDS = 2.0
    const val GRID_SECONDS = 0.25
    const val ECHO_DELAY_SECONDS = 0.375

    /** Smoothed packets per second above which per-packet notes thin to a sample. */
    const val THIN_RATE = 25.0

    /** Centre of the filtered noise for an encrypted breath; the same for every node, so it carries no melody. */
    const val BREATH_HZ = 1500.0
    const val MAX_HOPS = 8

    private fun hash(node: Long): Long = Rng.mix(node)

    /** Chord role 0..4 (root, third, fifth, seventh, ninth) this node always plays. */
    fun role(node: Long): Int = (hash(node) ushr 1).mod(5L).toInt()

    /** Register band 0..2 (octaves above the key root) this node always plays in. */
    fun band(node: Long): Int = (hash(node) ushr 9).mod(3L).toInt()

    fun pitchHz(node: Long, chord: Chord, rootHz: Double): Double = pitchHz(role(node), band(node), chord, rootHz)

    fun pitchHz(role: Int, band: Int, chord: Chord, rootHz: Double): Double =
        rootHz * Theory.justRatio(chord.roleSemitone(role)) * 2.0.pow(band)

    /** SNR -20..10 dB maps to 0.2..0.9; null (unknown) is the middle of the range. */
    fun velocityFor(snr: Float?): Float {
        val frac = if (snr == null) 0.5 else ((snr + 20.0) / 30.0).coerceIn(0.0, 1.0)
        return (0.2 + 0.7 * frac).toFloat()
    }

    /** Unknown hops sit at the middle distance; MQTT adds one more hop. */
    fun effectiveHops(hops: Int?, viaMqtt: Boolean): Int =
        ((hops ?: (MAX_HOPS / 2)).coerceAtLeast(0) + if (viaMqtt) 1 else 0).coerceAtMost(MAX_HOPS)

    fun reverbSendFor(hops: Int?, viaMqtt: Boolean): Float = (0.1 + 0.1 * effectiveHops(hops, viaMqtt)).toFloat()

    fun echoesFor(hops: Int?, viaMqtt: Boolean): Int = effectiveHops(hops, viaMqtt) / 3

    fun brightnessFor(kind: PacketKind, hops: Int?, viaMqtt: Boolean): Float =
        (articulation(kind).brightness - 0.06 * effectiveHops(hops, viaMqtt)).coerceAtLeast(0.1).toFloat()

    fun articulation(kind: PacketKind): Articulation = when (kind) {
        PacketKind.Text -> Articulation(Voice.Bell, 1.2, 0.8)
        PacketKind.Position -> Articulation(Voice.Mallet, 0.7, 0.55)
        PacketKind.Telemetry -> Articulation(Voice.Pluck, 0.45, 0.4)
        PacketKind.NodeInfo -> Articulation(Voice.Bell, 1.0, 0.5)
        PacketKind.Routing -> Articulation(Voice.Pluck, 0.3, 0.3)
        PacketKind.Traceroute -> Articulation(Voice.Bell, 1.2, 0.35)
        PacketKind.NeighborInfo -> Articulation(Voice.Mallet, 0.6, 0.45)
        PacketKind.Other -> Articulation(Voice.Mallet, 0.5, 0.4)
        PacketKind.Encrypted -> Articulation(Voice.Breath, 1.5, 0.3)
    }

    /** Pan -0.8 (west) to 0.8 (east) from the bearing of [from] relative to [reference]; centre when either is unknown. */
    fun panFor(from: GeoPoint?, reference: GeoPoint?): Float {
        if (from == null || reference == null) return 0f
        val dx = (from.lon - reference.lon) * cos(reference.lat * kotlin.math.PI / 180.0)
        val dy = from.lat - reference.lat
        val len = hypot(dx, dy)
        return if (len < 1e-9) 0f else (0.8 * dx / len).toFloat()
    }
}
