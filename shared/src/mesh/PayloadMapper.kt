package org.meshtastic.aether.mesh

import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NeighborInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.RouteDiscovery

private fun Int.node(): Long = toLong() and 0xFFFFFFFFL

/** A decoded NeighborInfo payload as an edge list; null for other ports, empty reports and undecodable payloads. */
internal fun MeshPacket.toNeighbors(nowMillis: Long): MeshEvent.Neighbors? {
    val data = decoded ?: return null
    if (data.portnum != PortNum.NEIGHBORINFO_APP) return null
    val info = try {
        NeighborInfo.ADAPTER.decode(data.payload)
    } catch (_: Exception) {
        return null
    }
    val reporter = info.node_id.takeIf { it != 0 }?.node() ?: from.node()
    val heard = info.neighbors.map { it.node_id.node() }.filter { it != 0L && it != reporter }.distinct()
    if (heard.isEmpty()) return null
    return MeshEvent.Neighbors(reporter, heard, nowMillis)
}

/**
 * A decoded traceroute payload as a path, requester first. The route list holds the hops between the two
 * ends; a reply (request_id set) travels from `from` back to `to`, so the requester is `to`.
 */
internal fun MeshPacket.toRoute(nowMillis: Long): MeshEvent.Route? {
    val data = decoded ?: return null
    if (data.portnum != PortNum.TRACEROUTE_APP) return null
    val discovery = try {
        RouteDiscovery.ADAPTER.decode(data.payload)
    } catch (_: Exception) {
        return null
    }
    val hops = discovery.route.map { it.node() }
    val ends = if (data.request_id != 0) to.node() to from.node() else from.node() to to.node()
    val path = (listOf(ends.first) + hops + listOf(ends.second)).filter { it != 0L && it != 0xFFFFFFFFL }
    return if (path.size >= 2) MeshEvent.Route(path, nowMillis) else null
}
