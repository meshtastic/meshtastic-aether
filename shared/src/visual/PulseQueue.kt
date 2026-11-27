package org.meshtastic.aether.visual

import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.mesh.resolveRelay

private const val PULSE_LINGER_SECONDS = 0.5
private const val ROUTE_PULSE_STRENGTH = 0.6f

/** Pulses along the legs of routes and packets: queued for their start frame, shown, and dropped after arrival. */
internal class PulseQueue(private val sampleRate: Int) {
    private val pending = ArrayList<Pulse>()
    private val active = ArrayList<Pulse>()

    val pulses: List<Pulse> get() = active

    /** One pulse per leg between the known nodes of [e], back to back from the later of [dueFrame] and [frame]. */
    fun route(e: MeshEvent.Route, dueFrame: Long, frame: Long, roster: OrbRoster) {
        val hopFrames = (sampleRate * SceneModel.HOP_SECONDS).toLong()
        val base = maxOf(dueFrame, frame)
        val known = e.nodes.filter { roster.isKnown(it) }
        for (i in 0 until known.size - 1) {
            val a = known[i]
            val b = known[i + 1]
            if (a == b) continue
            val start = base + i * hopFrames
            add(Pulse(a, if (b == roster.selfNode) null else b, start, start + hopFrames, a, ROUTE_PULSE_STRENGTH))
        }
    }

    /** Direct pulse source to anchor ending at [dueFrame]; a resolved relay adds a second, started halfway through. */
    fun packet(e: MeshEvent.PacketHeard, dueFrame: Long, strength: Float, roster: OrbRoster) {
        val travel = (sampleRate * SceneModel.TRAVEL_SECONDS).toLong()
        val relay = resolveRelay(roster.nodes, e.relayByte, e.from, roster.selfNode)
        if (e.toUs) add(Pulse(e.from, null, dueFrame - travel, dueFrame, e.from, strength))
        if (relay != null) add(Pulse(relay, null, dueFrame - travel / 2, dueFrame, relay, strength))
    }

    private fun add(p: Pulse) {
        pending += p
        if (pending.size > SceneModel.MAX_PENDING) pending.removeAt(0)
    }

    /** Shows the pulses whose start [frame] has come and drops the ones that arrived a while before it. */
    fun advance(frame: Long) {
        val pi = pending.iterator()
        while (pi.hasNext()) {
            val p = pi.next()
            if (p.startFrame <= frame) {
                pi.remove()
                active += p
                if (active.size > SceneModel.MAX_PULSES) active.removeAt(0)
            }
        }
        active.removeAll { (frame - it.endFrame).toDouble() / sampleRate > PULSE_LINGER_SECONDS }
    }
}
