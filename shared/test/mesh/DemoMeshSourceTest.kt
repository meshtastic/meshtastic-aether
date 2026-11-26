package org.meshtastic.aether.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.meshtastic.aether.mood.MeshMood
import org.meshtastic.aether.mood.MoodSnapshot
import org.meshtastic.aether.music.Form
import org.meshtastic.aether.music.Voice
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
        val tempBefore = before.mapNotNull { it.temperatureC }
        assertTrue(after.minOf { it.temperatureC!! } - tempBefore.max() >= 5f)
        assertTrue(before.minOf { it.pressureHpa!! } - after.maxOf { it.pressureHpa!! } >= 5f)
    }

    // The mood reads a pressure trend only from readings about three hours apart; the demo lasts five minutes.
    @Test
    fun `the first pass carries three hours of rising pressure from at least three nodes before the front falls`() {
        val history = script.filterIsInstance<MeshEvent.Environment>().filter { it.atMillis <= 0L }
        val nodes = history.map { it.node }.toSet()
        assertTrue(nodes.size >= 3, "three nodes give full confidence")
        for (n in nodes) {
            val mine = history.filter { it.node == n }
            assertTrue(mine.last().atMillis - mine.first().atMillis >= 3 * 3_600_000L, "span of three hours")
            assertTrue(mine.zipWithNext().all { (a, b) -> b.atMillis - a.atMillis >= 5 * 60_000L }, "readings five minutes apart or more")
            assertTrue(mine.last().pressureHpa!! - mine.first().pressureHpa!! >= 4f, "rising by hPa per 3 h before the front")
            assertTrue(mine.zipWithNext().all { (a, b) -> b.pressureHpa!! >= a.pressureHpa!! })
        }
        val after = script.filterIsInstance<MeshEvent.Environment>().filter { it.atMillis >= DemoScript.WEATHER_SHIFT_MS }
        val fallen = after.filter { it.node in nodes }.map { it.node }.toSet()
        assertTrue(fallen.size >= 3, "the front reaches at least three of the reporting nodes")
        assertTrue(after.all { it.pressureHpa!! < history.minOf { h -> h.pressureHpa!! } }, "below everything the history read")
    }

    @Test
    fun `a later pass has no history and holds the pressure low`() {
        val later = DemoScript.events(0L, first = false)
        assertTrue(later.all { it.atMillis >= 0L }, "nothing stamped before the pass")
        val lowest = script.filterIsInstance<MeshEvent.Environment>().filter { it.atMillis <= 0L }.minOf { it.pressureHpa!! }
        assertTrue(later.filterIsInstance<MeshEvent.Environment>().all { it.pressureHpa!! < lowest })
    }

    // Brief: a router, a tracker and a sensor each speak with their roles and hardware.
    @Test
    fun `a router a tracker and a sensor speak after arriving with their role and hardware`() {
        val seen = script.filterIsInstance<MeshEvent.NodeSeen>().associateBy { it.node }
        fun spoke(node: Int, role: String, atMs: Long) {
            val n = seen.getValue(DemoScript.nodeNum(node))
            assertEquals(role, n.role)
            assertTrue(!n.hardware.isNullOrEmpty())
            val p = script.filterIsInstance<MeshEvent.PacketHeard>().single { it.atMillis == atMs && it.from == n.node }
            assertTrue(p.atMillis > n.atMillis)
        }
        spoke(DemoScript.ROUTER_NODE, "ROUTER", DemoScript.ROUTER_MS)
        spoke(DemoScript.TRACKER_NODE, "TRACKER", DemoScript.TRACKER_MS)
        spoke(DemoScript.SENSOR_NODE, "SENSOR", DemoScript.SENSOR_MS)
        assertEquals(3, seen.values.map { it.hardware }.distinct().count { it in setOf("STATION_G2", "T_ECHO", "RAK4631") })
    }

    @Test
    fun `a low battery node speaks while another node sits on high ground and one packet is MQTT relayed`() {
        val health = script.filterIsInstance<MeshEvent.Health>().single { it.node == DemoScript.nodeNum(DemoScript.LOW_BATTERY_NODE) }
        assertEquals(DemoScript.LOW_BATTERY_PCT, health.batteryPct)
        assertTrue(script.filterIsInstance<MeshEvent.PacketHeard>().any {
            it.from == health.node && it.atMillis == DemoScript.LOW_BATTERY_MS
        })
        val high = script.filterIsInstance<MeshEvent.NodeSeen>().single { it.node == DemoScript.nodeNum(DemoScript.HIGH_GROUND_NODE) }
        val others = script.filterIsInstance<MeshEvent.NodeSeen>().filter { it !== high }
        assertTrue(high.position!!.altitudeM!! >= 2_000 && others.all { it.position!!.altitudeM!! < 1_000 })
        assertTrue(script.filterIsInstance<MeshEvent.PacketHeard>().any {
            it.viaMqtt && it.atMillis == DemoScript.MQTT_MS && it.from != DemoScript.nodeNum(DemoScript.NODE_COUNT - 1)
        })
        assertTrue(script.filterIsInstance<MeshEvent.PacketHeard>().any { it.atMillis == DemoScript.HIGH_GROUND_MS && it.from == high.node })
    }

    // Firmware: Power.h default OCV_ARRAY, 100 percent 4190 mV, 50 percent 3720, 10 percent 3300, 0 percent 3100; 101 means powered.
    @Test
    fun `battery voltages follow the firmware LiPo curve and a powered node reports no voltage`() {
        assertEquals(4.19f, DemoScript.lipoVolts(100), 1e-4f)
        assertEquals(3.72f, DemoScript.lipoVolts(50), 1e-4f)
        assertEquals(3.3f, DemoScript.lipoVolts(10), 1e-4f)
        assertEquals(3.22f, DemoScript.lipoVolts(6), 1e-4f)
        assertEquals(3.1f, DemoScript.lipoVolts(0), 1e-4f)
        val router = script.filterIsInstance<MeshEvent.Health>().single { it.node == DemoScript.nodeNum(DemoScript.ROUTER_NODE) }
        assertEquals(101, router.batteryPct)
        assertEquals(null, router.voltage)
    }

    // Brief: activity rises and falls through the loop.
    @Test
    fun `the packet rate rises toward the middle of the loop and falls again`() {
        fun packets(fromMs: Long, toMs: Long) = script.count {
            it is MeshEvent.PacketHeard && it.kind != PacketKind.NodeInfo && it.atMillis in fromMs until toMs
        }
        val early = packets(12_000, 42_000)
        val busy = packets(110_000, 140_000)
        val late = packets(215_000, 245_000)
        assertTrue(busy >= 2 * early, "busy $busy early $early")
        assertTrue(busy >= 2 * late, "busy $busy late $late")
    }

    @Test
    fun `texts carry their length and every packet a size and a channel`() {
        val packets = script.filterIsInstance<MeshEvent.PacketHeard>()
        assertTrue(packets.all { it.payloadBytes != null && it.channel != null })
        assertTrue(packets.filter { it.kind == PacketKind.Text }.all { it.textLength != null })
        assertTrue(packets.filter { it.kind != PacketKind.Text }.all { it.textLength == null })
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
        assertTrue(got.take(first.size).all { (at, e) -> at == maxOf(e.atMillis, 0L) }, "emitted at its own time and the history at once")
        val later = DemoScript.events(DemoScript.LOOP_MS, first = false)
        val played = got.drop(first.size).map { it.second }
        assertTrue(played.size > 5)
        assertEquals(later.take(played.size), played, "the second pass has no history")
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

    // Brief: the pressure fall reverses the rotation. The mood reads the first pass as rising until the front, then falling.
    @Test
    fun `the mood turns the rotation counter-clockwise after the front and not before`() {
        val mood = MeshMood()
        var before: MoodSnapshot? = null
        var after: MoodSnapshot? = null
        for (e in script) {
            if (e.atMillis > DemoScript.WEATHER_SHIFT_MS + 60_000L) break
            if (e.atMillis > DemoScript.WEATHER_SHIFT_MS - 1_000L && before == null) before = mood.advance(DemoScript.WEATHER_SHIFT_MS - 1_000L)
            mood.onEvent(e)
        }
        after = mood.advance(DemoScript.WEATHER_SHIFT_MS + 60_000L)
        assertEquals(1, before!!.params.rotationDirection)
        assertEquals("pressure trend", before.params.drivers.rotationDirection)
        assertEquals(-1, after.params.rotationDirection)
        assertTrue(after.rotation.directionStrength >= 0.25, "past the hysteresis band")
    }
}
