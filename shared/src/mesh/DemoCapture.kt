package org.meshtastic.aether.mesh

import okio.ByteString.Companion.toByteString
import org.meshtastic.aether.music.Rng
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A synthetic capture in the replay format (`<epochMillis>\t<base64 FromRadio>`): nine named nodes around a made-up
 * origin, a few minutes of mixed traffic and a drifting temperature. No real node data; same bytes every call.
 */
internal object DemoCapture {
    private const val BASE = 1_700_000_000_000L
    private const val NODES = 9
    private const val SECONDS = 240
    private const val MS_PER_SECOND = 1000L
    private const val SEED = 20260101L

    /** The nodes sit on a ring, one every 40 degrees, between 0.002 and 0.006 degrees out. */
    private const val BEARING_STEP_DEG = 40.0
    private const val HALF_TURN_DEG = 180.0
    private const val RING_MIN_DEG = 0.002
    private const val RING_SPAN_DEG = 0.004
    private const val ORIGIN_LAT = 45.0
    private const val ORIGIN_LON = -93.0

    /** The MQTT-bridged node is far away (London). */
    private const val FAR_LAT = 51.5
    private const val FAR_LON = -0.1

    /** Upper bounds of each packet kind's share of the traffic, cumulative. */
    private const val TEXT_UNTIL = 0.04
    private const val POSITION_UNTIL = 0.30
    private const val TELEMETRY_UNTIL = 0.45
    private const val NODEINFO_UNTIL = 0.60
    private const val FAR_POSITION_UNTIL = 0.68
    private const val ENCRYPTED_UNTIL = 0.85

    private const val START_TEMP_C = 14f
    private const val TEMP_RISE_C = 6f
    private const val MIN_GAP_MS = 800L
    private const val GAP_SPAN_MS = 3200
    private const val NUM_BASE = 0x0A000000
    private const val NUM_STRIDE = 0x101
    private const val DEGREES_E7 = 1e7
    private const val RSSI_DBM = -90
    private const val DEFAULT_HOP_LIMIT = 3
    private const val ENCRYPTED_BYTES = 4

    fun lines(): Sequence<String> = sequence {
        val rng = Rng(SEED)
        for (i in 0 until NODES) {
            val bearing = i * BEARING_STEP_DEG * PI / HALF_TURN_DEG
            val dist = RING_MIN_DEG + RING_SPAN_DEG * rng.nextDouble()
            val lat = ORIGIN_LAT + dist * cos(bearing)
            val lon = ORIGIN_LON + dist * sin(bearing)
            yield(line(BASE, nodeInfo(num(i), "Demo ${i + 1}", lat, lon)))
        }
        yield(line(BASE, nodeInfo(num(NODES), "Far MQTT", FAR_LAT, FAR_LON)))
        var t = MS_PER_SECOND
        while (t < SECONDS * MS_PER_SECOND) {
            val packet = trafficPacket(rng, t)
            yield(line(BASE + t, FromRadio.Builder().also { it.packet = packet }.build()))
            t += MIN_GAP_MS + (rng.nextDouble() * GAP_SPAN_MS).toLong()
        }
    }

    /** One packet of the mixed traffic at [t] ms; draws the sender, the kind and any per-kind variation from [rng]. */
    private fun trafficPacket(rng: Rng, t: Long): MeshPacket {
        val node = num(rng.nextInt(NODES))
        val roll = rng.nextDouble()
        return when {
            roll < TEXT_UNTIL -> packet(node, PortNum.TEXT_MESSAGE_APP, DEFAULT_HOP_LIMIT, hopLimit = 2, snr = 4f)
            roll < POSITION_UNTIL -> packet(
                node, PortNum.POSITION_APP, DEFAULT_HOP_LIMIT,
                hopLimit = DEFAULT_HOP_LIMIT - rng.nextInt(DEFAULT_HOP_LIMIT),
                snr = -3f + 8f * rng.nextDouble().toFloat(),
            )
            roll < TELEMETRY_UNTIL -> telemetry(
                node,
                tempC = START_TEMP_C + TEMP_RISE_C * (t / (SECONDS * MS_PER_SECOND).toFloat()),
                humidity = 55f,
                pressure = 1008f,
            )
            roll < NODEINFO_UNTIL -> packet(node, PortNum.NODEINFO_APP, DEFAULT_HOP_LIMIT, hopLimit = 1, snr = -8f)
            roll < FAR_POSITION_UNTIL ->
                packet(num(NODES), PortNum.POSITION_APP, hopStart = 7, hopLimit = 2, snr = -12f)
            roll < ENCRYPTED_UNTIL -> encrypted(node, DEFAULT_HOP_LIMIT, hopLimit = 2 - rng.nextInt(2))
            else -> packet(node, PortNum.TRACEROUTE_APP, DEFAULT_HOP_LIMIT, hopLimit = 1, snr = 0f)
        }
    }

    private fun num(i: Int): Int = NUM_BASE + i * NUM_STRIDE

    private fun line(at: Long, f: FromRadio) = "$at\t${Base64.encode(f.encode())}"

    private fun nodeInfo(num: Int, name: String, lat: Double, lon: Double): FromRadio = FromRadio.Builder().also { f ->
        f.node_info = NodeInfo.Builder().also { n ->
            n.num = num
            n.user = User.Builder().also { it.long_name = name }.build()
            n.position = Position.Builder().also {
                it.latitude_i = (lat * DEGREES_E7).toInt()
                it.longitude_i = (lon * DEGREES_E7).toInt()
            }.build()
        }.build()
    }.build()

    private fun base(from: Int, hopStart: Int, hopLimit: Int, snr: Float) = MeshPacket.Builder().also { b ->
        b.from = from
        b.hop_start = hopStart
        b.hop_limit = hopLimit
        b.rx_snr = snr
        b.rx_rssi = RSSI_DBM
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
        return base(from, DEFAULT_HOP_LIMIT, hopLimit = 2, snr = 2f).also {
            it.decoded = Data.Builder().also { d ->
                d.portnum = PortNum.TELEMETRY_APP
                d.payload = t.encode().toByteString()
            }.build()
        }.build()
    }

    /** Placeholder ciphertext: the bytes 1, 2, 3, 4. */
    private fun encrypted(from: Int, hopStart: Int, hopLimit: Int): MeshPacket =
        base(from, hopStart, hopLimit, snr = -5f).also {
            it.encrypted = ByteArray(ENCRYPTED_BYTES) { i -> (i + 1).toByte() }.toByteString()
        }.build()
}
