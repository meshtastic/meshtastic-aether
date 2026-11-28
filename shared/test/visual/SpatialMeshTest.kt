package org.meshtastic.aether.visual

import org.meshtastic.aether.mesh.GeoPoint
import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.theme.PaletteChoice
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Geometry an engine without sprites or lines draws: fans and ribbons that face the eye. */
class SpatialMeshTest {
    private val sr = 48_000
    private val origin = GeoPoint(45.0, -93.0)

    private fun frameWithOneOrb(): SpatialFrame {
        val s = SceneModel(sr)
        s.onEvent(MeshEvent.Self(1, null, origin, 0), 0)
        s.onEvent(MeshEvent.NodeSeen(2, null, GeoPoint(origin.lat + 0.01, origin.lon), true, 0), 0)
        s.update(0)
        return SpatialFrame().also { it.fill(s, PaletteChoice.Standard.palette, 0.0) }
    }

    private fun alpha(color: Int) = (color ushr 24) and 0xFF

    private fun pos(m: SpatialMesh, v: Int) = Triple(m.positions[v * 3], m.positions[v * 3 + 1], m.positions[v * 3 + 2])

    @Test
    fun `a sprite fan lies across the line of sight so it faces the eye`() {
        val f = frameWithOneOrb()
        val m = SpatialMesh(8, 128)
        m.build(f, 0f, 1.4f, 0f)
        val (cx, cy, cz) = pos(m, 0)
        val dx = cx
        val dy = cy - 1.4f
        val dz = cz
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        for (k in 1..SpatialMesh.FAN_SEGMENTS) {
            val (x, y, z) = pos(m, k)
            val along = ((x - cx) * dx + (y - cy) * dy + (z - cz) * dz) / d
            assertTrue(abs(along) < 1e-4f, "rim point $k is $along m along the view")
        }
    }

    @Test
    fun `a halo fades from its centre to nothing at the rim and a core stays solid`() {
        val f = frameWithOneOrb()
        val m = SpatialMesh(8, 128)
        m.build(f, 0f, 1.4f, 0f)
        val halo = 0
        val core = SpatialMesh.SPRITE_VERTICES
        assertTrue(alpha(m.colors[halo]) > 0, "halo centre is invisible")
        for (k in 1..SpatialMesh.FAN_SEGMENTS) assertEquals(0, alpha(m.colors[halo + k]), "halo rim $k")
        for (k in 1..SpatialMesh.FAN_SEGMENTS) assertEquals(alpha(m.colors[core]), alpha(m.colors[core + k]), "core rim $k")
    }

    /** The first triangle's normal, (v1 - v0) x (v2 - v0), dotted with the way back to the eye: positive is front facing. */
    private fun facesEye(m: SpatialMesh, first: Int, ex: Float, ey: Float, ez: Float): Float {
        val (x0, y0, z0) = pos(m, m.indices[first])
        val (x1, y1, z1) = pos(m, m.indices[first + 1])
        val (x2, y2, z2) = pos(m, m.indices[first + 2])
        val ux = x1 - x0
        val uy = y1 - y0
        val uz = z1 - z0
        val vx = x2 - x0
        val vy = y2 - y0
        val vz = z2 - z0
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        return nx * (ex - x0) + ny * (ey - y0) + nz * (ez - z0)
    }

    @Test
    fun `fans and ribbons wind counter-clockwise toward the eye as glTF front faces do`() {
        val f = frameWithOneOrb()
        val m = SpatialMesh(8, 128)
        m.build(f, 0f, 1.4f, 0f)
        assertTrue(facesEye(m, 0, 0f, 1.4f, 0f) > 0f, "sprite fan faces away")
        val ribbon = 2 * SpatialMesh.SPRITE_INDICES
        assertTrue(facesEye(m, ribbon, 0f, 1.4f, 0f) > 0f, "ribbon faces away")
        assertTrue(facesEye(m, ribbon + 3, 0f, 1.4f, 0f) > 0f, "ribbon second triangle faces away")
    }

    @Test
    fun `a ribbon is as wide as the line width and crosses the view`() {
        val f = frameWithOneOrb()
        val m = SpatialMesh(0, 128)
        m.build(f, 0f, 1.4f, 0f)
        val (ax, ay, az) = pos(m, 0)
        val (bx, by, bz) = pos(m, 1)
        val w = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az))
        assertEquals(SpatialMesh.LINE_WIDTH_METERS, w, 1e-5f)
        val mx = (ax + bx) / 2
        val my = (ay + by) / 2 - 1.4f
        val mz = (az + bz) / 2
        val across = ((bx - ax) * mx + (by - ay) * my + (bz - az) * mz) / (w * sqrt(mx * mx + my * my + mz * mz))
        assertTrue(abs(across) < 1e-3f, "the width runs $across along the view")
    }

    @Test
    fun `a smaller frame after a larger one leaves no stale triangles past its own`() {
        val m = SpatialMesh(8, 128)
        m.build(frameWithOneOrb(), 0f, 1.4f, 0f)
        val bare = SceneModel(sr).also { it.onEvent(MeshEvent.Self(1, null, origin, 0), 0) }
        bare.update(0)
        m.build(SpatialFrame().also { it.fill(bare, PaletteChoice.Standard.palette, 0.0) }, 0f, 1.4f, 0f)
        assertTrue(m.indexCount < m.maxIndices)
        for (i in m.indexCount until m.maxIndices) assertEquals(0, m.indices[i], "index $i")
    }

    @Test
    fun `a sprite past the capacity is dropped rather than overrunning the buffers`() {
        val f = frameWithOneOrb()
        val m = SpatialMesh(1, 0)
        m.build(f, 0f, 1.4f, 0f)
        assertEquals(SpatialMesh.SPRITE_VERTICES, m.vertexCount)
        assertEquals(SpatialMesh.SPRITE_INDICES, m.indexCount)
    }

    @Test
    fun `colors are premultiplied and packed red first with alpha in the high byte`() {
        val c = SpatialMesh.rgba(1f, 0.2f, 0.6f, 0.5f)
        assertEquals(128, c and 0xFF)
        assertEquals(26, (c ushr 8) and 0xFF)
        assertEquals(77, (c ushr 16) and 0xFF)
        assertEquals(128, alpha(c))
    }

    @Test
    fun `a halo rim draws nothing however bright its color`() {
        val f = frameWithOneOrb()
        val m = SpatialMesh(8, 128)
        m.build(f, 0f, 1.4f, 0f)
        for (k in 1..SpatialMesh.FAN_SEGMENTS) assertEquals(0, m.colors[k], "halo rim $k")
    }
}
