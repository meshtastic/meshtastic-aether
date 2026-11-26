package org.meshtastic.ambient.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.meshtastic.ambient.music.Form
import org.meshtastic.ambient.music.Voice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DemoMeshSourceTest {
    private val script = DemoScript.events(0L)

    // The brief fixes the story order: nodes arrive, a DM, a traceroute, a chord-changing text, a weather shift, a rest.
    @Test
    fun `the script tells its story in the briefed order`() {
        val lastArrival = script.indexOfLast { it is MeshEvent.NodeSeen }
        val dm = script.indexOfFirst { it is MeshEvent.PacketHeard && it.toUs && it.kind == PacketKind.Text }
        val route = script.indexOfFirst { it is MeshEvent.Route }
        val text = script.indexOfFirst { it is MeshEvent.PacketHeard && !it.toUs && it.kind == PacketKind.Text }
        val shift = script.indexOfFirst { it.atMillis == DemoScript.WEATHER_SHIFT_MS && it is MeshEvent.Environment }
        assertTrue(lastArrival < dm, "dm after the last arrival")
        assertTrue(dm < route, "traceroute after the dm")
        assertTrue(route < text, "text after the traceroute")
        assertTrue(text < shift, "weather shift after the text")
        val lastPacket = script.indexOfLast { it is MeshEvent.PacketHeard }
        assertTrue(script[lastPacket].atMillis <= DemoScript.REST_STARTS_MS, "no packet in the rest")
        assertTrue(DemoScript.LOOP_MS - script[lastPacket].atMillis >= 45_000L, "rest of at least 45 s")
    }

    @Test
    fun `nodes arrive one by one at least five seconds apart and are all new`() {
        val arrivals = script.filterIsInstance<MeshEvent.NodeSeen>()
        assertEquals(DemoScript.NODE_COUNT, arrivals.size)
        assertTrue(arrivals.all { it.firstThisSession && it.name != null && it.position != null })
        assertTrue(arrivals.zipWithNext().all { (a, b) -> b.atMillis - a.atMillis >= 5_000L })
    }

    // Regression: a packet from a node nobody has seen arrive would sound at a made-up place.
    @Test
    fun `no node speaks before it has arrived`() {
        val arrivedAt = script.filterIsInstance<MeshEvent.NodeSeen>().associate { it.node to it.atMillis }
        for (e in script.filterIsInstance<MeshEvent.PacketHeard>()) {
            val at = arrivedAt[e.from]
            assertTrue(at != null && e.atMillis >= at, "node ${e.from} at ${e.atMillis}")
        }
    }

    @Test
    fun `the direct message is addressed to our own node and the traceroute ends at it`() {
        val dm = script.filterIsInstance<MeshEvent.PacketHeard>().single { it.toUs }
        assertEquals(DemoScript.SELF, dm.to)
        val route = script.filterIsInstance<MeshEvent.Route>().single()
        assertTrue(route.nodes.size >= 3, "crosses the field")
        assertEquals(DemoScript.SELF, route.nodes.last())
    }

    @Test
    fun `the weather shift is a step of at least five degrees and a pressure drop`() {
        val env = script.filterIsInstance<MeshEvent.Environment>()
        val before = env.filter { it.atMillis < DemoScript.WEATHER_SHIFT_MS }
        val after = env.filter { it.atMillis >= DemoScript.WEATHER_SHIFT_MS }
        assertTrue(before.isNotEmpty() && after.isNotEmpty())
        assertTrue(after.minOf { it.temperatureC!! } - before.maxOf { it.temperatureC!! } >= 5f)
        assertTrue(before.minOf { it.pressureHpa!! } - after.maxOf { it.pressureHpa!! } >= 5f)
    }

    @Test
    fun `the script is the same on every call and rebases to any start time`() {
        assertEquals(script, DemoScript.events(0L))
        val moved = DemoScript.events(5_000_000L)
        assertEquals(script.map { it.atMillis + 5_000_000L }, moved.map { it.atMillis })
        assertTrue(script.zipWithNext().all { (a, b) -> a.atMillis <= b.atMillis })
    }

    @Test
    fun `the source plays the script on the clock and starts the next pass after the loop length`() = runTest {
        val got = ArrayList<Pair<Long, MeshEvent>>()
        val job = launch { DemoMeshSource({ currentTime }).events.collect { got += currentTime to it } }
        advanceTimeBy(DemoScript.LOOP_MS + 20_000L)
        job.cancel()
        val first = DemoScript.events(0L)
        assertEquals(first, got.take(first.size).map { it.second })
        assertTrue(got.take(first.size).all { (at, e) -> at == e.atMillis }, "emitted at its own time")
        val next = got[first.size]
        assertTrue(next.second is MeshEvent.Link)
        assertEquals(DemoScript.LOOP_MS, next.first)
        assertEquals(DemoScript.LOOP_MS, next.second.atMillis)
    }

    // Regression: demo mode pins the form at full; the arc must not dip into its quiet points or its rests.
    @Test
    fun `a pinned form holds every layer at full level at times the free form dips`() {
        val free = Form(7L)
        val pinned = Form(7L, pinnedFull = true)
        var freeDipped = false
        for (minute in 0..400 step 3) {
            val t = minute * 60.0
            val levels = pinned.levels(t)
            assertEquals(0.9f, levels.getValue(Voice.Drone), 1e-6f, "drone at $minute")
            assertEquals(0.8f, levels.getValue(Voice.Pad), 1e-6f, "pad at $minute")
            assertEquals(0.7f, levels.getValue(Voice.Bell), 1e-6f, "bell at $minute")
            assertEquals(0.5f, levels.getValue(Voice.Shimmer), 1e-6f, "shimmer at $minute")
            if (free.levels(t).getValue(Voice.Pad) < 0.8f) freeDipped = true
        }
        assertTrue(freeDipped, "the free form dips somewhere in 400 minutes")
    }
}
