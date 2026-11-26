package org.meshtastic.ambient.mesh

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.Telemetry
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.NodeChange
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient

/** The slice of [RadioClient] the source reads; [RadioClient] is final, so tests feed this instead. */
internal interface SdkFeed {
    val packets: Flow<MeshPacket>
    val fromRadio: Flow<FromRadio>
    val nodes: Flow<NodeChange>
    val connection: Flow<ConnectionState>
    fun ownNodeNum(): Long?
    fun telemetry(node: Long): Flow<Telemetry>
}

private class RadioClientFeed(private val client: RadioClient) : SdkFeed {
    override val packets get() = client.packets
    override val fromRadio get() = client.fromRadio
    override val nodes get() = client.nodes
    override val connection get() = client.connection
    override fun ownNodeNum(): Long? = client.ownNode.value?.num?.toLong()?.and(0xFFFFFFFFL)
    override fun telemetry(node: Long): Flow<Telemetry> = client.telemetry.observe(NodeId(node.toInt()))
}

/**
 * Live mesh through the SDK. After every `Connected` the source emits state-only events
 * (`NodeSeen`, `Environment`, `Vitals`) and no `PacketHeard` for [warmUpMillis], because the
 * node snapshot and retained handshake packets flush at that point.
 */
class SdkMeshSource internal constructor(
    private val feed: SdkFeed,
    private val warmUpMillis: Long,
    private val nowMillis: () -> Long,
) : MeshSource {

    constructor(client: RadioClient, warmUpMillis: Long = 5_000, nowMillis: () -> Long) :
        this(RadioClientFeed(client), warmUpMillis, nowMillis)

    private val seenThisSession = HashSet<Long>()

    override val events: Flow<MeshEvent> = channelFlow {
        var warmUntil = Long.MAX_VALUE

        launch {
            feed.packets.collect { p ->
                if (nowMillis() >= warmUntil) PacketMapper.map(p, nowMillis())?.let { send(it) }
            }
        }
        launch {
            feed.fromRadio.collect { f ->
                if (nowMillis() >= warmUntil) RawFrameMapper.map(f, nowMillis())?.let { send(it) }
            }
        }
        feed.connection.collectLatest { state ->
            when (state) {
                is ConnectionState.Connected -> coroutineScope {
                    warmUntil = nowMillis() + warmUpMillis
                    send(MeshEvent.Link(LinkState.WarmingUp, nowMillis()))
                    launch {
                        delay(warmUpMillis)
                        send(MeshEvent.Link(LinkState.Connected, nowMillis()))
                    }
                    launch { observeNodes(this) { send(it) } }
                }
                is ConnectionState.Connecting, is ConnectionState.Configuring -> {
                    warmUntil = Long.MAX_VALUE
                    send(MeshEvent.Link(LinkState.Connecting, nowMillis()))
                }
                else -> {
                    warmUntil = Long.MAX_VALUE
                    send(MeshEvent.Link(LinkState.Lost, nowMillis()))
                }
            }
        }
    }

    private suspend fun observeNodes(scope: kotlinx.coroutines.CoroutineScope, emit: suspend (MeshEvent) -> Unit) {
        val watching = HashSet<Long>()
        suspend fun seen(info: NodeInfo) {
            val num = info.num.toLong() and 0xFFFFFFFFL
            val first = seenThisSession.add(num)
            emit(info.toNodeSeen(first, nowMillis()))
            if (watching.add(num)) {
                scope.launch {
                    feed.telemetry(num).collect { t ->
                        t.toEnvironment(num, nowMillis())?.let { emit(it) }
                        if (num == feed.ownNodeNum()) t.toVitals(nowMillis())?.let { emit(it) }
                    }
                }
            }
        }
        feed.nodes.collect { change ->
            when (change) {
                is NodeChange.Snapshot -> change.nodes.values.forEach { seen(it) }
                is NodeChange.Added -> seen(change.node)
                is NodeChange.Updated -> seen(change.node)
                is NodeChange.Removed -> {
                    val num = change.nodeId.raw.toLong() and 0xFFFFFFFFL
                    emit(MeshEvent.NodeGone(num, nowMillis()))
                }
                else -> Unit
            }
        }
    }
}
