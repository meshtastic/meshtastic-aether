package org.meshtastic.ambient.mesh

import kotlinx.coroutines.Job
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
    val ownNode: Flow<NodeInfo?>
    fun ownNodeNum(): Long?
    fun telemetry(node: Long): Flow<Telemetry>
}

private class RadioClientFeed(private val client: RadioClient) : SdkFeed {
    override val packets get() = client.packets
    override val fromRadio get() = client.fromRadio
    override val nodes get() = client.nodes
    override val connection get() = client.connection
    override val ownNode get() = client.ownNode
    override fun ownNodeNum(): Long? = client.ownNode.value?.num?.toLong()?.and(0xFFFFFFFFL)
    override fun telemetry(node: Long): Flow<Telemetry> = client.telemetry.observe(NodeId(node.toInt()))
}

/**
 * Live mesh through the SDK. After every `Connected` the source emits state-only events
 * (`NodeSeen`, `Environment`, `Health`, `Vitals`, `Stats`, `Self`) and no `PacketHeard`, `Neighbors` or `Route` for [warmUpMillis], because the
 * node snapshot and retained handshake packets flush at that point.
 */
internal class SdkMeshSource internal constructor(
    private val feed: SdkFeed,
    private val warmUpMillis: Long,
    private val nowMillis: () -> Long,
) : MeshSource {

    constructor(client: RadioClient, warmUpMillis: Long = WARM_UP_MILLIS, nowMillis: () -> Long) :
        this(RadioClientFeed(client), warmUpMillis, nowMillis)

    private val seenThisSession = HashSet<Long>()

    override val events: Flow<MeshEvent> = channelFlow {
        var warmUntil = Long.MAX_VALUE

        launch {
            feed.packets.collect { p ->
                if (nowMillis() < warmUntil) return@collect
                PacketMapper.map(p, nowMillis(), feed.ownNodeNum())?.let { send(it) }
                p.toNeighbors(nowMillis())?.let { send(it) }
                p.toRoute(nowMillis())?.let { send(it) }
            }
        }
        launch {
            feed.fromRadio.collect { f ->
                if (nowMillis() >= warmUntil) RawFrameMapper.map(f, nowMillis(), feed.ownNodeNum())?.let { send(it) }
            }
        }
        launch {
            var last: MeshEvent.Self? = null
            feed.ownNode.collect { info ->
                val seen = info?.toNodeSeen(false, nowMillis()) ?: return@collect
                val prev = last
                if (prev == null || prev.node != seen.node || prev.name != seen.name || prev.position != seen.position) {
                    MeshEvent.Self(seen.node, seen.name, seen.position, nowMillis()).also { last = it; send(it) }
                }
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
        val watching = HashMap<Long, Job>()
        suspend fun seen(info: NodeInfo) {
            val num = info.num.toLong() and 0xFFFFFFFFL
            val first = seenThisSession.add(num)
            emit(info.toNodeSeen(first, nowMillis()))
            if (num !in watching) {
                watching[num] = scope.launch {
                    feed.telemetry(num).collect { t ->
                        t.toEnvironment(num, nowMillis())?.let { emit(it) }
                        t.toHealth(num, nowMillis())?.let { emit(it) }
                        if (num == feed.ownNodeNum()) {
                            t.toVitals(nowMillis())?.let { emit(it) }
                            t.toStats(nowMillis())?.let { emit(it) }
                        }
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
                    watching.remove(num)?.cancel()
                    emit(MeshEvent.NodeGone(num, nowMillis()))
                }
                else -> Unit
            }
        }
    }
}

/** How long after a connect the source reports state only: the node snapshot and retained packets flush then. */
internal const val WARM_UP_MILLIS = 5_000L
