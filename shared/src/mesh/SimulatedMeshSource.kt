package org.meshtastic.ambient.mesh

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.PI
import kotlin.math.cos
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
)

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
    private val firstOffsetMs: List<Long>

    init {
        require(nodeCount in 1..200) { "nodeCount out of range" }
        require(speed > 0.0) { "speed must be positive" }
        val rnd = SplitMix(seed)
        val ns = ArrayList<SimNode>()
        val ss = ArrayList<SimStream>()
        val offsets = ArrayList<Long>()
        for (i in 0 until nodeCount) {
            val num = 0x0A000000L + (rnd.nextLong() ushr 40) * 16 + i
            ns += SimNode(
                num = num,
                name = if (rnd.nextDouble() < 0.85) "Sim ${i + 1}" else null,
                position = if (rnd.nextDouble() < 0.8) {
                    GeoPoint(45.0 + rnd.between(-0.4, 0.4), -122.0 + rnd.between(-0.4, 0.4))
                } else {
                    null
                },
                knowsHopStart = rnd.nextDouble() < 0.9,
                viaMqtt = rnd.nextDouble() < 0.1,
                hasEnvironment = rnd.nextDouble() < 0.4,
                baseTempC = rnd.between(2.0, 28.0),
                phase = rnd.between(0.0, 2 * PI),
            )
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
        }
        nodes = ns
        streams = ss
        firstOffsetMs = offsets
    }

    override val events: Flow<MeshEvent> = flow {
        val rnd = SplitMix(seed xor 0x5DEECE66DL)
        emit(MeshEvent.Link(LinkState.Simulated, nowMillis()))
        for (n in nodes) emit(MeshEvent.NodeSeen(n.num, n.name, n.position, true, nowMillis()))

        // Virtual schedule time in ms; the flow sleeps (next - current) / speed.
        val due = LongArray(streams.size) { firstOffsetMs[it] + 1 }
        var envDue = ENV_PERIOD_MS
        var vitalsDue = VITALS_PERIOD_MS
        var now = 0L
        while (true) {
            var idx = -1
            var next = minOf(envDue, vitalsDue)
            for (i in due.indices) if (due[i] < next) { next = due[i]; idx = i }
            if (next > now) delay(((next - now) / speed).toLong().coerceAtLeast(0))
            now = next
            when {
                idx >= 0 -> {
                    val s = streams[idx]
                    emit(heard(nodes[s.node], s.kind, rnd))
                    due[idx] = now + (s.periodMs * (1.0 + rnd.between(-s.jitter, s.jitter))).toLong()
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
        val taken = rnd.nextInt(4)
        return MeshEvent.PacketHeard(
            from = n.num,
            kind = kind,
            snr = rnd.between(-15.0, 10.0).toFloat(),
            rssi = rnd.between(-125.0, -60.0).toInt(),
            hopsTaken = if (hopStart == 0) null else taken,
            viaMqtt = n.viaMqtt,
            atMillis = nowMillis(),
        )
    }

    private fun environment(n: SimNode, simMs: Long, rnd: SplitMix): MeshEvent.Environment {
        val day = simMs / 86_400_000.0 * 2 * PI
        val temp = n.baseTempC + 4.0 * cos(day + n.phase) + rnd.between(-0.1, 0.1)
        return MeshEvent.Environment(
            node = n.num,
            temperatureC = temp.toFloat(),
            humidityPct = (60.0 - 2.0 * (temp - n.baseTempC) + rnd.between(-0.5, 0.5)).toFloat().coerceIn(0f, 100f),
            pressureHpa = (1013.0 + 4.0 * sin(day * 0.5 + n.phase)).toFloat(),
            windMps = null,
            lux = null,
            atMillis = nowMillis(),
        )
    }

    private companion object {
        const val MIN = 60_000L
        const val ENV_PERIOD_MS = 10 * MIN
        const val VITALS_PERIOD_MS = 2 * MIN
    }
}
