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
        /** Destination node number; null when unknown. Firmware forwards only broadcasts and packets addressed to us. */
        val to: Long? = null,
        /** Low byte (0..255) of the node that relayed the packet last; null when the packet came straight from its origin. */
        val relayByte: Int? = null,
        /** True for a unicast addressed to our own node. */
        val toUs: Boolean = false,
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

    /** Our own node, once known: the anchor every destination, pan and layout is relative to. */
    data class Self(val node: Long, val name: String?, val position: GeoPoint?, override val atMillis: Long) : MeshEvent

    /** A NeighborInfo report: [node] hears each of [neighbors] directly. */
    data class Neighbors(val node: Long, val neighbors: List<Long>, override val atMillis: Long) : MeshEvent

    /** A traceroute route, origin first, in node numbers. */
    data class Route(val nodes: List<Long>, override val atMillis: Long) : MeshEvent
}

interface MeshSource {
    /** Cold; collecting starts the source, cancelling the collector stops it. */
    val events: Flow<MeshEvent>
}
