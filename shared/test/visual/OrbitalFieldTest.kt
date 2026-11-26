package org.meshtastic.aether.visual

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The expected values here come from the field's own design (its constants and Kepler exponent), so these are
 * characterization tests of that design, not checks against an outside source.
 */
class OrbitalFieldTest {
    private val dt = 1.0 / 60.0

    private fun orb(node: Long, x: Double, y: Double, rssi: Double? = null) = Orb(node, null, null).also {
        it.x = x
        it.y = y
        it.rssi = rssi
    }

    private fun step(orbs: List<Orb>, edges: List<Edge> = emptyList(), fade: Double = 1.0, dir: Int = 1, gravity: Double = 0.5, dtSeconds: Double = dt) =
        OrbitalField.step(dtSeconds, orbs, edges, { fade }, { n -> orbs.firstOrNull { it.node == n } }, null, dir, gravity)

    private fun run(orbs: List<Orb>, seconds: Double, edges: List<Edge> = emptyList(), fade: Double = 1.0, dir: Int = 1, gravity: Double = 0.5, each: () -> Unit = {}) {
        repeat((seconds / dt).toInt()) {
            step(orbs, edges, fade, dir, gravity)
            each()
        }
    }

    private fun offset(o: Orb) = hypot(o.phOx, o.phOy)

    @Test
    fun `an unconnected orb circles its place on a small ring and stays near it`() {
        val o = orb(1, 0.5, 0.0)
        var maxR = 0.0
        var sx = 0.0
        var sy = 0.0
        var n = 0
        run(listOf(o), 90.0) {
            maxR = maxOf(maxR, offset(o))
            sx += o.phOx
            sy += o.phOy
            n++
        }
        val ring = OrbitalField.ringRadius(OrbitalField.binding(null), 0.5)
        assertTrue(maxR > 0.5 * ring && maxR <= OrbitalField.MAX_OFFSET, "it moves on a ring of about $ring, saw $maxR")
        assertTrue(hypot(sx / n, sy / n) < 0.01, "the average offset over several turns is its place")
    }

    @Test
    fun `a fresh edge leans two nodes together and a faded one changes nothing`() {
        fun meanDistance(edge: Boolean, fade: Double): Double {
            val a = orb(10, -0.5, 0.0)
            val b = orb(20, 0.5, 0.0)
            val orbs = listOf(a, b)
            val edges = if (edge) listOf(Edge(10, 20, 0)) else emptyList()
            var sum = 0.0
            var n = 0
            run(orbs, 60.0, edges, fade, gravity = 1.0) {
                sum += hypot((b.x + b.phOx) - (a.x + a.phOx), (b.y + b.phOy) - (a.y + a.phOy))
                n++
            }
            return sum / n
        }
        val none = meanDistance(edge = false, fade = 1.0)
        val linked = meanDistance(edge = true, fade = 1.0)
        val faded = meanDistance(edge = true, fade = 0.0)
        assertTrue(none - linked > 0.2, "linked nodes sit closer: $linked against $none")
        assertEquals(none, faded, 1e-12)
    }

    @Test
    fun `higher gravity and a better heard node tighten the ring`() {
        fun maxOffset(gravity: Double, rssi: Double): Double {
            val o = orb(3, 0.4, 0.2, rssi)
            var m = 0.0
            run(listOf(o), 60.0, gravity = gravity) { m = maxOf(m, offset(o)) }
            return m
        }
        assertTrue(maxOffset(1.0, -40.0) < 0.5 * maxOffset(0.0, -40.0), "gravity tightens")
        assertTrue(maxOffset(1.0, -40.0) < 0.5 * maxOffset(1.0, -120.0), "a strong link tightens")
    }

    @Test
    fun `the direction sets which way an orb circles`() {
        fun angularMomentum(dir: Int): Double {
            val o = orb(5, 0.5, 0.0)
            var l = 0.0
            run(listOf(o), 40.0, dir = dir) { l += o.phOx * o.phVy - o.phOy * o.phVx }
            return l
        }
        assertTrue(angularMomentum(1) > 0.0)
        assertTrue(angularMomentum(-1) < 0.0)
    }

    @Test
    fun `a packet kick moves an orb and the spring brings it back`() {
        val kicked = orb(7, 0.5, 0.0)
        val still = orb(7, 0.5, 0.0)
        run(listOf(kicked), 5.0)
        run(listOf(still), 5.0)
        OrbitalField.kick(kicked, 1f)
        var apart = 0.0
        repeat(36) {
            step(listOf(kicked))
            step(listOf(still))
            apart = maxOf(apart, hypot(kicked.phOx - still.phOx, kicked.phOy - still.phOy))
        }
        assertTrue(apart > 0.03, "the kick shows within a second, saw $apart")
        run(listOf(kicked), 20.0)
        run(listOf(still), 20.0)
        assertTrue(hypot(kicked.phOx - still.phOx, kicked.phOy - still.phOy) < 0.005, "and it has settled")
    }

    @Test
    fun `odd time steps and many kicks never break the limit or make a number non-finite`() {
        val orbs = listOf(orb(1, 0.3, 0.1, -90.0), orb(2, -0.4, 0.2), orb(3, 0.0, -0.6, -50.0))
        val edges = listOf(Edge(1, 2, 0), Edge(2, 3, 0), Edge(1, 3, 0))
        for (d in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 100.0, 0.016)) {
            for (k in 0 until 200) {
                OrbitalField.kick(orbs[k % 3], 1f)
                step(orbs, edges, 1.0, 1, 1.0, d)
            }
        }
        for (o in orbs) {
            assertTrue(o.phOx.isFinite() && o.phOy.isFinite() && o.phVx.isFinite() && o.phVy.isFinite())
            assertTrue(offset(o) <= OrbitalField.MAX_OFFSET + 1e-12)
        }
    }

    @Test
    fun `the same inputs give the same offsets`() {
        fun final(): Pair<Double, Double> {
            val a = orb(1, 0.3, 0.1)
            val b = orb(2, -0.4, 0.2)
            run(listOf(a, b), 30.0, listOf(Edge(1, 2, 0)))
            return Pair(a.phOx, b.phOy)
        }
        assertEquals(final(), final())
    }

    @Test
    fun `an orb nearer the anchor turns faster by the cube root law and binding follows rssi`() {
        val inner = OrbitalField.angularRate(0.25)
        val outer = OrbitalField.angularRate(0.5)
        assertEquals(2.0.pow(1.5), inner / outer, 1e-9)
        assertTrue(OrbitalField.angularRate(1.0) < outer)
        assertEquals(0.0, OrbitalField.binding(-120.0), 1e-12)
        assertEquals(1.0, OrbitalField.binding(-40.0), 1e-12)
        assertEquals(0.0, OrbitalField.binding(-200.0), 1e-12)
        assertEquals(1.0, OrbitalField.binding(0.0), 1e-12)
        assertTrue(abs(OrbitalField.binding(null) - 0.4) < 1e-12)
    }
}
