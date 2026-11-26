package org.meshtastic.ambient.mesh

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/** SplitMix64: platform-independent, so a seed means the same mesh on every target. */
internal class SplitMix(seed: Long) {
    private var s = seed

    fun nextLong(): Long {
        s += -0x61c8864680b583ebL
        var z = s
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()

    fun between(lo: Double, hi: Double): Double = lo + (hi - lo) * nextDouble()

    fun nextInt(bound: Int): Int = (nextDouble() * bound).toInt()
}

/** One periodic broadcast of one node: every gap is `periodMs` scaled by a factor in 1 +- [jitter]. */
internal class SimStream(val node: Int, val kind: PacketKind, val periodMs: Long, val jitter: Double)

internal class SimNode(
    val num: Long,
    val name: String?,
    val position: GeoPoint?,
    val knowsHopStart: Boolean,
    val viaMqtt: Boolean,
    val hasEnvironment: Boolean,
    val baseTempC: Double,
    val phase: Double,
    val role: String = "CLIENT",
    val hardware: String = "HELTEC_V3",
    /** Battery percent at time zero; 101 means externally powered. */
    val batteryBasePct: Int = 101,
    val drainPctPerHour: Double = 0.0,
    val uptimeBaseS: Long = 0,
    val hasWind: Boolean = false,
    val windDirDeg: Double = 0.0,
    val windBaseMps: Double = 3.0,
) {
    val externallyPowered: Boolean get() = batteryBasePct > 100
}

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

private fun <T> SplitMix.weighted(items: List<Pair<T, Int>>): T {
    var r = nextInt(items.sumOf { it.second })
    for ((v, w) in items) {
        if (r < w) return v
        r -= w
    }
    return items.last().first
}

private val POWERED_ROLES = setOf("ROUTER", "ROUTER_LATE", "REPEATER", "CLIENT_BASE", "SENSOR")

internal const val SIM_JITTER = 0.15

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

    /** Nodes whose low byte is nonzero and shared with no other simulated node (self included): safe relay bytes. */
    private val relayPool: List<SimNode>
    private val firstOffsetMs: List<Long>
    private val healthOffsetMs: List<Long>

    init {
        require(nodeCount in 1..200) { "nodeCount out of range" }
        require(speed > 0.0) { "speed must be positive" }
        val rnd = SplitMix(seed)
        val ns = ArrayList<SimNode>()
        val ss = ArrayList<SimStream>()
        val offsets = ArrayList<Long>()
        val attr = SplitMix(seed xor 0xA77B1E5L)
        val healthOffsets = ArrayList<Long>()
        for (i in 0 until nodeCount) {
            val num = 0x0A000000L + (rnd.nextLong() ushr 40) * 16 + i
            val role = attr.weighted(SIM_ROLES)
            val external = if (role in POWERED_ROLES) attr.nextDouble() < 0.7 else attr.nextDouble() < 0.06
            val hasEnv = rnd.nextDouble() < 0.4
            val pos = if (rnd.nextDouble() < 0.8) {
                GeoPoint(45.0 + rnd.between(-0.4, 0.4), -122.0 + rnd.between(-0.4, 0.4))
            } else {
                null
            }
            ns += SimNode(
                num = num,
                name = if (rnd.nextDouble() < 0.85) "Sim ${i + 1}" else null,
                position = pos?.copy(altitudeM = attr.between(5.0, 1500.0).toInt()),
                knowsHopStart = rnd.nextDouble() < 0.9,
                viaMqtt = rnd.nextDouble() < 0.1,
                hasEnvironment = hasEnv,
                baseTempC = rnd.between(2.0, 28.0),
                phase = rnd.between(0.0, 2 * PI),
                role = role,
                hardware = attr.weighted(SIM_HARDWARE),
                batteryBasePct = if (external) 101 else attr.between(25.0, 100.0).toInt(),
                drainPctPerHour = attr.between(0.2, 1.2),
                uptimeBaseS = attr.between(3_600.0, 30 * 86_400.0).toLong(),
                hasWind = hasEnv && attr.nextDouble() < 0.3,
                windDirDeg = attr.between(0.0, 360.0),
                windBaseMps = attr.between(1.5, 6.0),
            )
            healthOffsets += (attr.nextDouble() * HEALTH_PERIOD_MS).toLong()
            fun add(kind: PacketKind, minMs: Long, maxMs: Long) {
                val period = (rnd.between(minMs.toDouble(), maxMs.toDouble())).toLong()
                ss += SimStream(i, kind, period, SIM_JITTER)
                offsets += (rnd.nextDouble() * period).toLong()
            }
            add(PacketKind.Position, 3 * MIN, 30 * MIN)
            add(PacketKind.Telemetry, 10 * MIN, 60 * MIN)
            add(PacketKind.NodeInfo, 60 * MIN, 180 * MIN)
            if (rnd.nextDouble() < 0.5) add(PacketKind.Text, 20 * MIN, 120 * MIN)
            if (rnd.nextDouble() < 0.35) add(PacketKind.Encrypted, 2 * MIN, 12 * MIN)
            if (rnd.nextDouble() < 0.15) add(PacketKind.Routing, 60 * MIN, 180 * MIN)
            if (rnd.nextDouble() < 0.1) add(PacketKind.Traceroute, 120 * MIN, 240 * MIN)
            if (rnd.nextDouble() < 0.3) add(PacketKind.NeighborInfo, 90 * MIN, 240 * MIN)
        }
        val selfRnd = SplitMix(seed xor 0x5E1F5E1FL)
        self = SimNode(
            num = 0x0C000000L + (selfRnd.nextLong() ushr 40) * 16 + 15,
            name = "Sim Self",
            position = GeoPoint(45.0, -122.0),
            knowsHopStart = true,
            viaMqtt = false,
            hasEnvironment = false,
            baseTempC = 15.0,
            phase = 0.0,
            uptimeBaseS = 2 * 86_400L,
        )
        val lowBytes = (ns + self).groupingBy { (it.num and 0xFF).toInt() }.eachCount()
        relayPool = ns.filter { (it.num and 0xFF) != 0L && lowBytes[(it.num and 0xFF).toInt()] == 1 }
        nodes = ns
        streams = ss
        firstOffsetMs = offsets
        healthOffsetMs = healthOffsets
    }

    override val events: Flow<MeshEvent> = flow {
        val rnd = SplitMix(seed xor 0x5DEECE66DL)
        emit(MeshEvent.Link(LinkState.Simulated, nowMillis()))
        emit(MeshEvent.Self(self.num, self.name, self.position, nowMillis()))
        for (n in nodes) {
            emit(MeshEvent.NodeSeen(n.num, n.name, n.position, true, nowMillis(), role = n.role, hardware = n.hardware))
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
                    healthDue[hidx] = now + (HEALTH_PERIOD_MS * (1.0 + rnd.between(-SIM_JITTER, SIM_JITTER))).toLong()
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
                    due[idx] = now + (s.periodMs * (1.0 + rnd.between(-s.jitter, s.jitter))).toLong()
                }
                next == statsDue -> {
                    emit(stats(now, rxCount))
                    statsDue += STATS_PERIOD_MS
                }
                next == envDue -> {
                    for (n in nodes) if (n.hasEnvironment) emit(environment(n, now, rnd))
                    envDue += ENV_PERIOD_MS
                }
                else -> {
                    emit(
                        MeshEvent.Vitals(
                            (8.0 + 6.0 * sin(now / 3_600_000.0) + rnd.between(-1.0, 1.0)).toFloat().coerceIn(0f, 100f),
                            (1.0 + rnd.between(0.0, 1.5)).toFloat(),
                            nowMillis(),
                        ),
                    )
                    vitalsDue += VITALS_PERIOD_MS
                }
            }
        }
    }

    private fun heard(n: SimNode, kind: PacketKind, rnd: SplitMix): MeshEvent.PacketHeard {
        val hopStart = if (n.knowsHopStart) 3 else 0
        val snr = rnd.between(-15.0, 10.0).toFloat()
        val rssi = rnd.between(-125.0, -60.0).toInt()
        val relays = relayPool.filter { it !== n }
        val taken = if (hopStart == 0) 0 else if (relays.isEmpty()) 0 else rnd.nextInt(4)
        val relay = if (hopStart != 0 && taken > 0) relays[rnd.nextInt(relays.size)] else null
        val toUs = kind == PacketKind.Traceroute || (kind == PacketKind.Text && rnd.nextDouble() < 0.25)
        val textLength = if (kind == PacketKind.Text) 4 + (-ln(1.0 - rnd.nextDouble()) * 40.0).toInt().coerceAtMost(196) else null
        val payloadBytes = textLength ?: when (kind) {
            PacketKind.Position -> 20 + rnd.nextInt(20)
            PacketKind.Telemetry -> 20 + rnd.nextInt(30)
            PacketKind.NodeInfo -> 40 + rnd.nextInt(40)
            PacketKind.Routing -> 2 + rnd.nextInt(8)
            PacketKind.Traceroute -> 10 + rnd.nextInt(30)
            PacketKind.NeighborInfo -> 20 + rnd.nextInt(100)
            else -> 30 + rnd.nextInt(90)
        }
        val channelRoll = rnd.nextDouble()
        val channel = if (channelRoll < 0.7) 0 else if (channelRoll < 0.9) 1 else 2 + rnd.nextInt(2)
        return MeshEvent.PacketHeard(
            from = n.num,
            kind = kind,
            snr = snr,
            rssi = rssi,
            hopsTaken = if (hopStart == 0) null else taken,
            viaMqtt = n.viaMqtt,
            atMillis = nowMillis(),
            to = if (toUs) self.num else BROADCAST,
            relayByte = relay?.let { (it.num and 0xFF).toInt() },
            toUs = toUs,
            payloadBytes = payloadBytes,
            textLength = textLength,
            channel = if (kind == PacketKind.Encrypted) null else channel,
        )
    }

    private fun health(n: SimNode, simMs: Long, rnd: SplitMix): MeshEvent.Health {
        val pct = if (n.externallyPowered) 101 else (n.batteryBasePct - n.drainPctPerHour * simMs / 3_600_000.0).coerceAtLeast(3.0).toInt()
        val volts = if (n.externallyPowered) 5.0 + rnd.between(-0.1, 0.1) else 3.3 + 0.9 * pct / 100.0 + rnd.between(-0.01, 0.01)
        return MeshEvent.Health(n.num, pct, volts.toFloat(), n.uptimeBaseS + simMs / 1_000, nowMillis())
    }

    /** Cumulative counters since our boot, like firmware's LocalStats: duplicates are 30% and bad packets 2% of receives. */
    private fun stats(simMs: Long, rx: Long) = MeshEvent.Stats(
        numOnline = (nodes.size * 0.4).toInt().coerceAtLeast(1),
        numTotal = nodes.size + 1,
        rxTotal = rx,
        rxDupe = rx * 3 / 10,
        rxBad = rx / 50,
        noiseFloorDbm = -110 + (simMs / 600_000L % 5).toInt() - 2,
        uptimeSeconds = self.uptimeBaseS + simMs / 1_000,
        atMillis = nowMillis(),
    )

    private fun neighbors(n: SimNode, rnd: SplitMix): MeshEvent.Neighbors {
        val others = nodes.filter { it !== n }
        val picked = LinkedHashSet<Long>()
        if (others.isNotEmpty()) repeat(1 + rnd.nextInt(4)) { picked += others[rnd.nextInt(others.size)].num }
        return MeshEvent.Neighbors(n.num, picked.toList(), nowMillis())
    }

    /** A reply to a traceroute we sent: us first, then relays, then the answering node. */
    private fun route(n: SimNode, rnd: SplitMix): MeshEvent.Route {
        val others = nodes.filter { it !== n }
        val mids = LinkedHashSet<Long>()
        if (others.isNotEmpty()) repeat(rnd.nextInt(3)) { mids += others[rnd.nextInt(others.size)].num }
        return MeshEvent.Route(listOf(self.num) + mids + n.num, nowMillis())
    }

    private fun environment(n: SimNode, simMs: Long, rnd: SplitMix): MeshEvent.Environment {
        val day = simMs / 86_400_000.0 * 2 * PI
        val temp = n.baseTempC + 4.0 * cos(day + n.phase) + rnd.between(-0.1, 0.1)
        val wind = n.windBaseMps * (1.0 + 0.4 * sin(day * 3 + n.phase)) + rnd.between(-0.2, 0.2)
        return MeshEvent.Environment(
            node = n.num,
            temperatureC = temp.toFloat(),
            humidityPct = (60.0 - 2.0 * (temp - n.baseTempC) + rnd.between(-0.5, 0.5)).toFloat().coerceIn(0f, 100f),
            pressureHpa = (1013.0 + 4.0 * sin(day * 0.5 + n.phase)).toFloat(),
            windMps = if (n.hasWind) wind.toFloat() else null,
            lux = null,
            atMillis = nowMillis(),
            windDirectionDeg = if (n.hasWind) ((n.windDirDeg + 25.0 * sin(day * 2 + n.phase)).mod(360.0)).toFloat() else null,
            windGustMps = if (n.hasWind) (wind * 1.6 + rnd.between(0.0, 1.0)).toFloat() else null,
            windLullMps = if (n.hasWind) (wind * 0.5).toFloat() else null,
        )
    }

    private companion object {
        const val BROADCAST = 0xFFFFFFFFL
        const val MIN = 60_000L
        const val ENV_PERIOD_MS = 10 * MIN
        const val VITALS_PERIOD_MS = 2 * MIN
        const val HEALTH_PERIOD_MS = 30 * MIN
        const val STATS_FIRST_MS = 2 * MIN
        const val STATS_PERIOD_MS = 30 * MIN
    }
}
