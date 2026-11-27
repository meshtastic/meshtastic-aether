package org.meshtastic.aether.music

import org.meshtastic.aether.mesh.GeoPoint
import org.meshtastic.aether.mesh.PacketKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow

private const val HALF_TURN_DEGREES = 180.0

/** Two positions closer than this (in degrees) are the same place: no bearing, so a centred pan. */
private const val SAME_PLACE_DEGREES = 1e-9

/** How much shorter a degree of longitude is than a degree of latitude at [latDegrees]. */
internal fun eastWestScale(latDegrees: Double): Double = cos(latDegrees * PI / HALF_TURN_DEGREES)

/** Unknown hops sit at the middle distance; MQTT adds one more hop. */
private fun effectiveHops(hops: Int?, viaMqtt: Boolean): Int =
    ((hops ?: (Ornaments.MAX_HOPS / 2)).coerceAtLeast(0) + if (viaMqtt) 1 else 0).coerceAtMost(Ornaments.MAX_HOPS)

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
        val free = busyUntil.indexOfFirst { it <= startFrame }
        val slot = if (free >= 0) free else busyUntil.indices.minBy { busyUntil[it] }
        val at = maxOf(startFrame, busyUntil[slot])
        if (at > deadlineFrame) return null
        busyUntil[slot] = at + durationFrames
        return at
    }
}

object Ornaments {
    const val SLOT_CAP = 15
    const val TTL_SECONDS = 2.0
    const val GRID_SECONDS = 0.25
    const val ECHO_DELAY_SECONDS = 0.375

    /** Destination node number of a broadcast. */
    const val BROADCAST = 0xFFFFFFFFL

    /** A call (unicast to us) against the same packet as a broadcast: shorter, drier, brighter, a little louder. */
    const val CALL_LENGTH_SCALE = 0.6
    const val CALL_DRYNESS = 0.15f
    const val CALL_VELOCITY_LIFT = 0.1f
    const val CALL_BRIGHTNESS_LIFT = 0.15f
    const val BROADCAST_WIDEN = 0.05f

    /**
     * Smoothed (2 s) packets per second above which per-packet notes thin to a sample: the 15 slots carry about 4
     * packets a second at one note and an echo each, so this is where a burst starts to drop notes.
     */
    const val THIN_RATE = 4.0

    /** No ornament sits further from the centre than this, channel offset included. */
    const val MAX_PAN = 0.7f

    /** Centre of the filtered noise for an encrypted breath; the same for every node, so it carries no melody. */
    const val BREATH_HZ = 1500.0
    const val MAX_HOPS = 8

    /** Register bands a node can play in: 0..2 octaves above the key root. */
    const val BANDS = 3

    /** [role] reads the node hash from bit 1 and [band] from bit 9, so the two are independent. */
    private const val BAND_HASH_SHIFT = 9

    /** The SNR range [velocityFor] spans, in dB; unknown sits in the middle of it. */
    private const val SNR_LOW_DB = -20.0
    private const val SNR_HIGH_DB = 10.0
    private const val UNKNOWN_SNR_FRACTION = 0.5

    /** Reverb send is this at zero hops and rises by [SEND_PER_HOP] per hop. */
    private const val SEND_BASE = 0.1
    private const val SEND_PER_HOP = 0.1
    private const val HOPS_PER_ECHO = 3
    private const val DULLING_PER_HOP = 0.06
    private const val MIN_BRIGHTNESS = 0.1

    /** Chord role 0..4 (root, third, fifth, seventh, ninth) this node always plays. */
    fun role(node: Long): Int = (Rng.mix(node) ushr 1).mod(Chord.ROLES.toLong()).toInt()

    /** Register band 0..2 (octaves above the key root) this node always plays in. */
    fun band(node: Long): Int = (Rng.mix(node) ushr BAND_HASH_SHIFT).mod(BANDS.toLong()).toInt()

    fun pitchHz(node: Long, chord: Chord, rootHz: Double): Double = pitchHz(role(node), band(node), chord, rootHz)

    fun pitchHz(role: Int, band: Int, chord: Chord, rootHz: Double): Double =
        rootHz * Theory.justRatio(chord.roleSemitone(role)) * 2.0.pow(band)

    /** SNR -20..10 dB maps to 0.25..0.6 (under 9 dB of loudness); null (unknown) is the middle of the range. */
    fun velocityFor(snr: Float?): Float {
        val frac = if (snr == null) {
            UNKNOWN_SNR_FRACTION
        } else {
            ((snr - SNR_LOW_DB) / (SNR_HIGH_DB - SNR_LOW_DB)).coerceIn(0.0, 1.0)
        }
        return (MIN_VELOCITY + (MAX_VELOCITY - MIN_VELOCITY) * frac).toFloat()
    }

    const val MIN_VELOCITY = 0.25
    const val MAX_VELOCITY = 0.6

    fun reverbSendFor(hops: Int?, viaMqtt: Boolean): Float =
        (SEND_BASE + SEND_PER_HOP * effectiveHops(hops, viaMqtt)).toFloat()

    fun echoesFor(hops: Int?, viaMqtt: Boolean): Int = effectiveHops(hops, viaMqtt) / HOPS_PER_ECHO

    fun brightnessFor(kind: PacketKind, hops: Int?, viaMqtt: Boolean): Float =
        (articulation(kind).brightness - DULLING_PER_HOP * effectiveHops(hops, viaMqtt))
            .coerceAtLeast(MIN_BRIGHTNESS).toFloat()

    fun articulation(kind: PacketKind): Articulation = when (kind) {
        PacketKind.Text -> Articulation(Voice.Bell, seconds = 1.2, brightness = 0.8)
        PacketKind.Position -> Articulation(Voice.Mallet, seconds = 0.7, brightness = 0.55)
        PacketKind.Telemetry -> Articulation(Voice.Pluck, seconds = 0.45, brightness = 0.4)
        PacketKind.NodeInfo -> Articulation(Voice.Bell, seconds = 1.0, brightness = 0.5)
        PacketKind.Routing -> Articulation(Voice.Pluck, seconds = 0.3, brightness = 0.3)
        PacketKind.Traceroute -> Articulation(Voice.Bell, seconds = 1.2, brightness = 0.35)
        PacketKind.NeighborInfo -> Articulation(Voice.Mallet, seconds = 0.6, brightness = 0.45)
        PacketKind.Other -> Articulation(Voice.Mallet, seconds = 0.5, brightness = 0.4)
        PacketKind.Encrypted -> Articulation(Voice.Breath, seconds = 1.5, brightness = 0.3)
    }

    /**
     * Pan -[MAX_PAN] (west) to [MAX_PAN] (east) from the bearing of [from] relative to [reference]; centre when either
     * is unknown.
     */
    fun panFor(from: GeoPoint?, reference: GeoPoint?): Float {
        if (from == null || reference == null) return 0f
        val dx = (from.lon - reference.lon) * eastWestScale(reference.lat)
        val dy = from.lat - reference.lat
        val len = hypot(dx, dy)
        return if (len < SAME_PLACE_DEGREES) 0f else (MAX_PAN * dx / len).toFloat()
    }
}
