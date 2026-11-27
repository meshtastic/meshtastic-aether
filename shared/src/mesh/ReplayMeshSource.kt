package org.meshtastic.aether.mesh

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.Telemetry
import kotlin.io.encoding.Base64

/**
 * Replays a capture: lines of `<epochMillis>\t<base64 FromRadio>`. Delays between lines are the
 * capture gaps divided by [speed]; each event's `atMillis` is the capture timestamp, so a
 * downstream clock reads capture time, not wall time. Malformed lines are skipped.
 */
internal class ReplayMeshSource(
    private val lines: Sequence<String>,
    private val speed: Double = 1.0,
) : MeshSource {

    init {
        require(speed > 0.0) { "speed must be positive" }
    }

    override val events: Flow<MeshEvent> = flow {
        val identity = ReplayIdentity()
        var previous: Long? = null
        for (line in lines) {
            val (at, frame) = parseLine(line) ?: continue
            if (previous == null) emit(MeshEvent.Link(LinkState.Replaying, at))
            val gap = previous?.let { at - it } ?: 0L
            previous = at
            if (gap > 0) delay((gap / speed).toLong())
            emitFrame(frame, at, identity)
        }
    }
}

/** What the replay has learned about the capturing node so far. */
private class ReplayIdentity {
    var ownNode: Long? = null
    var lastSelf: MeshEvent.Self? = null
    val seen = HashSet<Long>()
}

/** The timestamp and frame of one capture line; null when the line is malformed. */
private fun parseLine(line: String): Pair<Long, FromRadio>? {
    val tab = line.indexOf('\t')
    val at = if (tab < 0) null else line.substring(0, tab).trim().toLongOrNull()
    val frame = at?.let { decodeFrame(line.substring(tab + 1).trim()) }
    return if (at == null || frame == null) null else at to frame
}

private suspend fun FlowCollector<MeshEvent>.emitFrame(frame: FromRadio, at: Long, identity: ReplayIdentity) {
    frame.my_info?.let {
        val own = it.my_node_num.toNodeNum()
        identity.ownNode = own
        if (own != identity.lastSelf?.node) {
            emit(MeshEvent.Self(own, null, null, at).also { s -> identity.lastSelf = s })
        }
    }
    frame.node_info?.let { info ->
        val num = info.num.toNodeNum()
        val node = info.toNodeSeen(identity.seen.add(num), at)
        emit(node)
        val last = identity.lastSelf
        if (num == identity.ownNode && (node.name != last?.name || node.position != last?.position)) {
            emit(MeshEvent.Self(num, node.name, node.position, at).also { s -> identity.lastSelf = s })
        }
    }
    val packet = frame.packet ?: return
    val data = packet.decoded
    if (data == null) {
        RawFrameMapper.map(frame, at, identity.ownNode)?.let { emit(it) }
    } else {
        emitDecoded(packet, data, at, identity.ownNode)
    }
}

private suspend fun FlowCollector<MeshEvent>.emitDecoded(packet: MeshPacket, data: Data, at: Long, ownNode: Long?) {
    val heard = PacketMapper.map(packet, at, ownNode) ?: return
    emit(heard)
    packet.toNeighbors(at)?.let { emit(it) }
    packet.toRoute(at)?.let { emit(it) }
    if (heard.kind != PacketKind.Telemetry) return
    val t = try {
        Telemetry.ADAPTER.decode(data.payload)
    } catch (_: Exception) {
        null
    }
    t?.toEnvironment(heard.from, at)?.let { emit(it) }
    t?.toHealth(heard.from, at)?.let { emit(it) }
    if (heard.from == ownNode) {
        t?.toVitals(at)?.let { emit(it) }
        t?.toStats(at)?.let { emit(it) }
    }
}

private fun decodeFrame(b64: String): FromRadio? = try {
    FromRadio.ADAPTER.decode(Base64.decode(b64))
} catch (_: Exception) {
    null
}
