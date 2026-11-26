package org.meshtastic.aether.mesh

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

    /** Our own node; never heard from, the target of the direct message and the traceroute. */
    const val SELF = BASE_NUM + 0xFF

    fun nodeNum(i: Int): Long = BASE_NUM + i * 0x101L + 1

    private fun position(i: Int): GeoPoint {
        val r = FIELD_DEG * (0.25 + 0.75 * (i + 1).toDouble() / NODE_COUNT)
        val a = i * GOLDEN_ANGLE
        val altitude = when {
            i == HIGH_GROUND_NODE -> HIGH_GROUND_M
            i % SENSOR_EVERY == 0 -> BASE_ALTITUDE_M
            else -> BASE_ALTITUDE_M + i * 15
        }
        return GeoPoint(ORIGIN_LAT + r * cos(a), ORIGIN_LON + 1.4 * r * sin(a), altitude)
    }

    private fun arrivalMs(i: Int): Long = FIRST_ARRIVAL_MS + i * ARRIVAL_GAP_MS

    private val ROLES = mapOf(ROUTER_NODE to "ROUTER", TRACKER_NODE to "TRACKER", SENSOR_NODE to "SENSOR")
    private val HARDWARE = listOf("HELTEC_V3", "TBEAM", "TLORA_V2_1_1P6")
    private val SPECIAL_HARDWARE = mapOf(ROUTER_NODE to "STATION_G2", TRACKER_NODE to "T_ECHO", SENSOR_NODE to "RAK4631")

    fun role(i: Int): String = ROLES[i] ?: "CLIENT"

    /** Firmware's default LiPo curve (`OCV_ARRAY` in Power.h), 100 percent down to 0 in steps of 10, in millivolts. */
    private val LIPO_MV = intArrayOf(4190, 4050, 3990, 3890, 3800, 3720, 3630, 3530, 3420, 3300, 3100)

    /** Cell voltage the firmware would report at [pct] percent: linear between the curve's points. */
    internal fun lipoVolts(pct: Int): Float {
        val pos = (100 - pct.coerceIn(0, 100)) / 10.0
        val i = pos.toInt().coerceAtMost(LIPO_MV.lastIndex)
        val next = minOf(i + 1, LIPO_MV.lastIndex)
        return ((LIPO_MV[i] + (LIPO_MV[next] - LIPO_MV[i]) * (pos - i)) / 1000.0).toFloat()
    }

    fun hardware(i: Int): String = SPECIAL_HARDWARE[i] ?: HARDWARE[i % HARDWARE.size]

    /**
     * Traffic pace as a multiple of the base gap: slow at the start, brisk between [BUSY_FROM_MS] and [BUSY_UNTIL_MS],
     * slow again by the end, linear in between, so the packet rate rises and falls through the loop.
     */
    internal fun pace(atMs: Long): Double = when {
        atMs <= TRAFFIC_FROM_MS -> QUIET_PACE
        atMs < BUSY_FROM_MS -> QUIET_PACE + (BUSY_PACE - QUIET_PACE) * (atMs - TRAFFIC_FROM_MS) / (BUSY_FROM_MS - TRAFFIC_FROM_MS)
        atMs <= BUSY_UNTIL_MS -> BUSY_PACE
        else -> (BUSY_PACE + (QUIET_PACE - BUSY_PACE) * (atMs - BUSY_UNTIL_MS) / (TRAFFIC_UNTIL_MS - BUSY_UNTIL_MS)).coerceAtMost(QUIET_PACE)
    }

    /** Temperature C, pressure hPa and wind m/s at [atMs]: a calm cool spell, then a step to warm, low and gusty. */
    private fun weather(atMs: Long, first: Boolean): Triple<Float, Float, Float> =
        if (atMs < WEATHER_SHIFT_MS) {
            Triple(9f + atMs / 100_000f, if (first) 988f else 976f, 1.5f)
        } else {
            Triple(17f, 976f, 8f)
        }

    /**
     * Every event of one pass, in time order, with `atMillis = base + offset`. The [first] pass also leads with the
     * pressure history, stamped before [base]; a later pass is only what happens inside its own five minutes.
     */
    fun events(base: Long, first: Boolean = true): List<MeshEvent> {
        val out = ArrayList<MeshEvent>()
        fun heard(
            offset: Long,
            node: Int,
            kind: PacketKind,
            k: Int,
            to: Long? = null,
            toUs: Boolean = false,
            viaMqtt: Boolean = node == FAR_NODE,
            textLength: Int? = null,
        ) {
            out += MeshEvent.PacketHeard(
                from = nodeNum(node),
                kind = kind,
                snr = -12f + (k * 7 % 20),
                rssi = -110 + (k * 11 % 40),
                hopsTaken = k % 4,
                viaMqtt = viaMqtt,
                atMillis = base + offset,
                to = to,
                toUs = toUs,
                payloadBytes = textLength ?: (24 + k * 17 % 80),
                textLength = textLength,
                channel = k % 2,
            )
        }
        fun environment(offset: Long, node: Int) {
            val (t, p, w) = weather(offset, first)
            out += MeshEvent.Environment(nodeNum(node), t + node * 0.1f, 55f, p, w, null, base + offset)
        }

        if (first) {
            val steps = (HISTORY_MS / HISTORY_STEP_MS).toInt()
            for (j in 0..steps) {
                val hpa = PRESSURE_START_HPA + HISTORY_RISE_HPA * j / steps
                for (n in 0 until NODE_COUNT step SENSOR_EVERY) {
                    out += MeshEvent.Environment(nodeNum(n), null, null, hpa, null, null, base - HISTORY_MS + j * HISTORY_STEP_MS)
                }
            }
        }

        out += MeshEvent.Link(LinkState.Simulated, base)
        out += MeshEvent.Self(SELF, "Demo Home", GeoPoint(ORIGIN_LAT, ORIGIN_LON), base)
        for (i in 0 until NODE_COUNT) {
            out += MeshEvent.NodeSeen(
                nodeNum(i), "Demo ${i + 1}", position(i), true, base + arrivalMs(i),
                role = role(i), hardware = hardware(i),
            )
            heard(arrivalMs(i) + 500, i, PacketKind.NodeInfo, i)
            if (i % SENSOR_EVERY == 0) environment(arrivalMs(i) + 1_000, i)
            val pct = when (i) {
                ROUTER_NODE -> POWERED
                LOW_BATTERY_NODE -> LOW_BATTERY_PCT
                else -> 55 + i * 4 % 40
            }
            out += MeshEvent.Health(
                nodeNum(i), pct, if (pct == POWERED) null else lipoVolts(pct), 3_600L * (i + 1), base + arrivalMs(i) + 1_500,
                channelUtilPct = 8f + (i * 7 % 30), airUtilTxPct = 0.5f + (i % 5) * 0.4f,
            )
        }

        val rnd = SplitMix(SEED)
        var t = TRAFFIC_FROM_MS
        var k = 0
        while (t < TRAFFIC_UNTIL_MS) {
            val arrived = ((t - FIRST_ARRIVAL_MS) / ARRIVAL_GAP_MS).toInt().coerceIn(0, NODE_COUNT - 1) + 1
            val node = rnd.nextInt(arrived)
            val roll = rnd.nextDouble()
            when {
                roll < 0.30 -> heard(t, node, PacketKind.Position, k)
                roll < 0.50 -> {
                    heard(t, node, PacketKind.Telemetry, k)
                    if (node % SENSOR_EVERY == 0) environment(t, node)
                }
                roll < 0.65 -> heard(t, node, PacketKind.NodeInfo, k)
                else -> heard(t, node, PacketKind.Encrypted, k)
            }
            t += ((TRAFFIC_MIN_GAP_MS + rnd.nextDouble() * TRAFFIC_GAP_SPAN_MS) * pace(t)).toLong()
            k++
        }

        heard(ROUTER_MS, ROUTER_NODE, PacketKind.Position, 1)
        heard(TRACKER_MS, TRACKER_NODE, PacketKind.Position, 2)
        heard(SENSOR_MS, SENSOR_NODE, PacketKind.Telemetry, 4)
        environment(SENSOR_MS, SENSOR_NODE)
        heard(LOW_BATTERY_MS, LOW_BATTERY_NODE, PacketKind.Telemetry, 7)
        heard(HIGH_GROUND_MS, HIGH_GROUND_NODE, PacketKind.Position, 9)
        heard(MQTT_MS, MQTT_NODE, PacketKind.Position, 10, viaMqtt = true)

        heard(DM_MS, 3, PacketKind.Text, 3, to = SELF, toUs = true, textLength = 14)
        heard(TRACEROUTE_MS, FAR_NODE, PacketKind.Traceroute, 1)
        out += MeshEvent.Route(listOf(nodeNum(FAR_NODE), nodeNum(6), nodeNum(2), SELF), base + TRACEROUTE_MS + 400)
        out += MeshEvent.Neighbors(nodeNum(6), listOf(nodeNum(2), nodeNum(FAR_NODE)), base + TRACEROUTE_MS + 800)
        heard(TEXT_MS, 5, PacketKind.Text, 5, textLength = 72)
        environment(WEATHER_SHIFT_MS, 0)
        environment(WEATHER_SHIFT_MS + 2_000, 4)
        environment(WEATHER_SHIFT_MS + 4_000, 8)
        for (i in 0 until NODE_COUNT) out += MeshEvent.NodeGone(nodeNum(i), base + LEAVE_FROM_MS + i * LEAVE_GAP_MS)
        return out.sortedBy { it.atMillis }
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
