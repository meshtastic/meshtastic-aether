package org.meshtastic.aether.visual

import org.meshtastic.aether.mesh.GeoPoint
import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.theme.PaletteChoice
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The axes are the ones WebXR, ARKit and ARCore share: x right, y up and -z ahead, with the floor at y 0. */
class SpatialFrameTest {
    private val sr = 48_000
    private val origin = GeoPoint(45.0, -93.0)
    private val self = 1L
    private val stride = SpatialFrame.SPRITE_STRIDE

    private fun scene() = SceneModel(sr).also { it.onEvent(MeshEvent.Self(self, null, origin, 0), 0) }

    private fun at(distanceMeters: Double, bearingDeg: Double): GeoPoint {
        val b = bearingDeg * PI / 180.0
        val dLat = distanceMeters * cos(b) / 111_320.0
        val dLon = distanceMeters * sin(b) / (111_320.0 * cos(origin.lat * PI / 180.0))
        return GeoPoint(origin.lat + dLat, origin.lon + dLon)
    }

    private fun seen(node: Long, p: GeoPoint?) = MeshEvent.NodeSeen(node, null, p, true, 0)

    private fun frame(scene: SceneModel, playbackFrame: Long = 0): SpatialFrame {
        scene.update(playbackFrame)
        return SpatialFrame().also { it.fill(scene, PaletteChoice.Standard.palette, playbackFrame.toDouble() / sr) }
    }

    @Test
    fun `a node due north is straight ahead and a node due east is to the right`() {
        val north = scene().also { it.onEvent(seen(2, at(1000.0, 0.0)), 0) }
        val east = scene().also { it.onEvent(seen(2, at(1000.0, 90.0)), 0) }
        val n = frame(north).sprites
        val e = frame(east).sprites
        assertTrue(n[2] < -1f && abs(n[0]) < 0.3f, "north at x ${n[0]} z ${n[2]}")
        assertTrue(e[0] > 1f && abs(e[2]) < 0.3f, "east at x ${e[0]} z ${e[2]}")
    }

    @Test
    fun `a node at our own position stays out of the viewers head`() {
        val s = scene().also { it.onEvent(seen(2, origin), 0) }
        val f = frame(s)
        assertEquals(2, f.spriteCount)
        val away = hypot(f.sprites[0], f.sprites[2])
        assertTrue(away >= 1.0f, "an orb $away m from the viewer")
    }

    @Test
    fun `a point pushed far below the plane stops above the floor`() {
        val out = FloatArray(3)
        SpatialLayout.place(0.5, 0.0, 3.0, 1.0, out, 0)
        assertEquals(SpatialLayout.MIN_HEIGHT_METERS.toFloat(), out[1])
    }

    @Test
    fun `the anchor ring lies on the floor around the viewer`() {
        val f = frame(scene())
        assertEquals(2 * SpatialLayout.RING_SEGMENTS, f.lineVertexCount)
        for (i in 0 until f.lineVertexCount) {
            val at = i * SpatialFrame.LINE_STRIDE
            val r = hypot(f.lines[at], f.lines[at + 2])
            assertEquals(0f, f.lines[at + 1], "vertex $i is off the floor")
            assertTrue(r >= 0.26f && r <= 0.34f, "vertex $i is $r m from the viewer")
        }
    }

    @Test
    fun `a route that ends at our node sends its pulse to the floor under the viewer`() {
        val s = scene().also { it.onEvent(seen(2, at(1000.0, 0.0)), 0) }
        s.update(0)
        s.onEvent(MeshEvent.Route(listOf(2L, self), 0), 0)
        val arrived = (SceneModel.HOP_SECONDS * sr).toLong() + 1
        val f = frame(s, arrived)
        assertEquals(3, f.spriteCount)
        val head = 2 * stride
        assertEquals(0f, f.sprites[head])
        assertEquals(0f, f.sprites[head + 1])
        assertEquals(0f, f.sprites[head + 2])
    }
}
