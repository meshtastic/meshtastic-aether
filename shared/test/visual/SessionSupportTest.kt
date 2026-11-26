package org.meshtastic.ambient.visual

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.meshtastic.ambient.mesh.LinkState
import org.meshtastic.ambient.mesh.MeshEvent
import org.meshtastic.ambient.mesh.PacketKind
import org.meshtastic.ambient.mesh.ReplayMeshSource
import org.meshtastic.ambient.ui.DemoCapture
import org.meshtastic.ambient.ui.LoopingReplaySource
import org.meshtastic.ambient.ui.localMoment
import org.meshtastic.ambient.ui.shifted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SessionSupportTest {
    private fun millis(iso: String) = Instant.parse(iso).toEpochMilliseconds()

    @Test
    fun `sunday 23 59 UTC is sunday in UTC and monday just after midnight in Berlin`() {
        val ms = millis("2026-11-22T23:59:00Z")
        val utc = localMoment(ms, TimeZone.UTC)
        assertEquals(7, utc.dayOfWeek)
        assertEquals(23 * 60 + 59, utc.minuteOfDay)
        val berlin = localMoment(ms, TimeZone.of("Europe/Berlin"))
        assertEquals(1, berlin.dayOfWeek)
        assertEquals(59, berlin.minuteOfDay)
        assertEquals(ms, berlin.epochMillis)
    }

    @Test
    fun `shifting moves every event time and nothing else`() {
        val events = listOf(
            MeshEvent.PacketHeard(1, PacketKind.Text, 1f, -2, 3, true, 100),
            MeshEvent.NodeSeen(1, "a", null, true, 100),
            MeshEvent.NodeGone(1, 100),
            MeshEvent.Environment(1, 1f, 2f, 3f, 4f, 5f, 100),
            MeshEvent.Vitals(1f, 2f, 100),
            MeshEvent.Link(LinkState.Replaying, 100),
        )
        for (e in events) {
            val moved = e.shifted(1_000)
            assertEquals(1_100L, moved.atMillis)
            assertEquals(e.shifted(0), e)
            assertEquals(e, moved.shifted(-1_000))
        }
    }

    @Test
    fun `a looping replay lands its first event at now and keeps the capture gaps`() = runTest {
        val now = 5_000_000_000L
        val got = LoopingReplaySource(DemoCapture::lines, speed = 1.0, nowMillis = { now }).events.take(14).toList()
        assertTrue(got.first() is MeshEvent.Link)
        assertEquals(now, got.first().atMillis)
        val raw = ReplayMeshSource(DemoCapture.lines()).events.take(14).toList()
        assertEquals(raw.map { it.atMillis - raw.first().atMillis }, got.map { it.atMillis - now })
    }

    @Test
    fun `the demo capture yields positioned nodes and a mix of packet kinds`() = runTest {
        val all = ReplayMeshSource(DemoCapture.lines(), speed = 1_000_000.0).events.toList()
        val nodes = all.filterIsInstance<MeshEvent.NodeSeen>()
        assertTrue(nodes.size >= 9, "nodes ${nodes.size}")
        assertTrue(nodes.all { it.position != null && it.name != null })
        val kinds = all.filterIsInstance<MeshEvent.PacketHeard>().map { it.kind }.toSet()
        for (k in listOf(PacketKind.Text, PacketKind.Position, PacketKind.Telemetry, PacketKind.Encrypted)) {
            assertTrue(k in kinds, "missing $k")
        }
        assertTrue(all.any { it is MeshEvent.Environment })
    }
}
