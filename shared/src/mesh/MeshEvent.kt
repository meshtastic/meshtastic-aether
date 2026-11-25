package org.meshtastic.ambient.mesh

import kotlinx.coroutines.flow.Flow

data class GeoPoint(val lat: Double, val lon: Double)

enum class PacketKind { Text, Position, Telemetry, NodeInfo, Routing, Traceroute, NeighborInfo, Other, Encrypted }

enum class LinkState { Connecting, WarmingUp, Connected, Simulated, Replaying, Lost }

/** App-level mesh events; nothing downstream of a [MeshSource] sees a protobuf. Times are epoch millis. */
sealed interface MeshEvent {
    val atMillis: Long

    /** [hopsTaken] is null when the origin's hop_start was 0 (old firmware): unknown, not direct. */
    data class PacketHeard(
        val from: Long,
        val kind: PacketKind,
        val snr: Float?,
        val rssi: Int?,
        val hopsTaken: Int?,
        val viaMqtt: Boolean,
        override val atMillis: Long,
    ) : MeshEvent

    data class NodeSeen(
        val node: Long,
        val name: String?,
        val position: GeoPoint?,
        val firstThisSession: Boolean,
        override val atMillis: Long,
    ) : MeshEvent

    data class NodeGone(val node: Long, override val atMillis: Long) : MeshEvent

    data class Environment(
        val node: Long,
        val temperatureC: Float?,
        val humidityPct: Float?,
        val pressureHpa: Float?,
        val windMps: Float?,
        val lux: Float?,
        override val atMillis: Long,
    ) : MeshEvent

    data class Vitals(val channelUtilPct: Float?, val airUtilTxPct: Float?, override val atMillis: Long) : MeshEvent

    data class Link(val state: LinkState, override val atMillis: Long) : MeshEvent
}

interface MeshSource {
    /** Cold; collecting starts the source, cancelling the collector stops it. */
    val events: Flow<MeshEvent>
}
