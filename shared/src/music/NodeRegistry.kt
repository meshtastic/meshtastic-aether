package org.meshtastic.aether.music

import org.meshtastic.aether.mesh.GeoPoint
import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.mesh.resolveRelay
import kotlin.math.hypot

/** The nodes the composer knows: where each is, when it was last heard, and where the listener stands among them. */
internal class NodeRegistry {
    private class NodeInfo(var position: GeoPoint?, var lastHeard: Long)

    private val nodes = HashMap<Long, NodeInfo>()
    private var sumLat = 0.0
    private var sumLon = 0.0
    private var positioned = 0

    /** Our own node, which never answers or relays its own packets. */
    var selfNode: Long? = null

    /** Our own node's position when known; pans are relative to it, else to the centroid of known nodes. */
    var origin: GeoPoint? = null

    val ids: Set<Long> get() = nodes.keys

    fun place(node: Long, position: GeoPoint?) {
        val info = nodes.getOrPut(node) { NodeInfo(null, Long.MIN_VALUE) }
        info.position?.let { sumLat -= it.lat; sumLon -= it.lon; positioned-- }
        info.position = position
        position?.let { sumLat += it.lat; sumLon += it.lon; positioned++ }
    }

    fun remove(node: Long) {
        nodes.remove(node)?.position?.let { sumLat -= it.lat; sumLon -= it.lon; positioned-- }
    }

    fun heard(node: Long, atMillis: Long) {
        nodes.getOrPut(node) { NodeInfo(null, Long.MIN_VALUE) }.lastHeard = atMillis
    }

    fun reference(): GeoPoint? =
        origin ?: if (positioned > 0) GeoPoint(sumLat / positioned, sumLon / positioned) else null

    /** Where [node] sits in the stereo field, by its bearing from [reference]. */
    fun panOf(node: Long): Float = Ornaments.panFor(nodes[node]?.position, reference())

    fun relayFor(e: MeshEvent.PacketHeard): Long? = resolveRelay(nodes.keys, e.relayByte, e.from, selfNode)

    /** The nearest known node, else the resolved relay, that answers [e]. */
    fun responder(e: MeshEvent.PacketHeard): Long? = nearestTo(e.from) ?: relayFor(e)

    /** The positioned node nearest [node], other than itself and us; ties go to the lower node number. */
    private fun nearestTo(node: Long): Long? {
        val here = nodes[node]?.position ?: return null
        val k = eastWestScale(here.lat)
        var best: Long? = null
        var bestD = Double.MAX_VALUE
        for ((id, info) in nodes) {
            val p = info.position
            if (p != null && id != node && id != selfNode) {
                val d = hypot((p.lon - here.lon) * k, p.lat - here.lat)
                val closer = d < bestD || (d == bestD && best != null && id < best)
                if (closer) {
                    bestD = d
                    best = id
                }
            }
        }
        return best
    }

    /** Up to [count] nodes heard at least once, most recent first. */
    fun recentlyHeard(count: Int): List<Long> =
        nodes.entries.filter { it.value.lastHeard != Long.MIN_VALUE }
            .sortedByDescending { it.value.lastHeard }.take(count).map { it.key }

    /** The node silent longest, at least [silentMs] as of [nowMs], that is not in [skip]; ties go to the lower id. */
    fun longestSilent(nowMs: Long, silentMs: Long, skip: Set<Long>): Long? {
        var gone: Long? = null
        var oldest = Long.MAX_VALUE
        for ((id, info) in nodes) {
            if (info.lastHeard == Long.MIN_VALUE || id in skip || nowMs - info.lastHeard < silentMs) continue
            if (info.lastHeard < oldest || (info.lastHeard == oldest && id < gone!!)) {
                oldest = info.lastHeard
                gone = id
            }
        }
        return gone
    }
}
