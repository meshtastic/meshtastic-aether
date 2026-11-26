package org.meshtastic.ambient.mesh

import okio.ByteString.Companion.toByteString
import org.meshtastic.ambient.music.Rng
import org.meshtastic.proto.Data
import org.meshtastic.proto.EnvironmentMetrics
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User
import kotlin.io.encoding.Base64

/**
 * A synthetic capture in the replay format (`<epochMillis>\t<base64 FromRadio>`): nine named nodes around a made-up
 * origin, a few minutes of mixed traffic and a drifting temperature. No real node data; same bytes every call.
 */
internal object DemoCapture {
    private const val BASE = 1_700_000_000_000L
    private const val NODES = 9
    private const val SECONDS = 240

    fun lines(): Sequence<String> = sequence {
        val rng = Rng(20260101L)
        for (i in 0 until NODES) {
            val bearing = i * 40.0 * kotlin.math.PI / 180.0
            val dist = 0.002 + 0.004 * rng.nextDouble()
            yield(line(BASE, nodeInfo(num(i), "Demo ${i + 1}", 45.0 + dist * kotlin.math.cos(bearing), -93.0 + dist * kotlin.math.sin(bearing))))
        }
        yield(line(BASE, nodeInfo(num(NODES), "Far MQTT", 51.5, -0.1)))
        var t = 1000L
        while (t < SECONDS * 1000L) {
            val node = num(rng.nextInt(NODES))
            val roll = rng.nextDouble()
            val packet = when {
                roll < 0.04 -> packet(node, PortNum.TEXT_MESSAGE_APP, 3, 2, 4f)
                roll < 0.30 -> packet(node, PortNum.POSITION_APP, 3, 3 - rng.nextInt(3), -3f + 8f * rng.nextDouble().toFloat())
                roll < 0.45 -> telemetry(node, 14f + 6f * (t / (SECONDS * 1000f)), 55f, 1008f)
                roll < 0.60 -> packet(node, PortNum.NODEINFO_APP, 3, 1, -8f)
                roll < 0.68 -> packet(num(NODES), PortNum.POSITION_APP, 7, 2, -12f)
                roll < 0.85 -> encrypted(node, 3, 2 - rng.nextInt(2))
                else -> packet(node, PortNum.TRACEROUTE_APP, 3, 1, 0f)
            }
            yield(line(BASE + t, FromRadio.Builder().also { it.packet = packet }.build()))
            t += 800L + (rng.nextDouble() * 3200).toLong()
        }
    }

    private fun num(i: Int): Int = 0x0A000000 + i * 0x101

    private fun line(at: Long, f: FromRadio) = "$at\t${Base64.encode(f.encode())}"

    private fun nodeInfo(num: Int, name: String, lat: Double, lon: Double): FromRadio = FromRadio.Builder().also { f ->
        f.node_info = NodeInfo.Builder().also { n ->
            n.num = num
            n.user = User.Builder().also { it.long_name = name }.build()
            n.position = Position.Builder().also {
                it.latitude_i = (lat * 1e7).toInt()
                it.longitude_i = (lon * 1e7).toInt()
            }.build()
        }.build()
    }.build()

    private fun base(from: Int, hopStart: Int, hopLimit: Int, snr: Float) = MeshPacket.Builder().also { b ->
        b.from = from
        b.hop_start = hopStart
        b.hop_limit = hopLimit
        b.rx_snr = snr
        b.rx_rssi = -90
    }

    private fun packet(from: Int, port: PortNum, hopStart: Int, hopLimit: Int, snr: Float): MeshPacket =
        base(from, hopStart, hopLimit, snr).also {
            it.decoded = Data.Builder().also { d -> d.portnum = port }.build()
        }.build()

    private fun telemetry(from: Int, tempC: Float, humidity: Float, pressure: Float): MeshPacket {
        val t = Telemetry.Builder().also { b ->
            b.environment_metrics = EnvironmentMetrics.Builder().also { e ->
                e.temperature = tempC
                e.relative_humidity = humidity
                e.barometric_pressure = pressure
            }.build()
        }.build()
        return base(from, 3, 2, 2f).also {
            it.decoded = Data.Builder().also { d ->
                d.portnum = PortNum.TELEMETRY_APP
                d.payload = t.encode().toByteString()
            }.build()
        }.build()
    }

    private fun encrypted(from: Int, hopStart: Int, hopLimit: Int): MeshPacket =
        base(from, hopStart, hopLimit, -5f).also { it.encrypted = byteArrayOf(1, 2, 3, 4).toByteString() }.build()
}
