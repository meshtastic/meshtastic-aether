package org.meshtastic.aether.visual

import org.meshtastic.aether.mesh.GeoPoint
import org.meshtastic.aether.mesh.LinkState
import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.mesh.PacketKind
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

    // Design: the idle core is at least 0.35 when seen and never decays below 0.2; a flash still tops 0.8.
    // Changed from the old test, which expected the glow to reach zero after three hours.
    @Test
    fun `glow flashes at a packet and settles on an idle floor that never vanishes`() {
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
        s.update(1_000 + sr * 100 * 3600)
        val longAfter = s.glow(orb)
        assertTrue(flash > 0.8, "flash $flash")
        assertTrue(flash > tenMinutes && tenMinutes > threeHours)
        assertTrue(threeHours >= 0.2 && longAfter >= 0.2, "floor $threeHours $longAfter")
        assertEquals(0.2, longAfter, 0.005)
    }

    // Regression: on a sparse mesh nearly every orb sat near zero within 30 minutes (review: idle 0.35, floor 0.2).
    @Test
    fun `a node that was only seen starts at the idle core and a day later is still at the floor`() {
        val s = scene()
        s.onEvent(seen(4, at(300.0, 0.0)), 0)
        s.update(0)
        val orb = s.orbs.first()
        assertEquals(SceneModel.IDLE_CORE, s.glow(orb), 1e-9)
        assertTrue(SceneModel.IDLE_CORE >= 0.35)
        s.update(sr * 1800)
        assertTrue(s.glow(orb) > 0.25, "half an hour in: ${s.glow(orb)}")
        s.update(sr * 24 * 3600)
        assertEquals(0.2, s.glow(orb), 0.001)
    }

    private fun note(node: Long?, at: Long = 0L, velocity: Float = 0.8f) =
        org.meshtastic.aether.music.NoteEvent(at, org.meshtastic.aether.music.Voice.Bell, 440.0, velocity, 0f, 0f, 1000, 0.5f, node)

    // Regression: memory and motif notes sounded with no visual (review: only packets drew).
    @Test
    fun `a note with a node pulses that orb at its frame and no other`() {
        val s = scene()
        s.onEvent(seen(1, at(300.0, 0.0)), 0)
        s.onEvent(seen(2, at(300.0, 90.0)), 0)
        s.update(0)
        val one = s.orb(1)!!
        val two = s.orb(2)!!
        val before = s.glow(one)
        s.onNote(note(1), 10_000)
        s.update(9_999)
        assertEquals(before, s.glow(one), 1e-3)
        s.update(10_000)
        assertTrue(s.glow(one) > before + 0.2, "pulse ${s.glow(one)} vs $before")
        assertEquals(before, s.glow(two), 1e-3)
        s.update(10_000 + sr * 30)
        assertEquals(s.glow(two), s.glow(one), 1e-3)
    }

    @Test
    fun `a note without a node or for an unknown node changes nothing and does not crash`() {
        val s = scene()
        s.onEvent(seen(1, at(300.0, 0.0)), 0)
        s.update(0)
        val g = s.glow(s.orb(1)!!)
        s.onNote(note(null), 0)
        s.onNote(note(999), 0)
        s.update(100)
        assertEquals(g, s.glow(s.orb(1)!!), 1e-6)
    }

    private fun texture(rootHz: Double, pad: Float) = org.meshtastic.aether.music.Texture(
        rootHz, 0.5f, 0.5f, 0.5f, 0.2f, 0.2f, 0.1f, mapOf(org.meshtastic.aether.music.Voice.Pad to pad),
    )

    // Regression: chords and pads had no visual; the background hue must follow the key slowly.
    // Root A (220 Hz) is pitch class 9 of 12 from C: sin(2 pi 9/12) = -1 so the target is -24 degrees.
    @Test
    fun `a texture shifts the hue slowly toward its key and the pad level eases in`() {
        val s = scene()
        s.onTexture(texture(220.0, 0.8f))
        s.update(0)
        s.update(sr)
        assertTrue(s.hueShift < 0.0 && s.hueShift > -2.0, "one second in: ${s.hueShift}")
        assertTrue(s.padLevel in 0.01..0.3)
        s.update(sr * 600)
        assertEquals(-24.0, s.hueShift, 0.5)
        assertEquals(0.8, s.padLevel, 0.01)
    }

    @Test
    fun `with pad level the field breathes and with none it holds still`() {
        val s = scene()
        s.onTexture(texture(261.6255653005986, 0.9f))
        s.update(sr * 600)
        val samples = (0 until 110).map { s.fieldBreath(it * 0.1) }
        assertTrue(samples.max() - samples.min() > 0.2, "range ${samples.max() - samples.min()}")
        val quiet = scene()
        assertTrue((0 until 110).all { quiet.fieldBreath(it * 0.1) == 1.0 })
    }

    // Regression: with no traffic the screen was a static gradient; orbs breathe on their own periods.
    @Test
    fun `orbs breathe and different nodes do not breathe in step`() {
        val s = scene()
        val a = ArrayList<Double>()
        var differs = false
        for (i in 0 until 200) {
            s.update(sr * i / 10)
            a += s.breath(0x1234)
            if (kotlin.math.abs(s.breath(0x1234) - s.breath(0x5678)) > 0.05) differs = true
        }
        assertTrue(a.max() - a.min() > 0.1)
        assertTrue(a.all { it in 0.84..1.0 })
        assertTrue(differs)
    }

    // Design: disconnected look: the field desaturates and the anchor pulses more slowly.
    @Test
    fun `unlinked desaturates the field and slows the anchor and relinking restores it`() {
        val s = scene()
        s.update(0)
        assertEquals(1.0, s.saturation, 1e-9)
        s.linked = false
        s.update(sr * 60)
        assertTrue(s.saturation < 0.25, "sat ${s.saturation}")
        s.linked = true
        s.update(sr * 120)
        assertTrue(s.saturation > 0.95)

        fun cycles(linked: Boolean): Int {
            val m = scene()
            m.linked = linked
            var peaks = 0
            var prev = m.anchorBreath
            var rising = false
            for (i in 1..320) {
                m.update(sr * i / 10)
                val b = m.anchorBreath
                if (rising && b < prev) peaks++
                rising = b > prev
                prev = b
            }
            return peaks
        }
        assertEquals(4, cycles(true), "32 s at an 8 s period")
        assertEquals(2, cycles(false), "32 s at a 16 s period")
    }

    // Regression: 200 nodes at one spot stacked into one white blob; orbs keep a minimum separation on a 16:9 screen.
    @Test
    fun `200 nodes at the same place spread to the minimum separation inside the ellipse`() {
        val s = scene()
        s.aspect = 16.0 / 9.0
        val here = at(50.0, 10.0)
        for (i in 1L..200L) s.onEvent(seen(0x100000L + i * 7919, here), 0)
        s.update(0)
        val list = s.orbs.toList()
        var worst = Double.MAX_VALUE
        for (i in list.indices) for (j in i + 1 until list.size) {
            worst = minOf(worst, hypot((list[i].x - list[j].x) * s.aspect, list[i].y - list[j].y))
        }
        assertTrue(worst >= SceneModel.MIN_SEPARATION - 1e-9, "closest pair $worst")
        assertTrue(list.all { hypot(it.x, it.y) <= 1.0 + 1e-9 })
        val screenW = list.maxOf { kotlin.math.abs(it.x) } * s.aspect
        val screenH = list.maxOf { kotlin.math.abs(it.y) }
        assertTrue(screenW / screenH in 0.7..1.4, "the blob is round on screen: $screenW by $screenH")
    }

    @Test
    fun `orbs that are already apart do not move when a later node collides`() {
        fun build(withCollider: Boolean): SceneModel {
            val m = scene()
            m.onEvent(seen(1, at(1000.0, 0.0)), 0)
            m.onEvent(seen(2, at(1000.0, 180.0)), 0)
            m.onEvent(seen(3, at(400.0, 90.0)), 0)
            if (withCollider) m.onEvent(seen(4, at(400.0, 90.0)), 0)
            m.update(0)
            return m
        }
        val alone = build(false)
        val crowded = build(true)
        for (n in 1L..3L) {
            assertEquals(alone.orb(n)!!.x, crowded.orb(n)!!.x, 1e-9)
            assertEquals(alone.orb(n)!!.y, crowded.orb(n)!!.y, 1e-9)
        }
        val a = crowded.orb(3)!!
        val b = crowded.orb(4)!!
        assertTrue(hypot(a.x - b.x, a.y - b.y) >= SceneModel.MIN_SEPARATION - 1e-9)
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
    fun `the night dim holds full light all day and the floor after midnight`() {
        for (m in 7 * 60..22 * 60) assertEquals(1.0, SceneModel.nightDim(m), 1e-9)
        for (m in 0..5 * 60) assertEquals(SceneModel.NIGHT_DIM_MIN, SceneModel.nightDim(m), 1e-9)
        assertTrue((0 until 1440).all { SceneModel.nightDim(it) in SceneModel.NIGHT_DIM_MIN..1.0 })
        assertTrue(SceneModel.nightDim(23 * 60) < 1.0 && SceneModel.nightDim(23 * 60) > SceneModel.NIGHT_DIM_MIN)
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
        val before = s.driftX(1)
        s.update(sr * 1000)
        assertTrue(before != s.driftX(1))
        s.onEvent(seen(2, at(9000.0, 200.0)), 0)
        s.update(10)
        assertEquals(0.0, s.anchor!!.x)
        assertEquals(0.0, s.anchor!!.y)
        assertTrue(s.orbs.none { it.node == me })
    }

    @Test
    fun `an orb glides to its new place when a node joins and a new orb starts in place`() {
        val s = SceneModel(sr.toInt())
        s.onEvent(seen(1, GeoPoint(45.0, -93.0)), 0)
        s.onEvent(seen(2, GeoPoint(45.01, -93.0)), 0)
        s.update(0)
        val first = s.orbs.first { it.node == 1L }
        assertEquals(first.x, first.shownX, 1e-12, "a placed orb starts at its place")
        s.onEvent(seen(3, GeoPoint(45.0, -92.9)), 0)
        s.update(sr / 60)
        val joined = s.orbs.first { it.node == 3L }
        assertEquals(joined.x, joined.shownX, 1e-12, "the new orb appears in place, not sliding in")
        val jump = hypot(first.x - first.shownX, first.y - first.shownY)
        assertTrue(jump > 0.05, "the relayout moved the target, jump $jump")
        s.update(sr / 60 * 2)
        val moved = hypot(first.x - first.shownX, first.y - first.shownY)
        assertTrue(moved < jump && moved > 0.5 * jump, "one frame closes only a sliver: $moved of $jump")
        for (f in 3..1200) s.update(sr / 60 * f)
        assertEquals(first.x, first.shownX, 1e-3, "it arrives")
    }


    @Test
    fun `a packet from a node kicks its orb off the place the layout gave it`() {
        fun offsetAfter(packet: Boolean): Double {
            val s = scene()
            s.onEvent(seen(1, at(500.0, 0.0)), 0)
            s.update(0)
            if (packet) s.onEvent(heard(1), 0)
            for (f in 1..30) s.update(sr / 60 * f)
            val o = s.orbs.first { it.node == 1L }
            return hypot(o.phOx, o.phOy)
        }
        assertTrue(offsetAfter(true) > offsetAfter(false) + 0.01, "the packet adds a visible kick")
    }
}
