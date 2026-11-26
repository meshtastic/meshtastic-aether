package org.meshtastic.aether.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.LocalStats
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.Neighbor
import org.meshtastic.proto.NeighborInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.NodeChange
import org.meshtastic.sdk.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeFeed : SdkFeed {
    val packetFlow = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 64)
    val rawFlow = MutableSharedFlow<FromRadio>(extraBufferCapacity = 64)
    val nodeFlow = MutableSharedFlow<NodeChange>(extraBufferCapacity = 64)
    val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val telemetryFlows = HashMap<Long, MutableSharedFlow<Telemetry>>()
    var own: Long? = null

    override val packets: Flow<MeshPacket> get() = packetFlow
    override val fromRadio: Flow<FromRadio> get() = rawFlow
    override val nodes: Flow<NodeChange> get() = nodeFlow
    override val connection: Flow<ConnectionState> get() = state
    val ownFlow = MutableStateFlow<NodeInfo?>(null)
    override val ownNode: Flow<NodeInfo?> get() = ownFlow
    override fun ownNodeNum(): Long? = own
    override fun telemetry(node: Long): Flow<Telemetry> =
        telemetryFlows.getOrPut(node) { MutableSharedFlow(extraBufferCapacity = 16) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SdkMeshSourceTest {
    private fun text(from: Int) = decodedPacket(from, PortNum.TEXT_MESSAGE_APP, hopStart = 3, hopLimit = 2)

    @Test
    fun `packets inside the warm-up window emit nothing and after it emit normally`() = runTest {
        val feed = FakeFeed()
        val source = SdkMeshSource(feed, 5_000L) { currentTime }
        val got = ArrayList<MeshEvent>()
        backgroundScope.launch { source.events.collect { got += it } }
        runCurrent()

        feed.state.value = ConnectionState.Connected
        runCurrent()
        feed.packetFlow.emit(text(1))
        feed.rawFlow.emit(frame(encryptedPacket(2)))
        advanceTimeBy(4_999)
        feed.packetFlow.emit(text(3))
        runCurrent()
        assertTrue(got.none { it is MeshEvent.PacketHeard }, "heard during warm-up: $got")

        advanceTimeBy(1)
        runCurrent()
        feed.packetFlow.emit(text(4))
        feed.rawFlow.emit(frame(encryptedPacket(5)))
        runCurrent()
        val heard = got.filterIsInstance<MeshEvent.PacketHeard>()
        assertEquals(listOf(4L to PacketKind.Text, 5L to PacketKind.Encrypted), heard.map { it.from to it.kind })
    }

    @Test
    fun `packets before the first connect are dropped`() = runTest {
        val feed = FakeFeed()
        val source = SdkMeshSource(feed, 100L) { currentTime }
        val got = ArrayList<MeshEvent>()
        backgroundScope.launch { source.events.collect { got += it } }
        runCurrent()
        feed.packetFlow.emit(text(1))
        runCurrent()
        assertTrue(got.none { it is MeshEvent.PacketHeard })
    }

    @Test
    fun `link events follow the connection and warm-up ends in Connected`() = runTest {
        val feed = FakeFeed()
        val source = SdkMeshSource(feed, 5_000L) { currentTime }
        val got = ArrayList<MeshEvent.Link>()
        backgroundScope.launch { source.events.collect { if (it is MeshEvent.Link) got += it } }
        runCurrent()
        feed.state.value = ConnectionState.Connecting(1)
        runCurrent()
        feed.state.value = ConnectionState.Connected
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        feed.state.value = ConnectionState.Disconnected
        runCurrent()
        assertEquals(
            listOf(LinkState.Lost, LinkState.Connecting, LinkState.WarmingUp, LinkState.Connected, LinkState.Lost),
            got.map { it.state },
        )
        assertEquals(5_000L, got[3].atMillis - got[2].atMillis)
    }

    @Test
    fun `a reconnect opens a new warm-up window`() = runTest {
        val feed = FakeFeed()
        val source = SdkMeshSource(feed, 5_000L) { currentTime }
        val got = ArrayList<MeshEvent>()
        backgroundScope.launch { source.events.collect { got += it } }
        runCurrent()
        feed.state.value = ConnectionState.Connected
        advanceTimeBy(6_000)
        feed.state.value = ConnectionState.Disconnected
        runCurrent()
        advanceTimeBy(10_000)
        feed.state.value = ConnectionState.Connected
        runCurrent()
        feed.packetFlow.emit(text(7))
        runCurrent()
        assertTrue(got.none { it is MeshEvent.PacketHeard }, "snapshot flush after reconnect leaked: $got")
        advanceTimeBy(5_000)
        runCurrent()
        feed.packetFlow.emit(text(8))
        runCurrent()
        assertEquals(listOf(8L), got.filterIsInstance<MeshEvent.PacketHeard>().map { it.from })
    }

    @Test
    fun `node snapshots and environment telemetry flow through during warm-up`() = runTest {
        val feed = FakeFeed()
        val source = SdkMeshSource(feed, 5_000L) { currentTime }
        val got = ArrayList<MeshEvent>()
        backgroundScope.launch { source.events.collect { got += it } }
        runCurrent()
        feed.state.value = ConnectionState.Connected
        runCurrent()

        val info = NodeInfo.Builder().also { n ->
            n.num = 0x42
            n.user = User.Builder().also { it.long_name = "Hilltop" }.build()
            n.position = Position.Builder().also {
                it.latitude_i = 450_000_000
                it.longitude_i = -1_220_000_000
            }.build()
        }.build()
        feed.nodeFlow.emit(NodeChange.Snapshot(mapOf(NodeId(0x42) to info)))
        runCurrent()
        feed.telemetryFlows.getValue(0x42L).emit(envTelemetry(21.5f, 40f, 1010f))
        runCurrent()

        val seen = got.filterIsInstance<MeshEvent.NodeSeen>().single()
        assertEquals(0x42L, seen.node)
        assertEquals("Hilltop", seen.name)
        assertEquals(GeoPoint(45.0, -122.0), seen.position)
        assertTrue(seen.firstThisSession)
        val env = got.filterIsInstance<MeshEvent.Environment>().single()
        assertEquals(21.5f, env.temperatureC)
        assertEquals(40f, env.humidityPct)
        assertEquals(1010f, env.pressureHpa)
    }

    @Test
    fun `a node with no user and no position is seen with a null name and null position`() = runTest {
        val feed = FakeFeed()
        val source = SdkMeshSource(feed, 0L) { currentTime }
        val got = ArrayList<MeshEvent>()
        backgroundScope.launch { source.events.collect { got += it } }
        runCurrent()
        feed.state.value = ConnectionState.Connected
        runCurrent()
        feed.nodeFlow.emit(NodeChange.Added(NodeInfo.Builder().also { it.num = 9 }.build()))
        runCurrent()
        val seen = got.filterIsInstance<MeshEvent.NodeSeen>().single()
        assertEquals(null, seen.name)
        assertEquals(null, seen.position)
    }

    private fun nodeInfo(num: Int) = NodeInfo.Builder().also { it.num = num }.build()

    private fun TestScope.connected(feed: FakeFeed, got: MutableList<MeshEvent>) {
        val source = SdkMeshSource(feed, 0L) { currentTime }
        backgroundScope.launch { source.events.collect { got += it } }
        runCurrent()
        feed.state.value = ConnectionState.Connected
        runCurrent()
    }

    @Test
    fun `a node announced twice has one telemetry collector so one reading is one event`() = runTest {
        val feed = FakeFeed()
        val got = ArrayList<MeshEvent>()
        connected(feed, got)
        feed.nodeFlow.emit(NodeChange.Added(nodeInfo(0x42)))
        runCurrent()
        feed.nodeFlow.emit(NodeChange.Updated(nodeInfo(0x42), emptySet()))
        runCurrent()
        feed.telemetryFlows.getValue(0x42L).emit(envTelemetry(20f, 50f, 1000f))
        runCurrent()
        assertEquals(1, got.filterIsInstance<MeshEvent.Environment>().size, got.toString())
    }

    @Test
    fun `telemetry from a removed node no longer reaches the stream`() = runTest {
        val feed = FakeFeed()
        val got = ArrayList<MeshEvent>()
        connected(feed, got)
        feed.nodeFlow.emit(NodeChange.Added(nodeInfo(0x42)))
        runCurrent()
        val flow = feed.telemetryFlows.getValue(0x42L)
        assertEquals(1, flow.subscriptionCount.value)
        feed.nodeFlow.emit(NodeChange.Removed(NodeId(0x42)))
        runCurrent()
        assertEquals(0, flow.subscriptionCount.value, "collector still subscribed")
        flow.emit(envTelemetry(20f, 50f, 1000f))
        runCurrent()
        assertTrue(got.none { it is MeshEvent.Environment }, got.toString())
        assertTrue(got.any { it is MeshEvent.NodeGone })
    }

    @Test
    fun `a node that returns after removal is watched again`() = runTest {
        val feed = FakeFeed()
        val got = ArrayList<MeshEvent>()
        connected(feed, got)
        feed.nodeFlow.emit(NodeChange.Added(nodeInfo(0x42)))
        runCurrent()
        feed.nodeFlow.emit(NodeChange.Removed(NodeId(0x42)))
        runCurrent()
        feed.nodeFlow.emit(NodeChange.Added(nodeInfo(0x42)))
        runCurrent()
        feed.telemetryFlows.getValue(0x42L).emit(envTelemetry(20f, 50f, 1000f))
        runCurrent()
        assertEquals(1, got.filterIsInstance<MeshEvent.Environment>().size, got.toString())
    }

    private fun own(num: Int, name: String?, lat: Int = 0, lon: Int = 0) = NodeInfo.Builder().also { n ->
        n.num = num
        if (name != null) n.user = User.Builder().also { it.long_name = name }.build()
        if (lat != 0) n.position = Position.Builder().also { it.latitude_i = lat; it.longitude_i = lon }.build()
    }.build()

    @Test
    fun `Self is emitted once per change in node name or position even during warm-up`() = runTest {
        val feed = FakeFeed()
        val source = SdkMeshSource(feed, 5_000L) { currentTime }
        val got = ArrayList<MeshEvent>()
        backgroundScope.launch { source.events.collect { got += it } }
        runCurrent()
        feed.state.value = ConnectionState.Connected
        runCurrent()
        feed.ownFlow.value = own(0x42, "Home")
        runCurrent()
        assertEquals(listOf(MeshEvent.Self(0x42L, "Home", null, 0L)), got.filterIsInstance<MeshEvent.Self>())
        feed.ownFlow.value = own(0x42, "Home").newBuilder().also { it.snr = 4f }.build()
        runCurrent()
        assertEquals(1, got.filterIsInstance<MeshEvent.Self>().size, "unrelated NodeInfo change re-emitted")
        feed.ownFlow.value = own(0x42, "Hilltop")
        runCurrent()
        feed.ownFlow.value = own(0x42, "Hilltop", 450_000_000, -1_220_000_000)
        runCurrent()
        val selfs = got.filterIsInstance<MeshEvent.Self>()
        assertEquals(listOf("Home", "Hilltop", "Hilltop"), selfs.map { it.name })
        assertEquals(GeoPoint(45.0, -122.0), selfs.last().position)
    }

    @Test
    fun `NeighborInfo and traceroute packets become Neighbors and Route after warm-up only`() = runTest {
        val feed = FakeFeed()
        val source = SdkMeshSource(feed, 1_000L) { currentTime }
        val got = ArrayList<MeshEvent>()
        backgroundScope.launch { source.events.collect { got += it } }
        runCurrent()
        feed.state.value = ConnectionState.Connected
        runCurrent()
        val nb = NeighborInfo.Builder().also { b ->
            b.node_id = 7
            b.neighbors = listOf(Neighbor.Builder().also { it.node_id = 8 }.build())
        }.build().encode()
        val tr = RouteDiscovery.Builder().also { it.route = listOf(0x11) }.build().encode()
        feed.packetFlow.emit(decodedPacket(7, PortNum.NEIGHBORINFO_APP, payload = nb))
        runCurrent()
        assertTrue(got.none { it is MeshEvent.Neighbors }, "retained packet leaked: $got")
        advanceTimeBy(1_000)
        runCurrent()
        feed.packetFlow.emit(decodedPacket(7, PortNum.NEIGHBORINFO_APP, payload = nb))
        feed.packetFlow.emit(decodedPacket(0x10, PortNum.TRACEROUTE_APP, to = 0x20, payload = tr))
        runCurrent()
        assertEquals(listOf(MeshEvent.Neighbors(7L, listOf(8L), currentTime)), got.filterIsInstance<MeshEvent.Neighbors>())
        assertEquals(listOf(listOf(0x10L, 0x11L, 0x20L)), got.filterIsInstance<MeshEvent.Route>().map { it.nodes })
    }

    @Test
    fun `packets are marked toUs against the node number the client learned`() = runTest {
        val feed = FakeFeed()
        feed.own = 0x42L
        val source = SdkMeshSource(feed, 0L) { currentTime }
        val got = ArrayList<MeshEvent>()
        backgroundScope.launch { source.events.collect { got += it } }
        runCurrent()
        feed.state.value = ConnectionState.Connected
        runCurrent()
        feed.packetFlow.emit(decodedPacket(1, PortNum.TEXT_MESSAGE_APP, to = 0x42))
        feed.packetFlow.emit(decodedPacket(2, PortNum.TEXT_MESSAGE_APP, to = -1))
        runCurrent()
        assertEquals(listOf(true, false), got.filterIsInstance<MeshEvent.PacketHeard>().map { it.toUs })
    }

    private fun deviceTelemetry(battery: Int, volts: Float, uptime: Int) = Telemetry.Builder().also { t ->
        t.device_metrics = DeviceMetrics.Builder().also {
            it.battery_level = battery
            it.voltage = volts
            it.uptime_seconds = uptime
        }.build()
    }.build()

    private fun statsTelemetry() = Telemetry.Builder().also { t ->
        t.local_stats = LocalStats.Builder().also {
            it.num_packets_rx = 50
            it.noise_floor = -105
            it.uptime_seconds = 90
        }.build()
    }.build()

    @Test
    fun `Health flows for every node and Stats only for our own and both during warm-up`() = runTest {
        val feed = FakeFeed()
        feed.own = 0x42L
        val source = SdkMeshSource(feed, 5_000L) { currentTime }
        val got = ArrayList<MeshEvent>()
        backgroundScope.launch { source.events.collect { got += it } }
        runCurrent()
        feed.state.value = ConnectionState.Connected
        runCurrent()
        feed.nodeFlow.emit(NodeChange.Snapshot(mapOf(NodeId(0x42) to nodeInfo(0x42), NodeId(0x43) to nodeInfo(0x43))))
        runCurrent()
        feed.telemetryFlows.getValue(0x43L).emit(deviceTelemetry(60, 3.9f, 100))
        feed.telemetryFlows.getValue(0x43L).emit(statsTelemetry())
        feed.telemetryFlows.getValue(0x42L).emit(deviceTelemetry(101, 5.0f, 200))
        feed.telemetryFlows.getValue(0x42L).emit(statsTelemetry())
        runCurrent()
        assertEquals(
            listOf(MeshEvent.Health(0x43L, 60, 3.9f, 100L, 0L), MeshEvent.Health(0x42L, 101, 5.0f, 200L, 0L)),
            got.filterIsInstance<MeshEvent.Health>(),
        )
        val stats = got.filterIsInstance<MeshEvent.Stats>().single()
        assertEquals(50L, stats.rxTotal)
        assertEquals(-105, stats.noiseFloorDbm)
        assertEquals(90L, stats.uptimeSeconds)
    }
}
