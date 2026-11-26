package org.meshtastic.ambient.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.meshtastic.ambient.mesh.MeshEvent
import org.meshtastic.ambient.mesh.MeshSource
import org.meshtastic.ambient.mesh.ReplayMeshSource

fun MeshEvent.shifted(by: Long): MeshEvent = when (this) {
    is MeshEvent.PacketHeard -> copy(atMillis = atMillis + by)
    is MeshEvent.NodeSeen -> copy(atMillis = atMillis + by)
    is MeshEvent.NodeGone -> copy(atMillis = atMillis + by)
    is MeshEvent.Environment -> copy(atMillis = atMillis + by)
    is MeshEvent.Vitals -> copy(atMillis = atMillis + by)
    is MeshEvent.Link -> copy(atMillis = atMillis + by)
    is MeshEvent.Self -> copy(atMillis = atMillis + by)
    is MeshEvent.Neighbors -> copy(atMillis = atMillis + by)
    is MeshEvent.Route -> copy(atMillis = atMillis + by)
}

/**
 * Replays a capture forever, each pass rebased so its first event lands at now: the composer drops packets older
 * than a day, and a capture's own timestamps are always in the past.
 */
class LoopingReplaySource(
    private val lines: () -> Sequence<String>,
    private val speed: Double = 1.0,
    private val nowMillis: () -> Long,
    private val pauseMillis: Long = 5_000,
) : MeshSource {
    override val events: Flow<MeshEvent> = flow {
        while (true) {
            var offset: Long? = null
            ReplayMeshSource(lines(), speed).events.collect { e ->
                val by = offset ?: (nowMillis() - e.atMillis).also { offset = it }
                emit(e.shifted(by))
            }
            delay(pauseMillis)
        }
    }
}
