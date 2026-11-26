package org.meshtastic.ambient.visual

import org.meshtastic.ambient.mesh.GeoPoint
import org.meshtastic.ambient.mesh.LinkState
import org.meshtastic.ambient.mesh.MeshEvent
import org.meshtastic.ambient.mesh.PacketKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SceneModelTest {
    private val sr = 48_000L
    private val origin = GeoPoint(45.0, -93.0)

    private fun scene() = SceneModel(sr.toInt()).also { it.origin = origin }

    private fun at(distanceMeters: Double, bearingDeg: Double): GeoPoint {
        val b = bearingDeg * PI / 180.0
        val dLat = distanceMeters * cos(b) / 111_320.0
        val dLon = distanceMeters * sin(b) / (111_320.0 * cos(origin.lat * PI / 180.0))
        return GeoPoint(origin.lat + dLat, origin.lon + dLon)
    }

    private fun seen(node: Long, p: GeoPoint?) = MeshEvent.NodeSeen(node, null, p, true, 0)

    private fun heard(node: Long, kind: PacketKind = PacketKind.Text, hops: Int? = 0) =
        MeshEvent.PacketHeard(node, kind, 5f, -80, hops, false, 0)

    private fun radius(o: Orb) = hypot(o.x, o.y)

    @Test
    fun `one far outlier is pinned to the edge while the rest stay spread inside the fit`() {
        val s = scene()
        for (i in 0 until 39) s.onEvent(seen(100L + i, at(100.0 + 50.0 * i, i * 37.0)), 0)
        s.onEvent(seen(999, at(4_400_000.0, 20.0)), 0)
        s.update(0)
        val outlier = s.orbs.first { it.node == 999L }
        val rest = s.orbs.filter { it.node != 999L }
        assertTrue(radius(outlier) >= 0.999, "outlier at ${radius(outlier)}")
        assertTrue(radius(outlier) <= 1.0)
        assertTrue(rest.count { radius(it) <= 0.95 } >= 38, "95 percent inside the fit")
        assertTrue(rest.maxOf { radius(it) } >= 0.85, "the outlier must not squash the cluster")
    }

    @Test
    fun `north is up and east is right relative to our node`() {
        val s = scene()
        s.onEvent(seen(1, at(1000.0, 0.0)), 0)
        s.onEvent(seen(2, at(1000.0, 90.0)), 0)
        s.update(0)
        val north = s.orbs.first { it.node == 1L }
        val east = s.orbs.first { it.node == 2L }
        assertTrue(north.y < -0.1 && kotlin.math.abs(north.x) < 0.05)
        assertTrue(east.x > 0.1 && kotlin.math.abs(east.y) < 0.05)
    }

    @Test
    fun `a ripple is not spawned before its frame and is at it`() {
        val s = scene()
        s.onEvent(seen(7, at(500.0, 10.0)), 0)
        s.onEvent(heard(7), 48_000)
        s.update(47_999)
        assertTrue(s.ripples.isEmpty())
        s.update(48_000)
        assertEquals(1, s.ripples.size)
        assertEquals(48_000L, s.ripples[0].startFrame)
    }

    @Test
    fun `hop echo rings come later and fainter than the packet ring and only for hops`() {
        val s = scene()
        s.onEvent(heard(1, hops = 0), 10_000)
        s.onEvent(heard(2, hops = 6), 10_000)
        s.update(10_000)
        assertEquals(listOf(0, 0), s.ripples.map { it.echo })
        s.update(10_000 + sr * 2)
        val direct = s.ripples.filter { it.node == 1L }
        val far = s.ripples.filter { it.node == 2L }
        assertEquals(1, direct.size)
        assertTrue(far.size > 1)
        val main = far.first { it.echo == 0 }
        far.filter { it.echo > 0 }.forEach {
            assertTrue(it.startFrame > main.startFrame)
            assertTrue(it.strength < main.strength)
        }
    }

    @Test
    fun `encrypted traffic raises a haze that fades and spawns no ripple`() {
        val s = scene()
        s.onEvent(heard(5, PacketKind.Encrypted), 1_000)
        s.update(999)
        assertEquals(0.0, s.haze)
        s.update(1_000)
        assertTrue(s.haze > 0.05)
        assertTrue(s.ripples.isEmpty())
        s.update(1_000 + sr * 120)
        assertTrue(s.haze < 0.01)
    }

    @Test
    fun `packets heard while warming up make no ripple and later ones do`() {
        val s = scene()
        s.onEvent(MeshEvent.Link(LinkState.WarmingUp, 0), 0)
        s.onEvent(heard(3), 100)
        s.update(200)
        assertTrue(s.ripples.isEmpty())
        s.onEvent(MeshEvent.Link(LinkState.Connected, 0), 0)
        s.onEvent(heard(3), 300)
        s.update(400)
        assertEquals(1, s.ripples.size)
    }

    @Test
    fun `glow flashes at a packet and fades with silence`() {
        val s = scene()
        s.onEvent(seen(4, at(300.0, 0.0)), 0)
        s.onEvent(heard(4), 1_000)
        s.update(1_000)
        val orb = s.orbs.first()
        val flash = s.glow(orb)
        s.update(1_000 + sr * 600)
        val tenMinutes = s.glow(orb)
        s.update(1_000 + sr * 3 * 3600)
        val threeHours = s.glow(orb)
        assertTrue(flash > 0.8, "flash $flash")
        assertTrue(flash > tenMinutes && tenMinutes > threeHours)
        assertTrue(threeHours < 0.01)
    }

    @Test
    fun `an unpositioned node sits at the same place whatever else is on screen`() {
        val alone = scene()
        alone.onEvent(seen(0xABCDL, null), 0)
        alone.update(0)
        val crowded = scene()
        for (i in 1L..9L) crowded.onEvent(seen(i, at(100.0 * i, i * 40.0)), 0)
        crowded.onEvent(seen(0xABCDL, null), 0)
        crowded.update(0)
        val a = alone.orbs.first()
        val b = crowded.orbs.first { it.node == 0xABCDL }
        assertEquals(a.x, b.x)
        assertEquals(a.y, b.y)
        assertTrue(radius(a) in 0.25..0.85)

        alone.onEvent(seen(0x1234L, null), 0)
        alone.update(1)
        assertEquals(a.x, alone.orbs.first { it.node == 0xABCDL }.x)
        val other = alone.orbs.first { it.node == 0x1234L }
        assertNotEquals(a.x, other.x)
    }

    @Test
    fun `a node that is gone leaves the scene`() {
        val s = scene()
        s.onEvent(seen(8, null), 0)
        s.onEvent(MeshEvent.NodeGone(8, 0), 0)
        s.update(0)
        assertTrue(s.orbs.isEmpty())
    }

    @Test
    fun `a burst of packets never leaves more than the ripple cap on screen`() {
        val s = scene()
        for (i in 0 until 1000) s.onEvent(heard(i.toLong()), 100)
        s.update(100)
        assertTrue(s.ripples.size <= SceneModel.MAX_RIPPLES)
        s.update(100 + (sr * (SceneModel.RIPPLE_LIFE_SECONDS + 1)).toLong())
        assertTrue(s.ripples.isEmpty())
    }

    @Test
    fun `the scene is dimmer at 2am than at 2pm and never fully dark`() {
        assertTrue(SceneModel.nightDim(2 * 60) < SceneModel.nightDim(14 * 60))
        assertEquals(1.0, SceneModel.nightDim(14 * 60), 1e-9)
        assertTrue((0 until 1440).all { SceneModel.nightDim(it) in 0.4..1.0 })
    }

    private val me = 0x00AA0001L

    private fun withSelf(): SceneModel = scene().also { it.onEvent(MeshEvent.Self(me, "me", null, 0), 0) }

    private fun toUs(from: Long, relay: Int? = null) =
        MeshEvent.PacketHeard(from, PacketKind.Text, 5f, -80, 1, false, 0, to = me, relayByte = relay, toUs = true)

    @Test
    fun `a pulse to our node is not spawned before its start and reaches the anchor at the note frame`() {
        val s = withSelf()
        s.onEvent(seen(0x11, at(500.0, 30.0)), 0)
        s.onEvent(toUs(0x11), 100_000)
        val start = 100_000 - (SceneModel.TRAVEL_SECONDS * sr).toLong()
        s.update(start - 1)
        assertTrue(s.pulses.isEmpty())
        s.update(100_000)
        assertEquals(1, s.pulses.size)
        val p = s.pulses[0]
        assertEquals(0x11L, p.fromNode)
        assertEquals(null, p.toNode)
        assertEquals(100_000L, p.endFrame)
        assertEquals(1.0, s.progress(p), 1e-9)
    }

    @Test
    fun `a resolved relay adds a second pulse in the relay color arriving at the same frame`() {
        val s = withSelf()
        s.onEvent(seen(0x11, at(500.0, 30.0)), 0)
        s.onEvent(seen(0x2277, at(800.0, 200.0)), 0)
        s.onEvent(toUs(0x11, relay = 0x77), 100_000)
        s.update(100_000)
        assertEquals(2, s.pulses.size)
        val relay = s.pulses.first { it.fromNode == 0x2277L }
        val direct = s.pulses.first { it.fromNode == 0x11L }
        assertEquals(0x2277L, relay.colorNode)
        assertEquals(direct.endFrame, relay.endFrame)
        assertEquals(direct.startFrame + (direct.endFrame - direct.startFrame) / 2, relay.startFrame)
    }

    @Test
    fun `an unresolved relay byte draws no extra pulse`() {
        val s = withSelf()
        s.onEvent(seen(0x11, at(500.0, 30.0)), 0)
        s.onEvent(toUs(0x11, relay = 0x99), 100_000)
        s.update(100_000)
        assertEquals(1, s.pulses.size)
    }

    @Test
    fun `a broadcast keeps its ripple and spawns no source pulse`() {
        val s = withSelf()
        s.onEvent(heard(0x11), 1_000)
        s.update(1_000)
        assertEquals(1, s.ripples.size)
        assertTrue(s.pulses.isEmpty())
    }

    @Test
    fun `neighbor edges fade within a minute and refresh when reported again`() {
        val s = withSelf()
        s.onEvent(seen(1, at(100.0, 0.0)), 0)
        s.onEvent(seen(2, at(100.0, 90.0)), 0)
        s.onEvent(MeshEvent.Neighbors(1, listOf(2), 0), 0)
        s.update(sr * 59)
        assertEquals(1, s.edges.size)
        s.onEvent(MeshEvent.Neighbors(2, listOf(1), 0), 0)
        s.update(sr * 100)
        assertEquals(1, s.edges.size, "refreshed at 59 s so alive at 100 s")
        s.update(sr * 120)
        assertTrue(s.edges.isEmpty())
    }

    @Test
    fun `a route with an unknown middle node skips it and pulses 400 ms per hop`() {
        val s = withSelf()
        s.onEvent(seen(1, at(100.0, 0.0)), 0)
        s.onEvent(seen(3, at(200.0, 90.0)), 0)
        s.onEvent(MeshEvent.Route(listOf(1, 2, 3, me), 0), 0)
        s.update(0)
        assertEquals(1, s.pulses.size)
        assertEquals(1L, s.pulses[0].fromNode)
        assertEquals(3L, s.pulses[0].toNode)
        s.update(19_200)
        assertEquals(2, s.pulses.size)
        assertEquals(3L, s.pulses[1].fromNode)
        assertEquals(null, s.pulses[1].toNode)
        assertEquals(19_200L, s.pulses[1].startFrame)
    }

    @Test
    fun `the anchor stays at the centre while orbs drift and layout shifts`() {
        val s = scene()
        s.onEvent(MeshEvent.Self(me, "me", at(0.0, 0.0), 0), 0)
        s.onEvent(seen(1, at(300.0, 45.0)), 0)
        s.update(0)
        val a = s.anchor!!
        assertEquals(0.0, a.x)
        assertEquals(0.0, a.y)
        assertTrue(s.driftX(1, 0.0) != s.driftX(1, 1000.0))
        s.onEvent(seen(2, at(9000.0, 200.0)), 0)
        s.update(10)
        assertEquals(0.0, s.anchor!!.x)
        assertEquals(0.0, s.anchor!!.y)
        assertTrue(s.orbs.none { it.node == me })
    }
}
