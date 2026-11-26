package org.meshtastic.aether.visual

import org.meshtastic.aether.mood.RotationState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectionTest {
    private val d = 3.0
    private val eps = 1e-9

    private fun project(x: Double, y: Double, z: Double, yaw: Double, pitch: Double): Projected {
        val p = Projection()
        p.update(yaw, pitch, d)
        return Projected().also { p.project(x, y, z, it) }
    }

    @Test
    fun `the anchor at the origin projects to the screen centre for any yaw and pitch`() {
        for (yaw in listOf(0.0, 0.7, PI / 2, 2.9, 5.5)) {
            for (pitch in listOf(-0.14, 0.0, 0.14, 1.0)) {
                val o = project(0.0, 0.0, 0.0, yaw, pitch)
                assertEquals(0.0, o.x, eps)
                assertEquals(0.0, o.y, eps)
                assertEquals(1.0, o.scale, eps)
            }
        }
    }

    @Test
    fun `a quarter turn of yaw sends a point on plus x onto the camera axis nearer by its radius`() {
        val o = project(1.0, 0.0, 0.0, PI / 2, 0.0)
        assertEquals(0.0, o.x, eps)
        assertEquals(-1.0, o.depth, eps)
    }

    @Test
    fun `a quarter turn of yaw sends plus z to the right and away from nothing`() {
        val o = project(0.0, 0.0, 1.0, PI / 2, 0.0)
        assertEquals(0.0, o.depth, eps)
        assertEquals(1.0, o.x, eps)
    }

    @Test
    fun `a quarter turn of pitch lifts a far point to the top and sends a low point away`() {
        val far = project(0.0, 0.0, 1.0, 0.0, PI / 2)
        assertEquals(-1.0, far.y, eps)
        assertEquals(0.0, far.depth, eps)
        val low = project(0.0, 1.0, 0.0, 0.0, PI / 2)
        assertEquals(1.0, low.depth, eps)
    }

    @Test
    fun `perspective scale is strictly decreasing with depth`() {
        var last = Double.MAX_VALUE
        var z = -1.0
        while (z <= 1.0) {
            val s = project(0.2, 0.1, z, 0.0, 0.0).scale
            assertTrue(s < last, "scale $s at z=$z not below $last")
            assertEquals(d / (d + z), s, eps)
            last = s
            z += 0.25
        }
    }

    @Test
    fun `without rotation x and y are the input scaled by perspective only`() {
        val o = project(0.5, -0.25, 0.4, 0.0, 0.0)
        assertEquals(0.5 * o.scale, o.x, eps)
        assertEquals(-0.25 * o.scale, o.y, eps)
    }

    @Test
    fun `two orbs at the same x and y with different z never share a screen position after a yaw`() {
        for (yaw in listOf(0.1, 0.5, 1.0, 2.0, 3.0, 4.5)) {
            for (pitch in listOf(0.0, 0.14, -0.14)) {
                val a = project(0.4, 0.3, -0.5, yaw, pitch)
                val b = project(0.4, 0.3, 0.5, yaw, pitch)
                assertTrue(hypot(a.x - b.x, a.y - b.y) > 1e-3, "share a position at yaw=$yaw pitch=$pitch")
            }
        }
    }

    @Test
    fun `the sort puts the farthest orb first and the nearest last`() {
        val depth = doubleArrayOf(0.3, -0.5, 0.9, 0.1, -0.2)
        val order = IntArray(5) { it }
        DepthSort.backToFront(depth, order, 5)
        assertEquals(listOf(2, 0, 3, 4, 1), order.toList())
    }

    @Test
    fun `the sort follows depth changes between frames from the previous order`() {
        val depth = doubleArrayOf(0.3, -0.5, 0.9, 0.1)
        val order = IntArray(4) { it }
        DepthSort.backToFront(depth, order, 4)
        depth[1] = 1.5
        DepthSort.backToFront(depth, order, 4)
        assertEquals(listOf(1, 2, 0, 3), order.toList())
    }

    @Test
    fun `the sort leaves entries past n alone and keeps a permutation`() {
        val depth = doubleArrayOf(0.1, 0.2, 0.3, 9.0)
        val order = IntArray(4) { it }
        DepthSort.backToFront(depth, order, 3)
        assertEquals(listOf(2, 1, 0, 3), order.toList())
    }

    @Test
    fun `the rotation overload turns by the yaw and leans by tilt plus wobble`() {
        val a = Projection()
        val b = Projection()
        val yaw = 0.7
        a.update(RotationState(yaw, 0.01, 0.1, 0.05, 1), d)
        b.update(yaw, 0.15, d)
        val pa = Projected()
        val pb = Projected()
        a.project(0.3, -0.2, 0.5, pa)
        b.project(0.3, -0.2, 0.5, pb)
        assertEquals(pb.x, pa.x, eps)
        assertEquals(pb.y, pa.y, eps)
        assertEquals(pb.depth, pa.depth, eps)
    }

    @Test
    fun `the cloud breathes three percent over sixty seconds`() {
        assertEquals(1.03, CloudMotion.breathing(PI / 2), eps)
        assertEquals(0.97, CloudMotion.breathing(3 * PI / 2), eps)
        assertEquals(1.0, CloudMotion.breathing(0.0), eps)
    }

    @Test
    fun `brightness falls from near to far and the far tint grows only past the middle`() {
        assertTrue(CloudMotion.brightness(-1.0) > CloudMotion.brightness(0.0))
        assertTrue(CloudMotion.brightness(0.0) > CloudMotion.brightness(1.0))
        assertEquals(0f, CloudMotion.coolMix(-1.0))
        assertEquals(0f, CloudMotion.coolMix(0.0))
        assertTrue(CloudMotion.coolMix(1.0) > CloudMotion.coolMix(0.5))
    }

    @Test
    fun `a near orb never exceeds the alpha cap and a far orb at the idle floor stays at the floor`() {
        assertEquals(ORB_ALPHA_CAP, CloudMotion.shadeAlpha(ORB_ALPHA_CAP, -1.0))
        assertTrue(CloudMotion.shadeAlpha(0.5f, -1.0) <= ORB_ALPHA_CAP)
        assertTrue(CloudMotion.shadeAlpha(0.2f, 1.0) >= 0.2f)
        assertTrue(CloudMotion.shadeAlpha(0.6f, 1.0) < 0.6f)
    }

    @Test
    fun `depth normalisation clamps to the nearest and farthest`() {
        assertEquals(1.0, CloudMotion.depthT(50.0), eps)
        assertEquals(-1.0, CloudMotion.depthT(-50.0), eps)
        assertTrue(abs(CloudMotion.depthT(0.0)) < eps)
    }
}
