package org.meshtastic.aether.mesh

import com.squareup.wire.ProtoAdapter
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NeighborInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.RouteDiscovery

/** The decoded payload when the packet is on [port]; null for other ports and undecodable payloads. */
private fun <T> MeshPacket.payloadOn(port: PortNum, adapter: ProtoAdapter<T>): T? {
    val data = decoded?.takeIf { it.portnum == port } ?: return null
    return try {
        adapter.decode(data.payload)
    } catch (_: Exception) {
        null
    }
}

/** A decoded NeighborInfo payload as an edge list; null for other ports, empty reports and undecodable payloads. */
internal fun MeshPacket.toNeighbors(nowMillis: Long): MeshEvent.Neighbors? {
    val info = payloadOn(PortNum.NEIGHBORINFO_APP, NeighborInfo.ADAPTER) ?: return null
    val reporter = info.node_id.takeIf { it != 0 }?.toNodeNum() ?: from.toNodeNum()
    val heard = info.neighbors.map { it.node_id.toNodeNum() }.filter { it != 0L && it != reporter }.distinct()
    return if (heard.isEmpty()) null else MeshEvent.Neighbors(reporter, heard, nowMillis)
}

/**
 * A decoded traceroute payload as a path, requester first. The route list holds the hops between the two
 * ends; a reply (request_id set) travels from `from` back to `to`, so the requester is `to`.
 */
internal fun MeshPacket.toRoute(nowMillis: Long): MeshEvent.Route? {
    val discovery = payloadOn(PortNum.TRACEROUTE_APP, RouteDiscovery.ADAPTER) ?: return null
    val hops = discovery.route.map { it.toNodeNum() }
    val isReply = (decoded?.request_id ?: 0) != 0
    val ends = if (isReply) to.toNodeNum() to from.toNodeNum() else from.toNodeNum() to to.toNodeNum()
    val path = (listOf(ends.first) + hops + listOf(ends.second)).filter { it != 0L && it != BROADCAST_NUM }
    return if (path.size >= 2) MeshEvent.Route(path, nowMillis) else null
}
