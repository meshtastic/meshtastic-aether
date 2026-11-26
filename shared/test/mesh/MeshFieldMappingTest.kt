package org.meshtastic.aether.mesh

import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.EnvironmentMetrics
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.LocalStats
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MeshFieldMappingTest {
    private fun node(user: User? = null, position: Position? = null) = NodeInfo.Builder().also {
        it.num = 0x42
        it.user = user
        it.position = position
    }.build()

    private fun user(role: Config.DeviceConfig.Role, hw: HardwareModel) = User.Builder().also {
        it.long_name = "n"
        it.role = role
        it.hw_model = hw
    }.build()

    @Test
    fun `a text counts characters rather than bytes or UTF-16 units`() {
        // h, e-acute, l, l, o, space, thumbs-up: 7 characters, 7 + 4 = 11 UTF-8 bytes, 8 UTF-16 units
        val payload = "héllo 👍".encodeUtf8().toByteArray()
        val e = assertNotNull(PacketMapper.map(decodedPacket(1, PortNum.TEXT_MESSAGE_APP, payload = payload), 0L))
        assertEquals(7, e.textLength)
        assertEquals(11, e.payloadBytes)
    }

    @Test
    fun `only a plain text packet has a text length`() {
        val payload = ByteArray(9) { 0x41 }
        val pos = assertNotNull(PacketMapper.map(decodedPacket(1, PortNum.POSITION_APP, payload = payload), 0L))
        assertNull(pos.textLength)
        assertEquals(9, pos.payloadBytes)
        val compressed = assertNotNull(PacketMapper.map(decodedPacket(1, PortNum.TEXT_MESSAGE_COMPRESSED_APP, payload = payload), 0L))
        assertNull(compressed.textLength)
    }

    @Test
    fun `an encrypted frame reports its ciphertext size and no channel index`() {
        val e = assertNotNull(RawFrameMapper.map(frame(encryptedPacket(1)), 0L))
        assertEquals(4, e.payloadBytes)
        assertNull(e.channel)
        assertNull(e.textLength)
    }

    @Test
    fun `a decoded packet carries the channel index it arrived on`() {
        val p = decodedPacket(1, PortNum.TEXT_MESSAGE_APP).newBuilder().also { it.channel = 2 }.build()
        assertEquals(2, PacketMapper.map(p, 0L)?.channel)
    }

    @Test
    fun `role and hardware come through as the proto enum names`() {
        val seen = node(user(Config.DeviceConfig.Role.ROUTER, HardwareModel.HELTEC_V3)).toNodeSeen(true, 0L)
        assertEquals("ROUTER", seen.role)
        assertEquals("HELTEC_V3", seen.hardware)
    }

    @Test
    fun `an unset hardware model and a missing user are unknown rather than named`() {
        val unset = node(user(Config.DeviceConfig.Role.TRACKER, HardwareModel.UNSET)).toNodeSeen(true, 0L)
        assertEquals("TRACKER", unset.role)
        assertNull(unset.hardware)
        val none = node().toNodeSeen(true, 0L)
        assertNull(none.role)
        assertNull(none.hardware)
    }

    @Test
    fun `position altitude lands in the GeoPoint`() {
        val withAlt = node(position = Position.Builder().also {
            it.latitude_i = 450_000_000
            it.longitude_i = -1_220_000_000
            it.altitude = 123
        }.build()).toNodeSeen(true, 0L)
        assertEquals(GeoPoint(45.0, -122.0, 123), withAlt.position)
        val without = node(position = Position.Builder().also {
            it.latitude_i = 450_000_000
            it.longitude_i = -1_220_000_000
        }.build()).toNodeSeen(true, 0L)
        assertNull(without.position?.altitudeM)
    }

    @Test
    fun `wind direction gust and lull map beside the speed`() {
        val t = Telemetry.Builder().also { b ->
            b.environment_metrics = EnvironmentMetrics.Builder().also {
                it.wind_direction = 270
                it.wind_speed = 4f
                it.wind_gust = 9.5f
                it.wind_lull = 1.5f
            }.build()
        }.build()
        val e = assertNotNull(t.toEnvironment(7L, 0L))
        assertEquals(270f, e.windDirectionDeg)
        assertEquals(4f, e.windMps)
        assertEquals(9.5f, e.windGustMps)
        assertEquals(1.5f, e.windLullMps)
        assertNull(envTelemetry(10f, 50f, 1000f).toEnvironment(7L, 0L)?.windDirectionDeg)
    }

    private fun device(block: (DeviceMetrics.Builder) -> Unit) = Telemetry.Builder().also {
        it.device_metrics = DeviceMetrics.Builder().also(block).build()
    }.build()

    @Test
    fun `device metrics become Health with an unsigned uptime`() {
        val h = assertNotNull(
            device {
                it.battery_level = 101
                it.voltage = 4.1f
                it.uptime_seconds = Int.MIN_VALUE
            }.toHealth(9L, 5L),
        )
        assertEquals(MeshEvent.Health(9L, 101, 4.1f, 2_147_483_648L, 5L), h)
    }

    @Test
    // Channel use is on nearly every node's device metrics, so a packet carrying only it still counts toward the mesh's channel use.
    fun `a device packet with only channel utilization is Health carrying it and environment alone is none`() {
        val h = device { it.channel_utilization = 12f; it.air_util_tx = 1.5f }.toHealth(9L, 0L)
        assertEquals(12f, h?.channelUtilPct)
        assertEquals(1.5f, h?.airUtilTxPct)
        assertNull(h?.batteryPct)
        assertNull(envTelemetry(10f, 50f, 1000f).toHealth(9L, 0L))
    }

    @Test
    fun `local stats become Stats and zero means not reported for nodes and noise`() {
        val full = Telemetry.Builder().also { t ->
            t.local_stats = LocalStats.Builder().also {
                it.num_online_nodes = 12
                it.num_total_nodes = 203
                it.num_packets_rx = 1000
                it.num_rx_dupe = 300
                it.num_packets_rx_bad = 20
                it.noise_floor = -108
                it.uptime_seconds = 7200
            }.build()
        }.build()
        assertEquals(MeshEvent.Stats(12, 203, 1000L, 300L, 20L, -108, 7200L, 3L), full.toStats(3L))
        val sparse = Telemetry.Builder().also { it.local_stats = LocalStats.Builder().also { s -> s.uptime_seconds = 60 }.build() }.build()
        val s = assertNotNull(sparse.toStats(0L))
        assertNull(s.numOnline)
        assertNull(s.numTotal)
        assertNull(s.noiseFloorDbm)
        assertNull(device { it.battery_level = 50 }.toStats(0L))
    }
}
