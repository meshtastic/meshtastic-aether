package org.meshtastic.aether.mesh

import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum

private fun MeshPacket.fromNode(): Long = from.toNodeNum()

/** hop_start 0 means the origin did not report it (old firmware): unknown, never zero hops. */
private fun MeshPacket.hops(): Int? = if (hop_start == 0) null else (hop_start - hop_limit).coerceAtLeast(0)

/** rx_snr and rx_rssi are plain proto3 scalars: 0 on both means the radio reported nothing. */
private fun MeshPacket.snrOrNull(): Float? = if (rx_snr == 0f && (rx_rssi ?: 0) == 0) null else rx_snr

private fun MeshPacket.rssiOrNull(): Int? = rx_rssi?.takeIf { it != 0 }

/** `to` is 0 only when unset; broadcast is 0xFFFFFFFF. */
private fun MeshPacket.toNode(): Long? = to.toNodeNum().takeIf { it != 0L }

/** relay_node is the low byte of the last relayer; 0 means the packet came straight from its origin. */
private fun MeshPacket.relayOrNull(): Int? = relay_node.toNodeNum().lowByte().takeIf { it != 0 }

private fun MeshPacket.heard(kind: PacketKind, nowMillis: Long, ownNode: Long?) = MeshEvent.PacketHeard(
    from = fromNode(),
    kind = kind,
    snr = snrOrNull(),
    rssi = rssiOrNull(),
    hopsTaken = hops(),
    viaMqtt = via_mqtt,
    atMillis = nowMillis,
    to = toNode(),
    relayByte = relayOrNull(),
    toUs = ownNode != null && toNode() == ownNode,
    payloadBytes = decoded?.payload?.size ?: encrypted?.size,
    textLength = decoded?.takeIf { it.portnum == PortNum.TEXT_MESSAGE_APP }?.payload?.utf8()?.codePointCount(),
    channel = if (decoded != null) channel else null,
)

/** Characters as a reader counts them: a surrogate pair is one. */
private fun String.codePointCount(): Int = length - count { it.isLowSurrogate() }

/** Maps packets that carry a decoded payload; an undecoded packet belongs to [RawFrameMapper]. */
internal object PacketMapper {
    fun map(p: MeshPacket, nowMillis: Long, ownNode: Long? = null): MeshEvent.PacketHeard? {
        val data = p.decoded ?: return null
        val kind = when (data.portnum) {
            PortNum.TEXT_MESSAGE_APP, PortNum.TEXT_MESSAGE_COMPRESSED_APP -> PacketKind.Text
            PortNum.POSITION_APP -> PacketKind.Position
            PortNum.TELEMETRY_APP -> PacketKind.Telemetry
            PortNum.NODEINFO_APP -> PacketKind.NodeInfo
            PortNum.ROUTING_APP -> PacketKind.Routing
            PortNum.TRACEROUTE_APP -> PacketKind.Traceroute
            PortNum.NEIGHBORINFO_APP -> PacketKind.NeighborInfo
            else -> PacketKind.Other
        }
        return p.heard(kind, nowMillis, ownNode)
    }
}

/** Takes only `FromRadio.packet` frames with no decoded payload, so it never overlaps [PacketMapper]. */
internal object RawFrameMapper {
    fun map(f: FromRadio, nowMillis: Long, ownNode: Long? = null): MeshEvent.PacketHeard? =
        f.packet?.takeIf { it.decoded == null }?.heard(PacketKind.Encrypted, nowMillis, ownNode)
}
