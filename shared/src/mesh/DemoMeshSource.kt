package org.meshtastic.aether.mesh

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.meshtastic.aether.music.Rng
import kotlin.math.cos
import kotlin.math.sin

/**
 * The scripted demo mesh: a five-minute loop that shows every behavior of the piece without a radio. Nodes arrive one
 * by one, traffic swells and thins, a router, a tracker and a sensor each speak with their role and hardware, a node
 * runs low on battery, one sits on high ground, an MQTT-relayed packet arrives, a direct message reaches us, a
 * traceroute crosses the field, a text changes the chord, the weather shifts and the last stretch is a rest.
 *
 * The first pass also carries three hours of pressure history, stamped before its start and rising, so the mood
 * sees a trend at once: the front at [WEATHER_SHIFT_MS] turns it to a fall and the rotation reverses. Later passes
 * hold the pressure low, so the turn is shown once per session. Pure data, the same events on every call.
 */
internal object DemoScript {
    const val LOOP_MS = 300_000L
    const val FIRST_ARRIVAL_MS = 4_000L
    const val ARRIVAL_GAP_MS = 9_000L
    const val NODE_COUNT = 12
    const val POWERED = 101
    const val DM_MS = 105_000L
    const val TRACEROUTE_MS = 130_000L
    const val TEXT_MS = 160_000L
    const val WEATHER_SHIFT_MS = 190_000L
    const val ROUTER_MS = 60_000L
    const val TRACKER_MS = 68_000L
    const val SENSOR_MS = 76_000L
    const val LOW_BATTERY_MS = 84_000L
    const val HIGH_GROUND_MS = 95_000L
    const val MQTT_MS = 175_000L

    const val ROUTER_NODE = 1
    const val TRACKER_NODE = 2
    const val SENSOR_NODE = 4
    const val LOW_BATTERY_NODE = 7
    const val HIGH_GROUND_NODE = 9
    const val MQTT_NODE = 10
    const val LOW_BATTERY_PCT = 6
    const val HIGH_GROUND_M = 2_800

    /**
     * Pressure history before the first pass: this long, one reading each [HISTORY_STEP_MS], rising [HISTORY_RISE_HPA]
     * in all. Pressures are station readings at 250 m, about 30 hPa under sea level.
     */
    const val HISTORY_MS = 3 * 3_600_000L
    const val HISTORY_STEP_MS = 1_800_000L
    const val HISTORY_RISE_HPA = 6f
    const val PRESSURE_START_HPA = 982f

    /** After this the loop is silent until [LOOP_MS]; the leaving nodes at its start are the only events. */
    const val REST_STARTS_MS = 255_000L

    private const val SEED = 20_261_125L
    private const val TRAFFIC_FROM_MS = 12_000L
    private const val TRAFFIC_UNTIL_MS = 250_000L
    private const val TRAFFIC_MIN_GAP_MS = 3_000L
    private const val TRAFFIC_GAP_SPAN_MS = 6_000.0
    private const val BUSY_FROM_MS = 100_000L
    private const val BUSY_UNTIL_MS = 170_000L
    private const val QUIET_PACE = 3.0
    private const val BUSY_PACE = 0.4
    private const val LEAVE_FROM_MS = 270_000L
    private const val LEAVE_GAP_MS = 1_500L
    private const val ORIGIN_LAT = 45.0
    private const val ORIGIN_LON = -93.0
    private const val FIELD_DEG = 0.012
    private const val GOLDEN_ANGLE = 2.399963229728653
    private const val BASE_ALTITUDE_M = 250
    private const val SENSOR_EVERY = 4
    private const val FAR_NODE = NODE_COUNT - 1
    private const val BASE_NUM = 0x0B000000L
    private const val NODE_NUM_STRIDE = 0x101L

    /** Nodes spiral out from this fraction of [FIELD_DEG] to all of it. */
    private const val INNER_RING = 0.25

    /** Each further node sits this much higher than the last, sensors excepted. */
    private const val ALTITUDE_STEP_M = 15

    /** A degree of longitude is shorter than one of latitude at 45 degrees north (1 / cos 45 is about 1.4). */
    private const val LON_STRETCH = 1.4

    private const val FULL_PCT = 100
    private const val LIPO_STEP_PCT = 10.0
    private const val MV_PER_VOLT = 1000.0

    /** Before the front: cool and warming one degree per [MS_PER_DEGREE], calm; after it: warm, low and gusty. */
    private const val COOL_START_C = 9f
    private const val MS_PER_DEGREE = 100_000f
    private const val CALM_WIND_MPS = 1.5f
    private const val WARM_C = 17f
    private const val LOW_PRESSURE_HPA = 976f
    private const val GUSTY_WIND_MPS = 8f
    private const val HUMIDITY_PCT = 55f
    private const val NODE_TEMP_STEP_C = 0.1f

    /**
     * A packet's link figures cycle with its index k: SNR -12 to +7 dB in steps of 7, RSSI -110 to -71 dBm in steps
     * of 11, 0 to 3 hops and 24 to 103 payload bytes in steps of 17.
     */
    private const val SNR_FLOOR_DB = -12f
    private const val SNR_STEP_DB = 7
    private const val SNR_SPAN_DB = 20
    private const val RSSI_FLOOR_DBM = -110
    private const val RSSI_STEP_DB = 11
    private const val RSSI_SPAN_DB = 40
    private const val HOP_VARIETY = 4
    private const val PAYLOAD_MIN_BYTES = 24
    private const val PAYLOAD_STEP_BYTES = 17
    private const val PAYLOAD_SPAN_BYTES = 80

    /** After a node arrives: its NodeInfo, then its first environment reading, then its health. */
    private const val NODE_INFO_DELAY_MS = 500
    private const val FIRST_ENVIRONMENT_DELAY_MS = 1_000
    private const val HEALTH_DELAY_MS = 1_500
    private const val BATTERY_BASE_PCT = 55
    private const val BATTERY_STEP_PCT = 4
    private const val BATTERY_SPAN_PCT = 40
    private const val UPTIME_STEP_S = 3_600L

    /** Upper bounds of each kind's share of the background traffic, cumulative; the rest is encrypted. */
    private const val POSITION_UNTIL = 0.30
    private const val TELEMETRY_UNTIL = 0.50
    private const val NODEINFO_UNTIL = 0.65

    private const val DM_NODE = 3
    private const val DM_LENGTH = 14
    private const val TEXT_NODE = 5
    private const val TEXT_LENGTH = 72

    /** The traceroute's reply crosses the field through these two nodes; the first also reports its neighbors. */
    private const val ROUTE_HUB_NODE = 6
    private const val ROUTE_HOP_NODE = 2
    private const val ROUTE_REPLY_DELAY_MS = 400
    private const val NEIGHBORS_DELAY_MS = 800

    /** The sensors report the front one after another, this far apart. */
    private const val SENSOR_STAGGER_MS = 2_000

    /** Our own node; never heard from, the target of the direct message and the traceroute. */
    const val SELF = BASE_NUM + 0xFF

    fun nodeNum(i: Int): Long = BASE_NUM + i * NODE_NUM_STRIDE + 1

    private fun position(i: Int): GeoPoint {
        val r = FIELD_DEG * (INNER_RING + (1.0 - INNER_RING) * (i + 1).toDouble() / NODE_COUNT)
        val a = i * GOLDEN_ANGLE
        val altitude = when {
            i == HIGH_GROUND_NODE -> HIGH_GROUND_M
            i % SENSOR_EVERY == 0 -> BASE_ALTITUDE_M
            else -> BASE_ALTITUDE_M + i * ALTITUDE_STEP_M
        }
        return GeoPoint(ORIGIN_LAT + r * cos(a), ORIGIN_LON + LON_STRETCH * r * sin(a), altitude)
    }

    private fun arrivalMs(i: Int): Long = FIRST_ARRIVAL_MS + i * ARRIVAL_GAP_MS

    private val ROLES = mapOf(ROUTER_NODE to "ROUTER", TRACKER_NODE to "TRACKER", SENSOR_NODE to "SENSOR")
    private val HARDWARE = listOf("HELTEC_V3", "TBEAM", "TLORA_V2_1_1P6")
    private val SPECIAL_HARDWARE = mapOf(
        ROUTER_NODE to "STATION_G2",
        TRACKER_NODE to "T_ECHO",
        SENSOR_NODE to "RAK4631",
    )

    fun role(i: Int): String = ROLES[i] ?: "CLIENT"

    /** Firmware's default LiPo curve (`OCV_ARRAY` in Power.h), 100 percent down to 0 in steps of 10, in millivolts. */
    private val LIPO_MV = intArrayOf(4190, 4050, 3990, 3890, 3800, 3720, 3630, 3530, 3420, 3300, 3100)

    /** Cell voltage the firmware would report at [pct] percent: linear between the curve's points. */
    internal fun lipoVolts(pct: Int): Float {
        val pos = (FULL_PCT - pct.coerceIn(0, FULL_PCT)) / LIPO_STEP_PCT
        val i = pos.toInt().coerceAtMost(LIPO_MV.lastIndex)
        val next = minOf(i + 1, LIPO_MV.lastIndex)
        return ((LIPO_MV[i] + (LIPO_MV[next] - LIPO_MV[i]) * (pos - i)) / MV_PER_VOLT).toFloat()
    }

    fun hardware(i: Int): String = SPECIAL_HARDWARE[i] ?: HARDWARE[i % HARDWARE.size]

    /**
     * Traffic pace as a multiple of the base gap: slow at the start, brisk between [BUSY_FROM_MS] and [BUSY_UNTIL_MS],
     * slow again by the end, linear in between, so the packet rate rises and falls through the loop.
     */
    internal fun pace(atMs: Long): Double = when {
        atMs <= TRAFFIC_FROM_MS -> QUIET_PACE
        atMs < BUSY_FROM_MS ->
            QUIET_PACE + (BUSY_PACE - QUIET_PACE) * (atMs - TRAFFIC_FROM_MS) / (BUSY_FROM_MS - TRAFFIC_FROM_MS)
        atMs <= BUSY_UNTIL_MS -> BUSY_PACE
        else -> (BUSY_PACE + (QUIET_PACE - BUSY_PACE) * (atMs - BUSY_UNTIL_MS) / (TRAFFIC_UNTIL_MS - BUSY_UNTIL_MS))
            .coerceAtMost(QUIET_PACE)
    }

    private class Weather(val temperatureC: Float, val pressureHpa: Float, val windMps: Float)

    /** The weather at [atMs]: a calm cool spell, then a step to warm, low and gusty. */
    private fun weather(atMs: Long, first: Boolean): Weather =
        if (atMs < WEATHER_SHIFT_MS) {
            val pressure = if (first) PRESSURE_START_HPA + HISTORY_RISE_HPA else LOW_PRESSURE_HPA
            Weather(COOL_START_C + atMs / MS_PER_DEGREE, pressure, CALM_WIND_MPS)
        } else {
            Weather(WARM_C, LOW_PRESSURE_HPA, GUSTY_WIND_MPS)
        }

    /**
     * Every event of one pass, in time order, with `atMillis = base + offset`. The [first] pass also leads with the
     * pressure history, stamped before [base]; a later pass is only what happens inside its own five minutes.
     */
    fun events(base: Long, first: Boolean = true): List<MeshEvent> = with(Pass(base, first)) {
        if (first) history()
        arrivals()
        traffic()
        scenes()
        for (i in 0 until NODE_COUNT) out += MeshEvent.NodeGone(nodeNum(i), base + LEAVE_FROM_MS + i * LEAVE_GAP_MS)
        out.sortedBy { it.atMillis }
    }

    /** One pass being written: every event lands in [out] at `base + offset`. */
    private class Pass(val base: Long, val first: Boolean) {
        val out = ArrayList<MeshEvent>()

        /** A broadcast from [node] whose link figures cycle with [k]; only the far node's arrive over MQTT. */
        private fun packet(offset: Long, node: Int, kind: PacketKind, k: Int) = MeshEvent.PacketHeard(
            from = nodeNum(node),
            kind = kind,
            snr = SNR_FLOOR_DB + (k * SNR_STEP_DB % SNR_SPAN_DB),
            rssi = RSSI_FLOOR_DBM + (k * RSSI_STEP_DB % RSSI_SPAN_DB),
            hopsTaken = k % HOP_VARIETY,
            viaMqtt = node == FAR_NODE,
            atMillis = base + offset,
            payloadBytes = PAYLOAD_MIN_BYTES + k * PAYLOAD_STEP_BYTES % PAYLOAD_SPAN_BYTES,
            channel = k % 2,
        )

        fun heard(offset: Long, node: Int, kind: PacketKind, k: Int) {
            out += packet(offset, node, kind, k)
        }

        fun heardOverMqtt(offset: Long, node: Int, kind: PacketKind, k: Int) {
            out += packet(offset, node, kind, k).copy(viaMqtt = true)
        }

        /** A text of [length] characters from [node], addressed to us when [toUs]. */
        fun text(offset: Long, node: Int, length: Int, toUs: Boolean = false) {
            out += packet(offset, node, PacketKind.Text, node).copy(
                to = if (toUs) SELF else null,
                toUs = toUs,
                payloadBytes = length,
                textLength = length,
            )
        }

        fun environment(offset: Long, node: Int) {
            val w = weather(offset, first)
            out += MeshEvent.Environment(
                node = nodeNum(node),
                temperatureC = w.temperatureC + node * NODE_TEMP_STEP_C,
                humidityPct = HUMIDITY_PCT,
                pressureHpa = w.pressureHpa,
                windMps = w.windMps,
                lux = null,
                atMillis = base + offset,
            )
        }

        /** The pressure history the first pass leads with: every sensor, rising, before [base]. */
        fun history() {
            val steps = (HISTORY_MS / HISTORY_STEP_MS).toInt()
            for (j in 0..steps) {
                val hpa = PRESSURE_START_HPA + HISTORY_RISE_HPA * j / steps
                val at = base - HISTORY_MS + j * HISTORY_STEP_MS
                for (n in 0 until NODE_COUNT step SENSOR_EVERY) {
                    out += MeshEvent.Environment(nodeNum(n), null, null, hpa, null, null, at)
                }
            }
        }

        /** The link, our own node, then each node arriving with its NodeInfo, first reading and health. */
        fun arrivals() {
            out += MeshEvent.Link(LinkState.Simulated, base)
            out += MeshEvent.Self(SELF, "Demo Home", GeoPoint(ORIGIN_LAT, ORIGIN_LON), base)
            for (i in 0 until NODE_COUNT) {
                val arrival = arrivalMs(i)
                out += MeshEvent.NodeSeen(
                    nodeNum(i), "Demo ${i + 1}", position(i), true, base + arrival,
                    role = role(i), hardware = hardware(i),
                )
                heard(arrival + NODE_INFO_DELAY_MS, i, PacketKind.NodeInfo, i)
                if (i % SENSOR_EVERY == 0) environment(arrival + FIRST_ENVIRONMENT_DELAY_MS, i)
                val pct = when (i) {
                    ROUTER_NODE -> POWERED
                    LOW_BATTERY_NODE -> LOW_BATTERY_PCT
                    else -> BATTERY_BASE_PCT + i * BATTERY_STEP_PCT % BATTERY_SPAN_PCT
                }
                out += MeshEvent.Health(
                    nodeNum(i), pct, if (pct == POWERED) null else lipoVolts(pct), UPTIME_STEP_S * (i + 1),
                    base + arrival + HEALTH_DELAY_MS,
                    channelUtilPct = 8f + (i * 7 % 30), airUtilTxPct = 0.5f + (i % 5) * 0.4f,
                )
            }
        }

        /** Background traffic from the nodes arrived so far, paced by [pace]. */
        fun traffic() {
            val rnd = Rng(SEED)
            var t = TRAFFIC_FROM_MS
            var k = 0
            while (t < TRAFFIC_UNTIL_MS) {
                val arrived = ((t - FIRST_ARRIVAL_MS) / ARRIVAL_GAP_MS).toInt().coerceIn(0, NODE_COUNT - 1) + 1
                val node = rnd.nextInt(arrived)
                val roll = rnd.nextDouble()
                when {
                    roll < POSITION_UNTIL -> heard(t, node, PacketKind.Position, k)
                    roll < TELEMETRY_UNTIL -> {
                        heard(t, node, PacketKind.Telemetry, k)
                        if (node % SENSOR_EVERY == 0) environment(t, node)
                    }
                    roll < NODEINFO_UNTIL -> heard(t, node, PacketKind.NodeInfo, k)
                    else -> heard(t, node, PacketKind.Encrypted, k)
                }
                t += ((TRAFFIC_MIN_GAP_MS + rnd.nextDouble() * TRAFFIC_GAP_SPAN_MS) * pace(t)).toLong()
                k++
            }
        }

        /** The set pieces: each role, the low battery, high ground, MQTT, the DM, the traceroute and the front. */
        fun scenes() {
            heard(ROUTER_MS, ROUTER_NODE, PacketKind.Position, ROUTER_NODE)
            heard(TRACKER_MS, TRACKER_NODE, PacketKind.Position, TRACKER_NODE)
            heard(SENSOR_MS, SENSOR_NODE, PacketKind.Telemetry, SENSOR_NODE)
            environment(SENSOR_MS, SENSOR_NODE)
            heard(LOW_BATTERY_MS, LOW_BATTERY_NODE, PacketKind.Telemetry, LOW_BATTERY_NODE)
            heard(HIGH_GROUND_MS, HIGH_GROUND_NODE, PacketKind.Position, HIGH_GROUND_NODE)
            heardOverMqtt(MQTT_MS, MQTT_NODE, PacketKind.Position, MQTT_NODE)

            text(DM_MS, DM_NODE, DM_LENGTH, toUs = true)
            heard(TRACEROUTE_MS, FAR_NODE, PacketKind.Traceroute, 1)
            out += MeshEvent.Route(
                listOf(nodeNum(FAR_NODE), nodeNum(ROUTE_HUB_NODE), nodeNum(ROUTE_HOP_NODE), SELF),
                base + TRACEROUTE_MS + ROUTE_REPLY_DELAY_MS,
            )
            out += MeshEvent.Neighbors(
                nodeNum(ROUTE_HUB_NODE),
                listOf(nodeNum(ROUTE_HOP_NODE), nodeNum(FAR_NODE)),
                base + TRACEROUTE_MS + NEIGHBORS_DELAY_MS,
            )
            text(TEXT_MS, TEXT_NODE, TEXT_LENGTH)
            for (n in 0 until NODE_COUNT step SENSOR_EVERY) {
                environment(WEATHER_SHIFT_MS + n / SENSOR_EVERY * SENSOR_STAGGER_MS, n)
            }
        }
    }
}

/**
 * Plays [DemoScript] forever: each pass starts at the clock's now, delays are divided by [speed], and the events
 * of a pass carry that pass's own times so the composer's clock stays real.
 */
internal class DemoMeshSource(
    private val nowMillis: () -> Long,
    private val speed: Double = 1.0,
) : MeshSource {

    init {
        require(speed > 0.0) { "speed must be positive" }
    }

    override val events: Flow<MeshEvent> = flow {
        var first = true
        while (true) {
            val base = nowMillis()
            var at = base
            for (e in DemoScript.events(base, first)) {
                val gap = e.atMillis - at
                if (gap > 0) delay((gap / speed).toLong())
                at = maxOf(at, e.atMillis)
                emit(e)
            }
            first = false
            val rest = base + DemoScript.LOOP_MS - at
            if (rest > 0) delay((rest / speed).toLong())
        }
    }
}
