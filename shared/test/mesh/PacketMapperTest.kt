package org.meshtastic.ambient.mesh

import org.meshtastic.proto.PortNum
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
}
