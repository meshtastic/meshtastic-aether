package org.meshtastic.aether.visual

import org.meshtastic.aether.mesh.GeoPoint
import org.meshtastic.aether.mesh.MeshEvent

/** The orbs by node number, our own node (never an orb), and whether the layout is stale. */
internal class OrbRoster {
    private val map = LinkedHashMap<Long, Orb>()
    private val layout = SceneLayout()

    var selfNode: Long? = null
        private set

    /** Set when a node joins, leaves or moves, or the origin or aspect changes; [relayout] clears it. */
    var dirty = false

    val orbs: Collection<Orb> get() = map.values
    val nodes: Set<Long> get() = map.keys

    fun orb(node: Long): Orb? = map[node]

    fun isKnown(node: Long) = node == selfNode || map.containsKey(node)

    fun seen(e: MeshEvent.NodeSeen, frame: Long) {
        if (e.node == selfNode) return
        map.getOrPut(e.node) { newOrb(e.node, frame) }.see(e)
        dirty = true
    }

    fun health(e: MeshEvent.Health, frame: Long) {
        if (e.node == selfNode) return
        map.getOrPut(e.node) { newOrb(e.node, frame).also { dirty = true } }.report(e)
    }

    /** Adds an orb for a node first heard by a packet. */
    fun ensure(node: Long, frame: Long) {
        if (node != selfNode && !map.containsKey(node)) {
            map[node] = newOrb(node, frame)
            dirty = true
        }
    }

    fun remove(node: Long) {
        if (map.remove(node) != null) dirty = true
    }

    /** Our own node is the anchor, never an orb. */
    fun self(node: Long) {
        selfNode = node
        remove(node)
    }

    fun relayout(origin: GeoPoint?, aspect: Double) {
        dirty = false
        layout.layout(map.values, origin, aspect)
    }

    private fun newOrb(node: Long, frame: Long) = Orb(node, null, null).also { it.seenFrame = frame }
}
