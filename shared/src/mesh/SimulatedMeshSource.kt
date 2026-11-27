package org.meshtastic.aether.mesh

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.meshtastic.aether.music.Rng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/** One periodic broadcast of one node: every gap is `periodMs` scaled by a factor in 1 +- [jitter]. */
internal class SimStream(val node: Int, val kind: PacketKind, val periodMs: Long, val jitter: Double)

/** What a simulated node is and how it is heard. */
internal class SimDevice(
    val role: String = "CLIENT",
    val hardware: String = "HELTEC_V3",
    val knowsHopStart: Boolean = true,
    val viaMqtt: Boolean = false,
    val link: SimLink = SimLink(),
)

/** A simulated node's battery and uptime at time zero. */
internal class SimPower(
    /** Battery percent at time zero; [POWERED_PCT] means externally powered. */
    val batteryBasePct: Int = POWERED_PCT,
    val drainPctPerHour: Double = 0.0,
    val uptimeBaseS: Long = 0,
) {
    val externallyPowered: Boolean get() = batteryBasePct > FULL_PCT
}

/** A simulated node's sensors: whether it reports the environment and wind, and the climate it sits in. */
internal class SimClimate(
    val hasEnvironment: Boolean,
    val baseTempC: Double,
    val phase: Double,
    val hasWind: Boolean = false,
    val windDirDeg: Double = 0.0,
    val windBaseMps: Double = 3.0,
)

/**
 * How a simulated node is heard: a steady signal strength and route, as a real node's are, with each packet varying
 * only a little around them.
 */
internal class SimLink(
    val baseRssiDbm: Double = (RSSI_MIN_DBM + RSSI_MAX_DBM) / 2,
    val snrOffsetDb: Double = 0.0,
    val baseHops: Int = 0,
)

internal class SimNode(
    val num: Long,
    val name: String?,
    val position: GeoPoint?,
    val device: SimDevice,
    val power: SimPower,
    val climate: SimClimate,
)

/** Role mix by weight: mostly clients, a tail of routers, trackers and sensors, as on a public mesh. */
internal val SIM_ROLES = listOf(
    "CLIENT" to 52, "CLIENT_MUTE" to 12, "CLIENT_BASE" to 5, "ROUTER" to 7, "ROUTER_LATE" to 3, "REPEATER" to 3,
    "TRACKER" to 7, "SENSOR" to 4, "TAK" to 3, "CLIENT_HIDDEN" to 2, "LOST_AND_FOUND" to 1, "TAK_TRACKER" to 1,
)

/** Hardware mix by weight, proto enum names. */
internal val SIM_HARDWARE = listOf(
    "HELTEC_V3" to 22, "RAK4631" to 18, "TBEAM" to 12, "T_ECHO" to 9, "TLORA_V2_1_1P6" to 7, "STATION_G2" to 6,
    "SEEED_XIAO_S3" to 5, "T_DECK" to 5, "HELTEC_V4" to 5, "LILYGO_TBEAM_S3_CORE" to 4, "TRACKER_T1000_E" to 4,
    "RAK11200" to 3,
)

private fun <T> Rng.weighted(items: List<Pair<T, Int>>): T {
    var r = nextInt(items.sumOf { it.second })
    for ((v, w) in items) {
        if (r < w) return v
        r -= w
    }
    return items.last().first
}

private val POWERED_ROLES = setOf("ROUTER", "ROUTER_LATE", "REPEATER", "CLIENT_BASE", "SENSOR")

internal const val SIM_JITTER = 0.15

/** Firmware reports 101 percent for a node with no battery or on external power. */
private const val POWERED_PCT = 101
private const val FULL_PCT = 100
private const val MAX_NODES = 200

/** Seed salts for the independent random streams. */
private const val ATTRIBUTE_SALT = 0xA77B1E5L
private const val SELF_SALT = 0x5E1F5E1FL
private const val TRAFFIC_SALT = 0x5DEECE66DL
private const val LINK_SALT = 0x11A4B5L

/** Node numbers: a base, 24 random bits in steps of 16, plus the node's index. */
private const val NUM_BASE = 0x0A000000L
private const val SELF_NUM_BASE = 0x0C000000L
private const val NUM_RANDOM_SHIFT = 40
private const val NUM_STRIDE = 16
private const val SELF_NUM_OFFSET = 15

/** Nodes scatter this many degrees around the origin, at this altitude range. */
private const val ORIGIN_LAT = 45.0
private const val ORIGIN_LON = -122.0
private const val SPREAD_DEG = 0.4
private const val ALTITUDE_MIN_M = 5.0
private const val ALTITUDE_MAX_M = 1500.0

/** The share of nodes with each trait. */
private const val POWERED_ROLE_MAINS_CHANCE = 0.7
private const val MAINS_CHANCE = 0.06

/**
 * Environment sensors sit on 3 to 4 percent of real nodes (docs/mesh-driven-parameters.md, "What real meshes send"),
 * so a simulated render hears the landscape a real mesh gives, not a weather station on every other node.
 */
private const val ENVIRONMENT_CHANCE = 0.04
private const val POSITION_CHANCE = 0.8
private const val NAMED_CHANCE = 0.85
private const val KNOWS_HOP_START_CHANCE = 0.9
private const val MQTT_CHANCE = 0.1

/** Wind appears on no captured mesh; a rare one stays so the wind paths still run in simulation. */
private const val WIND_CHANCE = 0.3

private const val BASE_TEMP_MIN_C = 2.0
private const val BASE_TEMP_MAX_C = 28.0
private const val BATTERY_MIN_PCT = 25.0
private const val BATTERY_MAX_PCT = 100.0
private const val DRAIN_MIN_PCT_PER_HOUR = 0.2
private const val DRAIN_MAX_PCT_PER_HOUR = 1.2
private const val UPTIME_MIN_S = 3_600.0
private const val UPTIME_MAX_S = 30 * 86_400.0
private const val FULL_TURN_DEG = 360.0
private const val WIND_BASE_MIN_MPS = 1.5
private const val WIND_BASE_MAX_MPS = 6.0
private const val SELF_TEMP_C = 15.0
private const val SELF_UPTIME_S = 2 * 86_400L

/** A node's broadcasts: kind, the chance it sends them at all (null: always) and the period range in minutes. */
private class StreamSpec(val kind: PacketKind, val chance: Double?, val minMinutes: Long, val maxMinutes: Long)

private val STREAM_SPECS = listOf(
    StreamSpec(PacketKind.Position, chance = null, minMinutes = 3, maxMinutes = 30),
    StreamSpec(PacketKind.Telemetry, chance = null, minMinutes = 10, maxMinutes = 60),
    StreamSpec(PacketKind.NodeInfo, chance = null, minMinutes = 60, maxMinutes = 180),
    StreamSpec(PacketKind.Text, chance = 0.5, minMinutes = 20, maxMinutes = 120),
    StreamSpec(PacketKind.Encrypted, chance = 0.35, minMinutes = 2, maxMinutes = 12),
    StreamSpec(PacketKind.Routing, chance = 0.15, minMinutes = 60, maxMinutes = 180),
    StreamSpec(PacketKind.Traceroute, chance = 0.1, minMinutes = 120, maxMinutes = 240),
    StreamSpec(PacketKind.NeighborInfo, chance = 0.3, minMinutes = 90, maxMinutes = 240),
)

/** Node [i] of the mesh; draws in a fixed order from [rnd] and [attr], so a seed means the same node. */
private fun simNode(i: Int, rnd: Rng, attr: Rng, link: Rng): SimNode {
    val num = NUM_BASE + (rnd.nextLong() ushr NUM_RANDOM_SHIFT) * NUM_STRIDE + i
    val role = attr.weighted(SIM_ROLES)
    val external = attr.nextDouble() < if (role in POWERED_ROLES) POWERED_ROLE_MAINS_CHANCE else MAINS_CHANCE
    val hasEnv = rnd.nextDouble() < ENVIRONMENT_CHANCE
    val pos = if (rnd.nextDouble() < POSITION_CHANCE) {
        GeoPoint(ORIGIN_LAT + rnd.range(-SPREAD_DEG, SPREAD_DEG), ORIGIN_LON + rnd.range(-SPREAD_DEG, SPREAD_DEG))
    } else {
        null
    }
    val name = if (rnd.nextDouble() < NAMED_CHANCE) "Sim ${i + 1}" else null
    val position = pos?.copy(altitudeM = attr.range(ALTITUDE_MIN_M, ALTITUDE_MAX_M).toInt())
    val device = SimDevice(
        role = role,
        hardware = attr.weighted(SIM_HARDWARE),
        knowsHopStart = rnd.nextDouble() < KNOWS_HOP_START_CHANCE,
        viaMqtt = rnd.nextDouble() < MQTT_CHANCE,
        link = SimLink(
            baseRssiDbm = link.range(RSSI_MIN_DBM, RSSI_MAX_DBM),
            snrOffsetDb = link.range(-SNR_OFFSET_DB, SNR_OFFSET_DB),
            baseHops = link.nextInt(HOP_LIMIT + 1),
        ),
    )
    val power = SimPower(
        batteryBasePct = if (external) POWERED_PCT else attr.range(BATTERY_MIN_PCT, BATTERY_MAX_PCT).toInt(),
        drainPctPerHour = attr.range(DRAIN_MIN_PCT_PER_HOUR, DRAIN_MAX_PCT_PER_HOUR),
        uptimeBaseS = attr.range(UPTIME_MIN_S, UPTIME_MAX_S).toLong(),
    )
    val climate = SimClimate(
        hasEnvironment = hasEnv,
        baseTempC = rnd.range(BASE_TEMP_MIN_C, BASE_TEMP_MAX_C),
        phase = rnd.range(0.0, 2 * PI),
        hasWind = hasEnv && attr.nextDouble() < WIND_CHANCE,
        windDirDeg = attr.range(0.0, FULL_TURN_DEG),
        windBaseMps = attr.range(WIND_BASE_MIN_MPS, WIND_BASE_MAX_MPS),
    )
    return SimNode(num, name, position, device, power, climate)
}

/** Our own simulated node: a fixed place and name, a number from its own seed. */
private fun simSelf(seed: Long): SimNode = SimNode(
    num = SELF_NUM_BASE + (Rng(seed xor SELF_SALT).nextLong() ushr NUM_RANDOM_SHIFT) * NUM_STRIDE + SELF_NUM_OFFSET,
    name = "Sim Self",
    position = GeoPoint(ORIGIN_LAT, ORIGIN_LON),
    device = SimDevice(knowsHopStart = true, viaMqtt = false),
    power = SimPower(uptimeBaseS = SELF_UPTIME_S),
    climate = SimClimate(hasEnvironment = false, baseTempC = SELF_TEMP_C, phase = 0.0),
)

/** Packet payload sizes by kind: at least [min] bytes and under [min] + [spread]. */
private class PayloadSize(val min: Int, val spread: Int)

private fun payloadSize(kind: PacketKind): PayloadSize = when (kind) {
    PacketKind.Position -> PayloadSize(min = 20, spread = 20)
    PacketKind.Telemetry -> PayloadSize(min = 20, spread = 30)
    PacketKind.NodeInfo -> PayloadSize(min = 40, spread = 40)
    PacketKind.Routing -> PayloadSize(min = 2, spread = 8)
    PacketKind.Traceroute -> PayloadSize(min = 10, spread = 30)
    PacketKind.NeighborInfo -> PayloadSize(min = 20, spread = 100)
    else -> PayloadSize(min = 30, spread = 90)
}

/** Firmware's default hop limit. */
private const val HOP_LIMIT = 3
private const val SNR_MIN_DB = -15.0
private const val SNR_MAX_DB = 10.0
private const val RSSI_MIN_DBM = -125.0
private const val RSSI_MAX_DBM = -60.0

/** Each packet's RSSI and SNR wander this far around the node's own; a node's own SNR sits this far off the line. */
private const val RSSI_JITTER_DB = 3.0
private const val SNR_JITTER_DB = 1.5
private const val SNR_OFFSET_DB = 3.0

/** A packet takes a route one hop longer or shorter than the node's usual one this often. */
private const val REROUTE_CHANCE = 0.15
private const val HALF = 0.5

/** SNR follows RSSI: a weak node near the floor is near the SNR floor, a strong one near the top. */
private fun snrForRssi(rssiDbm: Double): Double =
    SNR_MIN_DB + (SNR_MAX_DB - SNR_MIN_DB) * (rssiDbm - RSSI_MIN_DBM) / (RSSI_MAX_DBM - RSSI_MIN_DBM)
private const val TEXT_DM_CHANCE = 0.25

/** Text lengths: at least the minimum, plus an exponential tail with this mean, capped at the firmware's 200. */
private const val TEXT_MIN_CHARS = 4
private const val TEXT_MAX_CHARS = 200
private const val TEXT_MEAN_EXTRA_CHARS = 40.0

/** 70 percent of packets on the primary channel, 20 on the secondary, the rest on the next two. */
private const val PRIMARY_CHANNEL_UNTIL = 0.7
private const val SECONDARY_CHANNEL_UNTIL = 0.9

/** A text's length in characters. */
private fun textLength(rnd: Rng): Int =
    TEXT_MIN_CHARS +
        (-ln(1.0 - rnd.nextDouble()) * TEXT_MEAN_EXTRA_CHARS).toInt().coerceAtMost(TEXT_MAX_CHARS - TEXT_MIN_CHARS)

private fun channelIndex(rnd: Rng): Int {
    val roll = rnd.nextDouble()
    return when {
        roll < PRIMARY_CHANNEL_UNTIL -> 0
        roll < SECONDARY_CHANNEL_UNTIL -> 1
        else -> 2 + rnd.nextInt(2)
    }
}

private const val HOUR_MS = 3_600_000.0
private const val DAY_MS = 86_400_000.0
private const val MS_PER_SECOND = 1_000

private const val BATTERY_FLOOR_PCT = 3.0
private const val USB_VOLTS = 5.0
private const val USB_VOLTS_NOISE = 0.1
private const val CELL_EMPTY_VOLTS = 3.3
private const val CELL_SPAN_VOLTS = 0.9
private const val CELL_VOLTS_NOISE = 0.01

/** LocalStats: 40 percent of nodes online, 30 percent of receives duplicates and 2 percent bad. */
private const val ONLINE_SHARE = 0.4
private const val DUPE_PERCENT = 30
private const val BAD_PERCENT = 2

/** The noise floor steps through five 1 dB levels around -110 dBm, one every ten minutes. */
private const val NOISE_FLOOR_DBM = -110
private const val NOISE_STEP_MS = 600_000L
private const val NOISE_STEPS = 5

private const val MAX_NEIGHBOR_PICKS = 4
private const val MAX_ROUTE_RELAYS = 2

/** Our channel use swings around its mean once per 2 pi hours; airtime is 1 to 2.5 percent. */
private const val CHANNEL_UTIL_MEAN_PCT = 8.0
private const val CHANNEL_UTIL_SWING_PCT = 6.0
private const val AIR_UTIL_SPAN_PCT = 1.5
private const val MAX_PCT = 100f

/** Daily swings: temperature, wind (three gusty spells a day), humidity against temperature, pressure, wind veer. */
private const val TEMP_SWING_C = 4.0
private const val TEMP_NOISE_C = 0.1
private const val WIND_SWING = 0.4
private const val WIND_CYCLES_PER_DAY = 3
private const val WIND_NOISE_MPS = 0.2
private const val HUMIDITY_MEAN_PCT = 60.0
private const val HUMIDITY_PER_C = 2.0
private const val HUMIDITY_NOISE_PCT = 0.5
private const val SEA_LEVEL_HPA = 1013.0
private const val PRESSURE_SWING_HPA = 4.0
private const val PRESSURE_CYCLES_PER_DAY = 0.5
private const val WIND_VEER_DEG = 25.0
private const val GUST_FACTOR = 1.6
private const val LULL_FACTOR = 0.5

/**
 * A made-up mesh: each node loops through its own broadcast periods (periodic with jitter, not
 * Poisson, because real traffic is a set of slow loops of unrelated periods). Deterministic per
 * [seed]; [speed] divides the delays only, never the periods.
 */
class SimulatedMeshSource(
    private val seed: Long,
    private val nodeCount: Int = 20,
    private val speed: Double = 1.0,
    private val nowMillis: () -> Long,
) : MeshSource {

    internal val nodes: List<SimNode>
    internal val streams: List<SimStream>

    /** Our own simulated node: not in [nodes], never heard from, the target of DMs and traceroutes. */
    internal val self: SimNode

    /**
     * Nodes whose low byte is nonzero and shared with no other simulated node (self included): safe relay bytes.
     */
    private val relayPool: List<SimNode>
    private val firstOffsetMs: List<Long>
    private val healthOffsetMs: List<Long>

    init {
        require(nodeCount in 1..MAX_NODES) { "nodeCount out of range" }
        require(speed > 0.0) { "speed must be positive" }
        val rnd = Rng(seed)
        val ns = ArrayList<SimNode>()
        val ss = ArrayList<SimStream>()
        val offsets = ArrayList<Long>()
        val attr = Rng(seed xor ATTRIBUTE_SALT)
        val link = Rng(seed xor LINK_SALT)
        val healthOffsets = ArrayList<Long>()
        for (i in 0 until nodeCount) {
            ns += simNode(i, rnd, attr, link)
            healthOffsets += (attr.nextDouble() * HEALTH_PERIOD_MS).toLong()
            for (spec in STREAM_SPECS) {
                if (spec.chance != null && rnd.nextDouble() >= spec.chance) continue
                val period = rnd.range((spec.minMinutes * MIN).toDouble(), (spec.maxMinutes * MIN).toDouble()).toLong()
                ss += SimStream(i, spec.kind, period, SIM_JITTER)
                offsets += (rnd.nextDouble() * period).toLong()
            }
        }
        self = simSelf(seed)
        val lowBytes = (ns + self).groupingBy { it.num.lowByte() }.eachCount()
        relayPool = ns.filter { it.num.lowByte() != 0 && lowBytes[it.num.lowByte()] == 1 }
        nodes = ns
        streams = ss
        firstOffsetMs = offsets
        healthOffsetMs = healthOffsets
    }

    override val events: Flow<MeshEvent> = flow {
        val rnd = Rng(seed xor TRAFFIC_SALT)
        emit(MeshEvent.Link(LinkState.Simulated, nowMillis()))
        emit(MeshEvent.Self(self.num, self.name, self.position, nowMillis()))
        for (n in nodes) {
            emit(
                MeshEvent.NodeSeen(
                    n.num, n.name, n.position, true, nowMillis(), role = n.device.role, hardware = n.device.hardware,
                ),
            )
        }

        // Virtual schedule time in ms; the flow sleeps (next - current) / speed.
        val due = LongArray(streams.size) { firstOffsetMs[it] + 1 }
        var envDue = ENV_PERIOD_MS
        var vitalsDue = VITALS_PERIOD_MS
        var statsDue = STATS_FIRST_MS
        val healthDue = LongArray(nodes.size) { healthOffsetMs[it] + 1 }
        var rxCount = 0L
        var now = 0L
        while (true) {
            var idx = -1
            var hidx = -1
            var next = minOf(envDue, vitalsDue, statsDue)
            for (i in due.indices) if (due[i] < next) { next = due[i]; idx = i }
            for (i in healthDue.indices) if (healthDue[i] < next) { next = healthDue[i]; hidx = i; idx = -1 }
            if (next > now) delay(((next - now) / speed).toLong().coerceAtLeast(0))
            now = next
            when {
                hidx >= 0 -> {
                    emit(health(nodes[hidx], now, rnd))
                    healthDue[hidx] = now + (HEALTH_PERIOD_MS * (1.0 + rnd.range(-SIM_JITTER, SIM_JITTER))).toLong()
                }
                idx >= 0 -> {
                    val s = streams[idx]
                    val node = nodes[s.node]
                    emit(heard(node, s.kind, rnd))
                    rxCount++
                    when (s.kind) {
                        PacketKind.NeighborInfo -> emit(neighbors(node, rnd))
                        PacketKind.Traceroute -> emit(route(node, rnd))
                        else -> Unit
                    }
                    due[idx] = now + (s.periodMs * (1.0 + rnd.range(-s.jitter, s.jitter))).toLong()
                }
                next == statsDue -> {
                    emit(stats(now, rxCount))
                    statsDue += STATS_PERIOD_MS
                }
                next == envDue -> {
                    for (n in nodes) if (n.climate.hasEnvironment) emit(environment(n, now, rnd))
                    envDue += ENV_PERIOD_MS
                }
                else -> {
                    emit(vitals(now, rnd))
                    vitalsDue += VITALS_PERIOD_MS
                }
            }
        }
    }

    private fun vitals(simMs: Long, rnd: Rng): MeshEvent.Vitals {
        val swing = CHANNEL_UTIL_SWING_PCT * sin(simMs / HOUR_MS)
        return MeshEvent.Vitals(
            (CHANNEL_UTIL_MEAN_PCT + swing + rnd.range(-1.0, 1.0)).toFloat().coerceIn(0f, MAX_PCT),
            (1.0 + rnd.range(0.0, AIR_UTIL_SPAN_PCT)).toFloat(),
            nowMillis(),
        )
    }

    private fun heard(n: SimNode, kind: PacketKind, rnd: Rng): MeshEvent.PacketHeard {
        val knowsHops = n.device.knowsHopStart
        val link = n.device.link
        val rssiDbm = (link.baseRssiDbm + rnd.range(-RSSI_JITTER_DB, RSSI_JITTER_DB))
            .coerceIn(RSSI_MIN_DBM, RSSI_MAX_DBM)
        val rssi = rssiDbm.toInt()
        val snr = (snrForRssi(rssiDbm) + link.snrOffsetDb + rnd.range(-SNR_JITTER_DB, SNR_JITTER_DB))
            .coerceIn(SNR_MIN_DB, SNR_MAX_DB).toFloat()
        val relays = relayPool.filter { it !== n }
        val taken = if (!knowsHops || relays.isEmpty()) 0 else hopsTaken(link, rnd)
        val relay = if (knowsHops && taken > 0) relays[rnd.nextInt(relays.size)] else null
        val toUs = kind == PacketKind.Traceroute || (kind == PacketKind.Text && rnd.nextDouble() < TEXT_DM_CHANCE)
        val textLength = if (kind == PacketKind.Text) textLength(rnd) else null
        val payloadBytes = textLength ?: payloadSize(kind).let { it.min + rnd.nextInt(it.spread) }
        val channel = channelIndex(rnd)
        return MeshEvent.PacketHeard(
            from = n.num,
            kind = kind,
            snr = snr,
            rssi = rssi,
            hopsTaken = if (knowsHops) taken else null,
            viaMqtt = n.device.viaMqtt,
            atMillis = nowMillis(),
            to = if (toUs) self.num else BROADCAST_NUM,
            relayByte = relay?.num?.lowByte(),
            toUs = toUs,
            payloadBytes = payloadBytes,
            textLength = textLength,
            channel = if (kind == PacketKind.Encrypted) null else channel,
        )
    }

    /** The node's usual route, or one hop longer or shorter now and then. */
    private fun hopsTaken(link: SimLink, rnd: Rng): Int {
        if (rnd.nextDouble() >= REROUTE_CHANCE) return link.baseHops
        return (link.baseHops + if (rnd.nextDouble() < HALF) 1 else -1).coerceIn(0, HOP_LIMIT)
    }

    private fun health(n: SimNode, simMs: Long, rnd: Rng): MeshEvent.Health {
        val p = n.power
        val pct = if (p.externallyPowered) {
            POWERED_PCT
        } else {
            (p.batteryBasePct - p.drainPctPerHour * simMs / HOUR_MS).coerceAtLeast(BATTERY_FLOOR_PCT).toInt()
        }
        val volts = if (p.externallyPowered) {
            USB_VOLTS + rnd.range(-USB_VOLTS_NOISE, USB_VOLTS_NOISE)
        } else {
            CELL_EMPTY_VOLTS + CELL_SPAN_VOLTS * pct / FULL_PCT.toDouble() +
                rnd.range(-CELL_VOLTS_NOISE, CELL_VOLTS_NOISE)
        }
        return MeshEvent.Health(n.num, pct, volts.toFloat(), p.uptimeBaseS + simMs / MS_PER_SECOND, nowMillis())
    }

    /**
     * Cumulative counters since our boot, like firmware's LocalStats: duplicates are 30% and bad packets 2% of
     * receives.
     */
    private fun stats(simMs: Long, rx: Long) = MeshEvent.Stats(
        numOnline = (nodes.size * ONLINE_SHARE).toInt().coerceAtLeast(1),
        numTotal = nodes.size + 1,
        rxTotal = rx,
        rxDupe = rx * DUPE_PERCENT / FULL_PCT,
        rxBad = rx * BAD_PERCENT / FULL_PCT,
        noiseFloorDbm = NOISE_FLOOR_DBM + (simMs / NOISE_STEP_MS % NOISE_STEPS).toInt() - NOISE_STEPS / 2,
        uptimeSeconds = self.power.uptimeBaseS + simMs / MS_PER_SECOND,
        atMillis = nowMillis(),
    )

    private fun neighbors(n: SimNode, rnd: Rng): MeshEvent.Neighbors {
        val others = nodes.filter { it !== n }
        val picked = LinkedHashSet<Long>()
        if (others.isNotEmpty()) {
            repeat(1 + rnd.nextInt(MAX_NEIGHBOR_PICKS)) { picked += others[rnd.nextInt(others.size)].num }
        }
        return MeshEvent.Neighbors(n.num, picked.toList(), nowMillis())
    }

    /** A reply to a traceroute we sent: us first, then relays, then the answering node. */
    private fun route(n: SimNode, rnd: Rng): MeshEvent.Route {
        val others = nodes.filter { it !== n }
        val mids = LinkedHashSet<Long>()
        if (others.isNotEmpty()) {
            repeat(rnd.nextInt(MAX_ROUTE_RELAYS + 1)) { mids += others[rnd.nextInt(others.size)].num }
        }
        return MeshEvent.Route(listOf(self.num) + mids + n.num, nowMillis())
    }

    private fun environment(n: SimNode, simMs: Long, rnd: Rng): MeshEvent.Environment {
        val c = n.climate
        val day = simMs / DAY_MS * 2 * PI
        val temp = c.baseTempC + TEMP_SWING_C * cos(day + c.phase) + rnd.range(-TEMP_NOISE_C, TEMP_NOISE_C)
        val wind = c.windBaseMps * (1.0 + WIND_SWING * sin(day * WIND_CYCLES_PER_DAY + c.phase)) +
            rnd.range(-WIND_NOISE_MPS, WIND_NOISE_MPS)
        val humidity = HUMIDITY_MEAN_PCT - HUMIDITY_PER_C * (temp - c.baseTempC) +
            rnd.range(-HUMIDITY_NOISE_PCT, HUMIDITY_NOISE_PCT)
        return MeshEvent.Environment(
            node = n.num,
            temperatureC = temp.toFloat(),
            humidityPct = humidity.toFloat().coerceIn(0f, MAX_PCT),
            pressureHpa = (SEA_LEVEL_HPA + PRESSURE_SWING_HPA * sin(day * PRESSURE_CYCLES_PER_DAY + c.phase)).toFloat(),
            windMps = if (c.hasWind) wind.toFloat() else null,
            lux = null,
            atMillis = nowMillis(),
            windDirectionDeg = if (c.hasWind) windDirection(c, day) else null,
            windGustMps = if (c.hasWind) (wind * GUST_FACTOR + rnd.range(0.0, 1.0)).toFloat() else null,
            windLullMps = if (c.hasWind) (wind * LULL_FACTOR).toFloat() else null,
        )
    }

    private fun windDirection(c: SimClimate, day: Double): Float =
        ((c.windDirDeg + WIND_VEER_DEG * sin(day * 2 + c.phase)).mod(FULL_TURN_DEG)).toFloat()

    private companion object {
        const val MIN = 60_000L
        const val ENV_PERIOD_MS = 10 * MIN
        const val VITALS_PERIOD_MS = 2 * MIN
        const val HEALTH_PERIOD_MS = 30 * MIN
        const val STATS_FIRST_MS = 2 * MIN
        const val STATS_PERIOD_MS = 30 * MIN
    }
}
