package org.meshtastic.aether.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val HOUR = 3_600_000L

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedMeshSourceTest {
    private suspend fun kotlinx.coroutines.test.TestScope.run(source: SimulatedMeshSource, ms: Long): List<MeshEvent> {
        val out = ArrayList<MeshEvent>()
        withTimeoutOrNull(ms) { source.events.collect { out += it } }
        return out
    }

    @Test
    fun `the same seed gives the same event list`() = runTest {
        val a = run(SimulatedMeshSource(42L) { currentTime }, 2 * HOUR)
        val startA = a.first().atMillis
        val b = run(SimulatedMeshSource(42L) { currentTime }, 2 * HOUR)
        val startB = b.first().atMillis
        assertTrue(a.size > 50, "too quiet: ${a.size}")
        assertEquals(a.map { it.shifted(-startA) }, b.map { it.shifted(-startB) })
    }

    @Test
    fun `different seeds give different meshes`() = runTest {
        val a = run(SimulatedMeshSource(1L) { currentTime }, HOUR)
        val b = run(SimulatedMeshSource(2L) { currentTime }, HOUR)
        assertNotEquals(
            a.filterIsInstance<MeshEvent.NodeSeen>().map { it.node },
            b.filterIsInstance<MeshEvent.NodeSeen>().map { it.node },
        )
    }

    @Test
    fun `the simulation announces itself and every node before any packet`() = runTest {
        val src = SimulatedMeshSource(7L, nodeCount = 12) { currentTime }
        val ev = run(src, HOUR)
        assertEquals(LinkState.Simulated, (ev.first() as MeshEvent.Link).state)
        val seen = ev.filterIsInstance<MeshEvent.NodeSeen>()
        assertEquals(12, seen.size)
        assertTrue(seen.all { it.firstThisSession })
        val firstPacket = ev.indexOfFirst { it is MeshEvent.PacketHeard }
        assertTrue(ev.indexOfLast { it is MeshEvent.NodeSeen } < firstPacket)
    }

    @Test
    fun `per-node inter-arrival of each kind stays within its period plus jitter`() = runTest {
        val src = SimulatedMeshSource(99L, nodeCount = 25) { currentTime }
        val ev = run(src, 8 * HOUR)
        var checked = 0
        for (s in src.streams) {
            val num = src.nodes[s.node].num
            val times = ev.filterIsInstance<MeshEvent.PacketHeard>()
                .filter { it.from == num && it.kind == s.kind }
                .map { it.atMillis }
            for (gap in times.zipWithNext { x, y -> y - x }) {
                val lo = s.periodMs * (1 - s.jitter) - 1
                val hi = s.periodMs * (1 + s.jitter) + 1
                assertTrue(gap >= lo && gap <= hi, "node ${s.node} ${s.kind} gap $gap outside [$lo, $hi]")
                checked++
            }
        }
        assertTrue(checked > 100, "only $checked gaps checked")
    }

    @Test
    fun `periods are minutes to hours as for firmware defaults and jitter is modest`() {
        val src = SimulatedMeshSource(5L, nodeCount = 40) { 0L }
        for (s in src.streams) {
            assertTrue(s.periodMs in 2 * 60_000L..4 * HOUR, "${s.kind} period ${s.periodMs}")
            assertTrue(s.jitter in 0.0..0.3)
        }
        val kinds = src.streams.map { it.kind }.toSet()
        assertTrue(PacketKind.Encrypted in kinds && PacketKind.Text in kinds && PacketKind.Position in kinds)
    }

    @Test
    fun `speed divides the waiting time and leaves the event sequence unchanged`() = runTest {
        val n = 60
        val slow = SimulatedMeshSource(11L) { currentTime }
        val t0 = currentTime
        val a = slow.events.take(n).toList()
        val slowElapsed = currentTime - t0
        val fast = SimulatedMeshSource(11L, speed = 60.0) { currentTime }
        val t1 = currentTime
        val b = fast.events.take(n).toList()
        val fastElapsed = currentTime - t1
        assertEquals(a.map { it.kindKey() }, b.map { it.kindKey() })
        assertTrue(slowElapsed > 60_000L, "slow $slowElapsed")
        assertTrue(fastElapsed in (slowElapsed / 62)..(slowElapsed / 58), "slow $slowElapsed fast $fastElapsed")
    }

    @Test
    fun `a share of simulated packets are encrypted and some hops are unknown`() = runTest {
        val ev = run(SimulatedMeshSource(3L, nodeCount = 40) { currentTime }, 6 * HOUR)
            .filterIsInstance<MeshEvent.PacketHeard>()
        assertTrue(ev.any { it.kind == PacketKind.Encrypted })
        assertTrue(ev.any { it.hopsTaken == null })
        assertTrue(ev.filter { it.hopsTaken != null }.all { it.hopsTaken!! in 0..3 })
        assertTrue(ev.all { it.snr == null || it.snr!! in -20f..15f })
    }

    @Test
    fun `environment readings stay in physical range`() = runTest {
        val ev = run(SimulatedMeshSource(8L, nodeCount = 40) { currentTime }, 24 * HOUR)
            .filterIsInstance<MeshEvent.Environment>()
        assertTrue(ev.isNotEmpty())
        assertTrue(ev.all { it.temperatureC!! in -10f..45f && it.humidityPct!! in 0f..100f && it.pressureHpa!! in 950f..1050f })
    }

    @Test
    fun `Self comes right after the link and relay bytes resolve to a simulated node`() = runTest {
        val src = SimulatedMeshSource(21L, nodeCount = 40) { currentTime }
        val ev = run(src, 8 * HOUR)
        val self = ev[1] as MeshEvent.Self
        assertEquals(src.self.num, self.node)
        val known = src.nodes.map { it.num }
        val heard = ev.filterIsInstance<MeshEvent.PacketHeard>()
        val relayed = heard.filter { (it.hopsTaken ?: 0) > 0 }
        assertTrue(relayed.size > 50, "only ${relayed.size} relayed packets")
        for (h in relayed) {
            val node = resolveRelay(known, h.relayByte, h.from, self.node)
            assertTrue(node != null && node != h.from, "relay ${h.relayByte} of ${h.from} did not resolve")
        }
        assertTrue(heard.filter { it.hopsTaken == 0 || it.hopsTaken == null }.all { it.relayByte == null })
    }

    @Test
    fun `destinations are mostly broadcast and a unicast to us is flagged toUs`() = runTest {
        val src = SimulatedMeshSource(22L, nodeCount = 40) { currentTime }
        val heard = run(src, 8 * HOUR).filterIsInstance<MeshEvent.PacketHeard>()
        val dms = heard.filter { it.toUs }
        assertTrue(dms.isNotEmpty())
        assertTrue(dms.all { it.to == src.self.num })
        assertTrue(heard.filter { !it.toUs }.all { it.to == 0xFFFFFFFFL })
        assertTrue(dms.size * 2 < heard.size, "DMs ${dms.size} of ${heard.size}")
    }

    @Test
    fun `Neighbors and Route events only name simulated nodes`() = runTest {
        val src = SimulatedMeshSource(23L, nodeCount = 60) { currentTime }
        val ev = run(src, 24 * HOUR)
        val known = src.nodes.map { it.num }.toSet()
        val nb = ev.filterIsInstance<MeshEvent.Neighbors>()
        val routes = ev.filterIsInstance<MeshEvent.Route>()
        assertTrue(nb.isNotEmpty() && routes.isNotEmpty(), "neighbors ${nb.size} routes ${routes.size}")
        assertTrue(nb.all { it.node in known && it.neighbors.isNotEmpty() && it.node !in it.neighbors && known.containsAll(it.neighbors) })
        assertTrue(routes.all { it.nodes.first() == src.self.num && it.nodes.drop(1).all { n -> n in known } && it.nodes.size >= 2 })
    }

    @Test
    fun `nodes announce a role and hardware from the fixed mixes and routers are the minority`() = runTest {
        val src = SimulatedMeshSource(31L, nodeCount = 200) { currentTime }
        val seen = run(src, 1_000L).filterIsInstance<MeshEvent.NodeSeen>()
        assertEquals(200, seen.size)
        assertTrue(seen.all { it.role in SIM_ROLES.map { r -> r.first } && it.hardware in SIM_HARDWARE.map { h -> h.first } })
        val roles = seen.groupingBy { it.role }.eachCount()
        assertTrue(roles.size >= 5, "roles $roles")
        assertTrue((roles["CLIENT"] ?: 0) > 70, "CLIENT ${roles["CLIENT"]} of 200 against a weight of 52 percent")
        assertTrue((roles["ROUTER"] ?: 0) < (roles["CLIENT"] ?: 0))
        assertTrue(seen.mapNotNull { it.hardware }.toSet().size >= 6)
        assertTrue(seen.mapNotNull { it.position }.all { it.altitudeM != null && it.altitudeM!! in 0..2000 })
    }

    @Test
    fun `Health arrives for many nodes with battery drain voltage and growing uptime`() = runTest {
        val src = SimulatedMeshSource(32L, nodeCount = 60) { currentTime }
        val health = run(src, 12 * HOUR).filterIsInstance<MeshEvent.Health>()
        val known = src.nodes.map { it.num }.toSet()
        assertTrue(health.all { it.node in known && it.batteryPct != null && it.voltage != null && it.uptimeSeconds != null })
        assertTrue(health.map { it.node }.toSet().size > 50, "only ${health.map { it.node }.toSet().size} nodes reported")
        val powered = health.filter { it.batteryPct == 101 }
        val cell = health.filter { it.batteryPct != 101 }
        assertTrue(powered.isNotEmpty() && cell.isNotEmpty())
        assertTrue(powered.all { it.voltage!! in 4.8f..5.2f })
        // a lithium cell reads 3.3 V empty to 4.2 V full, plus 10 mV of noise
        assertTrue(cell.all { it.batteryPct!! in 3..100 && it.voltage!! in 3.29f..4.21f })
        for ((_, list) in health.groupBy { it.node }) {
            assertTrue(list.zipWithNext().all { (a, b) -> b.uptimeSeconds!! > a.uptimeSeconds!! && b.batteryPct!! <= a.batteryPct!! })
        }
        assertTrue(health.groupBy { it.node }.values.any { it.size >= 20 }, "a node should report about every 30 minutes")
    }

    @Test
    fun `Stats for self count every packet heard before it and never run backwards`() = runTest {
        val src = SimulatedMeshSource(33L, nodeCount = 40) { currentTime }
        val ev = run(src, 6 * HOUR)
        var heard = 0L
        var stats = 0
        var last: MeshEvent.Stats? = null
        for (e in ev) {
            if (e is MeshEvent.PacketHeard) heard++
            if (e is MeshEvent.Stats) {
                stats++
                assertEquals(heard, e.rxTotal, "rxTotal against packets heard so far")
                assertTrue(e.rxDupe!! <= e.rxTotal!! && e.rxBad!! <= e.rxTotal!!)
                assertTrue(e.noiseFloorDbm!! in -125..-95 && e.numTotal == 41 && e.numOnline!! in 1..41)
                if (last != null) assertTrue(e.uptimeSeconds!! > last.uptimeSeconds!! && e.rxDupe!! >= last.rxDupe!!)
                last = e
            }
        }
        assertTrue(stats >= 10, "stats events $stats")
    }

    @Test
    fun `some environment nodes report wind with gust above speed above lull`() = runTest {
        val env = run(SimulatedMeshSource(34L, nodeCount = 100) { currentTime }, 6 * HOUR).filterIsInstance<MeshEvent.Environment>()
        val wind = env.filter { it.windMps != null }
        assertTrue(wind.isNotEmpty(), "no wind reports")
        assertTrue(wind.map { it.node }.toSet().size < env.map { it.node }.toSet().size)
        assertTrue(wind.all { it.windDirectionDeg!! in 0f..360f && it.windGustMps!! >= it.windMps!! && it.windMps!! >= it.windLullMps!! && it.windLullMps!! >= 0f })
        assertTrue(env.filter { it.windMps == null }.all { it.windDirectionDeg == null && it.windGustMps == null && it.windLullMps == null })
    }

    @Test
    fun `packets carry payload sizes and channels and only text has a length`() = runTest {
        val heard = run(SimulatedMeshSource(35L, nodeCount = 60) { currentTime }, 12 * HOUR).filterIsInstance<MeshEvent.PacketHeard>()
        val text = heard.filter { it.kind == PacketKind.Text }
        assertTrue(text.size > 20 && heard.size > 200)
        assertTrue(text.all { it.textLength!! in 4..200 && it.payloadBytes == it.textLength })
        assertTrue(heard.filter { it.kind != PacketKind.Text }.all { it.textLength == null })
        assertTrue(heard.all { it.payloadBytes!! in 2..200 })
        assertTrue(heard.filter { it.kind == PacketKind.Encrypted }.all { it.channel == null })
        val channels = heard.filter { it.kind != PacketKind.Encrypted }.map { it.channel }
        assertTrue(channels.all { it in 0..3 } && channels.toSet().size >= 3)
        assertTrue(channels.count { it == 0 } * 2 > channels.size, "channel 0 is the primary and most used")
    }

    private fun MeshEvent.shifted(by: Long): MeshEvent = when (this) {
        is MeshEvent.PacketHeard -> copy(atMillis = atMillis + by)
        is MeshEvent.NodeSeen -> copy(atMillis = atMillis + by)
        is MeshEvent.NodeGone -> copy(atMillis = atMillis + by)
        is MeshEvent.Environment -> copy(atMillis = atMillis + by)
        is MeshEvent.Vitals -> copy(atMillis = atMillis + by)
        is MeshEvent.Link -> copy(atMillis = atMillis + by)
        is MeshEvent.Self -> copy(atMillis = atMillis + by)
        is MeshEvent.Neighbors -> copy(atMillis = atMillis + by)
        is MeshEvent.Route -> copy(atMillis = atMillis + by)
        is MeshEvent.Health -> copy(atMillis = atMillis + by)
        is MeshEvent.Stats -> copy(atMillis = atMillis + by)
    }

    private fun MeshEvent.kindKey(): String = when (this) {
        is MeshEvent.PacketHeard -> "P$from$kind"
        is MeshEvent.NodeSeen -> "N$node"
        is MeshEvent.Environment -> "E$node"
        is MeshEvent.Vitals -> "V"
        is MeshEvent.Link -> "L$state"
        is MeshEvent.NodeGone -> "G$node"
        is MeshEvent.Self -> "S$node"
        is MeshEvent.Neighbors -> "B$node"
        is MeshEvent.Route -> "R${nodes.joinToString(",")}"
        is MeshEvent.Health -> "H$node"
        is MeshEvent.Stats -> "T"
    }
}
