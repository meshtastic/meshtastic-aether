package org.meshtastic.ambient.mesh

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

    private fun MeshEvent.shifted(by: Long): MeshEvent = when (this) {
        is MeshEvent.PacketHeard -> copy(atMillis = atMillis + by)
        is MeshEvent.NodeSeen -> copy(atMillis = atMillis + by)
        is MeshEvent.NodeGone -> copy(atMillis = atMillis + by)
        is MeshEvent.Environment -> copy(atMillis = atMillis + by)
        is MeshEvent.Vitals -> copy(atMillis = atMillis + by)
        is MeshEvent.Link -> copy(atMillis = atMillis + by)
    }

    private fun MeshEvent.kindKey(): String = when (this) {
        is MeshEvent.PacketHeard -> "P$from$kind"
        is MeshEvent.NodeSeen -> "N$node"
        is MeshEvent.Environment -> "E$node"
        is MeshEvent.Vitals -> "V"
        is MeshEvent.Link -> "L$state"
        is MeshEvent.NodeGone -> "G$node"
    }
}
