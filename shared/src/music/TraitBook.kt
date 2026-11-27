package org.meshtastic.aether.music

import org.meshtastic.aether.mesh.MeshEvent

/** What the mesh has told us about one node besides its position; every field is null until reported. */
internal class NodeTraits {
    var role: String? = null
    var hardware: String? = null
    var batteryPct: Int? = null
    var voltage: Float? = null
    var uptimeSeconds: Long? = null
    var altitudeM: Int? = null
}

/** Every node's [NodeTraits], plus the mean altitude of the nodes that reported one. */
internal class TraitBook {
    private val traits = HashMap<Long, NodeTraits>()
    private var altitudeSum = 0.0
    private var altitudeCount = 0

    operator fun get(node: Long): NodeTraits? = traits[node]

    fun seen(e: MeshEvent.NodeSeen) {
        val t = of(e.node)
        t.role = e.role ?: t.role
        t.hardware = e.hardware ?: t.hardware
        val alt = e.position?.altitudeM
        if (alt != null && alt != t.altitudeM) {
            t.altitudeM?.let { altitudeSum -= it; altitudeCount-- }
            t.altitudeM = alt
            altitudeSum += alt
            altitudeCount++
        }
    }

    fun health(e: MeshEvent.Health) {
        val t = of(e.node)
        t.batteryPct = e.batteryPct ?: t.batteryPct
        t.voltage = e.voltage ?: t.voltage
        t.uptimeSeconds = e.uptimeSeconds ?: t.uptimeSeconds
    }

    fun remove(node: Long) {
        traits.remove(node)?.altitudeM?.let { altitudeSum -= it; altitudeCount-- }
    }

    fun meanAltitude(): Double? = if (altitudeCount > 0) altitudeSum / altitudeCount else null

    private fun of(node: Long): NodeTraits = traits.getOrPut(node) { NodeTraits() }
}
