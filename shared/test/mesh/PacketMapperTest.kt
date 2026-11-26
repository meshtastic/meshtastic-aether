package org.meshtastic.ambient.mesh

import org.meshtastic.proto.Neighbor
import org.meshtastic.proto.NeighborInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.RouteDiscovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PacketMapperTest {
    @Test
    fun `an encrypted frame from the radio maps to Encrypted`() {
        val e = assertNotNull(RawFrameMapper.map(frame(encryptedPacket(from = 0x1234)), 777L))
        assertEquals(PacketKind.Encrypted, e.kind)
        assertEquals(0x1234L, e.from)
        assertEquals(777L, e.atMillis)
    }

    @Test
    fun `a decoded packet inside a FromRadio frame is ignored by the raw mapper`() {
        val decoded = frame(decodedPacket(from = 5, port = PortNum.TEXT_MESSAGE_APP))
        assertNull(RawFrameMapper.map(decoded, 0L))
    }

    @Test
    fun `a FromRadio frame without a packet is ignored by the raw mapper`() {
        assertNull(RawFrameMapper.map(org.meshtastic.proto.FromRadio.Builder().build(), 0L))
    }

    @Test
    fun `the packet mapper leaves an undecoded packet to the raw mapper`() {
        assertNull(PacketMapper.map(encryptedPacket(from = 5), 0L))
    }

    @Test
    fun `hop_start zero gives unknown hops rather than zero`() {
        val e = assertNotNull(PacketMapper.map(decodedPacket(1, PortNum.TEXT_MESSAGE_APP, hopStart = 0, hopLimit = 0), 0L))
        assertNull(e.hopsTaken)
        val raw = assertNotNull(RawFrameMapper.map(frame(encryptedPacket(1, hopStart = 0, hopLimit = 3)), 0L))
        assertNull(raw.hopsTaken)
    }

    @Test
    fun `hop_start 3 with hop_limit 1 is two hops taken`() {
        val e = assertNotNull(PacketMapper.map(decodedPacket(1, PortNum.POSITION_APP, hopStart = 3, hopLimit = 1), 0L))
        assertEquals(2, e.hopsTaken)
        val raw = assertNotNull(RawFrameMapper.map(frame(encryptedPacket(1, hopStart = 3, hopLimit = 1)), 0L))
        assertEquals(2, raw.hopsTaken)
    }

    @Test
    fun `a packet that arrived directly has zero hops when hop_start is known`() {
        val e = assertNotNull(PacketMapper.map(decodedPacket(1, PortNum.POSITION_APP, hopStart = 3, hopLimit = 3), 0L))
        assertEquals(0, e.hopsTaken)
    }

    @Test
    fun `portnums map to the packet kinds of the firmware module they belong to`() {
        val expected = mapOf(
            PortNum.TEXT_MESSAGE_APP to PacketKind.Text,
            PortNum.TEXT_MESSAGE_COMPRESSED_APP to PacketKind.Text,
            PortNum.POSITION_APP to PacketKind.Position,
            PortNum.TELEMETRY_APP to PacketKind.Telemetry,
            PortNum.NODEINFO_APP to PacketKind.NodeInfo,
            PortNum.ROUTING_APP to PacketKind.Routing,
            PortNum.TRACEROUTE_APP to PacketKind.Traceroute,
            PortNum.NEIGHBORINFO_APP to PacketKind.NeighborInfo,
            PortNum.STORE_FORWARD_APP to PacketKind.Other,
            PortNum.UNKNOWN_APP to PacketKind.Other,
        )
        for ((port, kind) in expected) {
            val e = assertNotNull(PacketMapper.map(decodedPacket(1, port), 0L))
            assertEquals(kind, e.kind, "portnum $port")
        }
    }

    @Test
    fun `snr rssi and via_mqtt are carried through`() {
        val e = assertNotNull(
            PacketMapper.map(decodedPacket(1, PortNum.TEXT_MESSAGE_APP, snr = -7.25f, rssi = -103, viaMqtt = true), 0L),
        )
        assertEquals(-7.25f, e.snr)
        assertEquals(-103, e.rssi)
        assertEquals(true, e.viaMqtt)
    }

    @Test
    fun `a packet with no radio metadata has null snr and null rssi`() {
        val e = assertNotNull(PacketMapper.map(decodedPacket(1, PortNum.TEXT_MESSAGE_APP), 0L))
        assertNull(e.snr)
        assertNull(e.rssi)
        assertEquals(false, e.viaMqtt)
    }

    @Test
    fun `a node number with the high bit set stays positive`() {
        val e = assertNotNull(PacketMapper.map(decodedPacket(0xDEADBEEF.toInt(), PortNum.TEXT_MESSAGE_APP), 0L))
        assertEquals(0xDEADBEEFL, e.from)
    }

    private val broadcast = -1

    @Test
    fun `to and relay_node are carried and a zero relay_node means no relay`() {
        val e = assertNotNull(PacketMapper.map(decodedPacket(1, PortNum.TEXT_MESSAGE_APP, to = broadcast, relayNode = 0x1F), 0L))
        assertEquals(0xFFFFFFFFL, e.to)
        assertEquals(0x1F, e.relayByte)
        val direct = assertNotNull(PacketMapper.map(decodedPacket(1, PortNum.TEXT_MESSAGE_APP, to = 0x42), 0L))
        assertEquals(0x42L, direct.to)
        assertNull(direct.relayByte)
        val raw = assertNotNull(RawFrameMapper.map(frame(encryptedPacket(1, to = broadcast, relayNode = 0xA5)), 0L))
        assertEquals(0xFFFFFFFFL, raw.to)
        assertEquals(0xA5, raw.relayByte)
    }

    @Test
    fun `a packet with no destination set has a null to`() {
        val e = assertNotNull(PacketMapper.map(decodedPacket(1, PortNum.TEXT_MESSAGE_APP), 0L))
        assertNull(e.to)
    }

    @Test
    fun `toUs is true only when the destination is our own node`() {
        val own = 0x42L
        val dm = assertNotNull(PacketMapper.map(decodedPacket(1, PortNum.TEXT_MESSAGE_APP, to = 0x42), 0L, own))
        assertEquals(true, dm.toUs)
        val other = assertNotNull(PacketMapper.map(decodedPacket(1, PortNum.TEXT_MESSAGE_APP, to = 0x43), 0L, own))
        assertEquals(false, other.toUs)
        val bcast = assertNotNull(PacketMapper.map(decodedPacket(1, PortNum.TEXT_MESSAGE_APP, to = broadcast), 0L, own))
        assertEquals(false, bcast.toUs)
        val unknownOwn = assertNotNull(PacketMapper.map(decodedPacket(1, PortNum.TEXT_MESSAGE_APP, to = 0x42), 0L))
        assertEquals(false, unknownOwn.toUs)
        val raw = assertNotNull(RawFrameMapper.map(frame(encryptedPacket(1, to = 0x42)), 0L, own))
        assertEquals(true, raw.toUs)
    }

    private fun neighborPayload(reporter: Int, vararg heard: Int) = NeighborInfo.Builder().also { b ->
        b.node_id = reporter
        b.neighbors = heard.map { id -> Neighbor.Builder().also { it.node_id = id }.build() }
    }.build().encode()

    private fun routePayload(vararg hops: Int) = RouteDiscovery.Builder().also { it.route = hops.toList() }.build().encode()

    @Test
    fun `a NeighborInfo payload becomes edges from its reporting node`() {
        val p = decodedPacket(5, PortNum.NEIGHBORINFO_APP, payload = neighborPayload(7, 8, 9))
        assertEquals(MeshEvent.Neighbors(7L, listOf(8L, 9L), 11L), p.toNeighbors(11L))
    }

    @Test
    fun `an empty or undecodable NeighborInfo gives no event and other ports are ignored`() {
        assertNull(decodedPacket(5, PortNum.NEIGHBORINFO_APP, payload = neighborPayload(7)).toNeighbors(0L))
        assertNull(decodedPacket(5, PortNum.NEIGHBORINFO_APP, payload = byteArrayOf(0x0A, 0x7F)).toNeighbors(0L))
        assertNull(decodedPacket(5, PortNum.TEXT_MESSAGE_APP, payload = neighborPayload(7, 8)).toNeighbors(0L))
    }

    @Test
    fun `a traceroute request path runs from the sender through its route to us`() {
        val p = decodedPacket(0x10, PortNum.TRACEROUTE_APP, to = 0x20, payload = routePayload(0x11, 0x12))
        assertEquals(MeshEvent.Route(listOf(0x10L, 0x11L, 0x12L, 0x20L), 3L), p.toRoute(3L))
    }

    @Test
    fun `a traceroute reply path starts at the requester who is the destination`() {
        val p = decodedPacket(0x30, PortNum.TRACEROUTE_APP, to = 0x20, payload = routePayload(0x11), requestId = 99)
        assertEquals(MeshEvent.Route(listOf(0x20L, 0x11L, 0x30L), 3L), p.toRoute(3L))
    }

    @Test
    fun `an undecodable traceroute payload gives no route`() {
        assertNull(decodedPacket(1, PortNum.TRACEROUTE_APP, to = 2, payload = byteArrayOf(0x0A, 0x7F)).toRoute(0L))
    }
}
