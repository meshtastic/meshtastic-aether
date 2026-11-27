package org.meshtastic.aether.music

import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.mood.MeshMood

private const val DYAD_MS = 60_000L
private const val DYAD_SECONDS = 4.0
private const val DYAD_VELOCITY = 0.25f
private const val MAX_ROUTE_NODES = 8
private const val ROUTE_STEP_SECONDS = 0.3
private const val ROUTE_NOTE_SECONDS = 0.8
private const val ROUTE_VELOCITY = 0.45f
private const val ROUTE_SEND = 0.5f
private const val ROUTE_BRIGHTNESS = 0.5f

/**
 * The mesh's shape as phrases: a neighbor report is a pad dyad of two nodes (at most one a minute), a traced route
 * its hops as bells in order.
 */
internal class MeshPhrases(private val score: Score, private val mood: MeshMood, private val nodes: NodeRegistry) {
    private var lastDyadMs = Long.MIN_VALUE
    private val pendingDyads = ArrayList<Pair<Long, Long>>()
    private val pendingRoutes = ArrayList<List<Long>>()

    /** The reporter and the first neighbor the composer already knows. */
    fun onNeighbors(e: MeshEvent.Neighbors) {
        if (mood.warmingUp || dyadResting(e.atMillis)) return
        val other = e.neighbors.firstOrNull { it != e.node && it in nodes.ids } ?: return
        lastDyadMs = e.atMillis
        pendingDyads += e.node to other
    }

    fun onRoute(e: MeshEvent.Route) {
        if (!mood.warmingUp && e.nodes.isNotEmpty()) pendingRoutes += e.nodes.take(MAX_ROUTE_NODES)
    }

    fun run(nowFrame: Long) {
        playDyads(nowFrame)
        playRoutes(nowFrame)
    }

    private fun dyadResting(atMillis: Long): Boolean = lastDyadMs != Long.MIN_VALUE && atMillis - lastDyadMs < DYAD_MS

    private fun playDyads(nowFrame: Long) {
        val chord = score.chord
        val padLevel = score.levels(nowFrame).getValue(Voice.Pad)
        for ((a, b) in pendingDyads) {
            val length = score.clip(nowFrame, score.sec(DYAD_SECONDS))
            if (padLevel <= Score.AUDIBLE_LEVEL || length <= 0) continue
            for (node in listOf(a, b)) {
                score.notes += NoteEvent(
                    nowFrame, Voice.Pad, score.padHz(chord.roleSemitone(Ornaments.role(node))), DYAD_VELOCITY,
                    nodes.panOf(node), Score.LOOP_SEND, length, Score.MEMORY_BRIGHTNESS, node,
                )
            }
        }
        pendingDyads.clear()
    }

    private fun playRoutes(nowFrame: Long) {
        val chord = score.chord
        val duration = score.sec(ROUTE_NOTE_SECONDS)
        for (route in pendingRoutes) {
            route.forEachIndexed { i, node ->
                score.emit(
                    NoteEvent(
                        nowFrame + score.sec(ROUTE_STEP_SECONDS * i), Voice.Bell,
                        Ornaments.pitchHz(node, chord, score.keyHz), ROUTE_VELOCITY, nodes.panOf(node), ROUTE_SEND,
                        duration, ROUTE_BRIGHTNESS, node,
                    ),
                )
            }
        }
        pendingRoutes.clear()
    }
}
