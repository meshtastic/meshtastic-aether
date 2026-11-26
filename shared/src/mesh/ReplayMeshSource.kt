package org.meshtastic.ambient.mesh

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.Telemetry
import kotlin.io.encoding.Base64

/**
 * Replays a capture: lines of `<epochMillis>\t<base64 FromRadio>`. Delays between lines are the
 * capture gaps divided by [speed]; each event's `atMillis` is the capture timestamp, so a
 * downstream clock reads capture time, not wall time. Malformed lines are skipped.
 */
class ReplayMeshSource(
    private val lines: Sequence<String>,
    private val speed: Double = 1.0,
) : MeshSource {

    init {
        require(speed > 0.0) { "speed must be positive" }
    }

    override val events: Flow<MeshEvent> = flow {
        var previous: Long? = null
        var ownNode: Long? = null
        val seen = HashSet<Long>()
        var started = false
        for (line in lines) {
            val tab = line.indexOf('\t')
            if (tab < 0) continue
            val at = line.substring(0, tab).trim().toLongOrNull() ?: continue
            val frame = decodeFrame(line.substring(tab + 1).trim()) ?: continue
            if (!started) {
                started = true
                emit(MeshEvent.Link(LinkState.Replaying, at))
            }
            val gap = previous?.let { at - it } ?: 0L
            previous = at
            if (gap > 0) delay((gap / speed).toLong())

            frame.my_info?.let { ownNode = it.my_node_num.toLong() and 0xFFFFFFFFL }
            frame.node_info?.let { info ->
                val num = info.num.toLong() and 0xFFFFFFFFL
                emit(info.toNodeSeen(seen.add(num), at))
            }
            val packet = frame.packet ?: continue
            val data = packet.decoded
            if (data == null) {
                RawFrameMapper.map(frame, at)?.let { emit(it) }
                continue
            }
            PacketMapper.map(packet, at)?.let { heard ->
                emit(heard)
                if (heard.kind == PacketKind.Telemetry) {
                    val t = try {
                        Telemetry.ADAPTER.decode(data.payload)
                    } catch (_: Exception) {
                        null
                    }
                    t?.toEnvironment(heard.from, at)?.let { emit(it) }
                    if (heard.from == ownNode) t?.toVitals(at)?.let { emit(it) }
                }
            }
        }
    }
}

private fun decodeFrame(b64: String): FromRadio? = try {
    FromRadio.ADAPTER.decode(Base64.decode(b64))
} catch (_: Exception) {
    null
}
