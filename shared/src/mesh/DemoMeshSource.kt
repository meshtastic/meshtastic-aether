package org.meshtastic.ambient.mesh

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.cos
import kotlin.math.sin

/**
 * The scripted demo mesh: a five-minute loop that shows every behavior of the piece without a radio. Nodes arrive one
 * by one, a direct message reaches us, a traceroute crosses the field, a text changes the chord, the weather shifts,
 * and the last stretch is a rest. Pure data, the same events on every call.
 */
internal object DemoScript {
    const val LOOP_MS = 300_000L
    const val FIRST_ARRIVAL_MS = 4_000L
    const val ARRIVAL_GAP_MS = 9_000L
    const val NODE_COUNT = 12
    const val DM_MS = 105_000L
    const val TRACEROUTE_MS = 130_000L
    const val TEXT_MS = 160_000L
    const val WEATHER_SHIFT_MS = 190_000L

    /** After this the loop is silent until [LOOP_MS]; the leaving nodes at its start are the only events. */
    const val REST_STARTS_MS = 255_000L

    private const val SEED = 20_261_125L
    private const val TRAFFIC_FROM_MS = 12_000L
    private const val TRAFFIC_UNTIL_MS = 250_000L
    private const val TRAFFIC_MIN_GAP_MS = 3_000L
    private const val TRAFFIC_GAP_SPAN_MS = 6_000.0
    private const val LEAVE_FROM_MS = 270_000L
    private const val LEAVE_GAP_MS = 1_500L
    private const val ORIGIN_LAT = 45.0
    private const val ORIGIN_LON = -93.0
    private const val FIELD_DEG = 0.012
    private const val GOLDEN_ANGLE = 2.399963229728653
    private const val SENSOR_EVERY = 4
    private const val FAR_NODE = NODE_COUNT - 1
    private const val BASE_NUM = 0x0B000000L

    /** Our own node; never heard from, the target of the direct message and the traceroute. */
    const val SELF = BASE_NUM + 0xFF

    fun nodeNum(i: Int): Long = BASE_NUM + i * 0x101L + 1

    private fun position(i: Int): GeoPoint {
        val r = FIELD_DEG * (0.25 + 0.75 * (i + 1).toDouble() / NODE_COUNT)
        val a = i * GOLDEN_ANGLE
        return GeoPoint(ORIGIN_LAT + r * cos(a), ORIGIN_LON + 1.4 * r * sin(a))
    }

    private fun arrivalMs(i: Int): Long = FIRST_ARRIVAL_MS + i * ARRIVAL_GAP_MS

    /** Temperature C, pressure hPa and wind m/s at [atMs]: a calm cool spell, then a step to warm, low and gusty. */
    private fun weather(atMs: Long): Triple<Float, Float, Float> =
        if (atMs < WEATHER_SHIFT_MS) {
            Triple(9f + atMs / 100_000f, 1018f, 1.5f)
        } else {
            Triple(17f, 1006f, 8f)
        }

    /** Every event of one pass, in time order, with `atMillis = base + offset`. */
    fun events(base: Long): List<MeshEvent> {
        val out = ArrayList<MeshEvent>()
        fun heard(offset: Long, node: Int, kind: PacketKind, k: Int, to: Long? = null, toUs: Boolean = false) {
            out += MeshEvent.PacketHeard(
                from = nodeNum(node),
                kind = kind,
                snr = -12f + (k * 7 % 20),
                rssi = -110 + (k * 11 % 40),
                hopsTaken = k % 4,
                viaMqtt = node == FAR_NODE,
                atMillis = base + offset,
                to = to,
                toUs = toUs,
            )
        }
        fun environment(offset: Long, node: Int) {
            val (t, p, w) = weather(offset)
            out += MeshEvent.Environment(nodeNum(node), t + node * 0.1f, 55f, p, w, null, base + offset)
        }

        out += MeshEvent.Link(LinkState.Simulated, base)
        out += MeshEvent.Self(SELF, "Demo Home", GeoPoint(ORIGIN_LAT, ORIGIN_LON), base)
        for (i in 0 until NODE_COUNT) {
            out += MeshEvent.NodeSeen(nodeNum(i), "Demo ${i + 1}", position(i), true, base + arrivalMs(i))
            heard(arrivalMs(i) + 500, i, PacketKind.NodeInfo, i)
            if (i % SENSOR_EVERY == 0) environment(arrivalMs(i) + 1_000, i)
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
            t += TRAFFIC_MIN_GAP_MS + (rnd.nextDouble() * TRAFFIC_GAP_SPAN_MS).toLong()
            k++
        }

        heard(DM_MS, 3, PacketKind.Text, 3, to = SELF, toUs = true)
        heard(TRACEROUTE_MS, FAR_NODE, PacketKind.Traceroute, 1)
        out += MeshEvent.Route(listOf(nodeNum(FAR_NODE), nodeNum(6), nodeNum(2), SELF), base + TRACEROUTE_MS + 400)
        out += MeshEvent.Neighbors(nodeNum(6), listOf(nodeNum(2), nodeNum(FAR_NODE)), base + TRACEROUTE_MS + 800)
        heard(TEXT_MS, 5, PacketKind.Text, 5)
        environment(WEATHER_SHIFT_MS, 0)
        environment(WEATHER_SHIFT_MS + 2_000, 4)
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
        while (true) {
            val base = nowMillis()
            var at = base
            for (e in DemoScript.events(base)) {
                val gap = e.atMillis - at
                if (gap > 0) delay((gap / speed).toLong())
                at = e.atMillis
                emit(e)
            }
            val rest = base + DemoScript.LOOP_MS - at
            if (rest > 0) delay((rest / speed).toLong())
        }
    }
}
