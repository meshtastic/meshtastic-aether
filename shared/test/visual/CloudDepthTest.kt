package org.meshtastic.aether.visual

import org.meshtastic.aether.mesh.MeshEvent
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CloudDepthTest {
    @Test
    fun `depth stays within minus one and one and is the same every call`() {
        for (node in 1L..2000L) {
            val z = SceneModel.depthFor(node * 7919, 0.0)
            assertTrue(z >= -1.0 && z <= 1.0, "node $node gave $z")
            assertEquals(z, SceneModel.depthFor(node * 7919, 0.0))
        }
    }

    @Test
    fun `nodes spread on both sides of the plane rather than lying flat`() {
        var near = 0
        var far = 0
        for (node in 1L..1000L) {
            val z = SceneModel.depthFor(node * 104729, 0.0)
            if (z > 0.05) far++
            if (z < -0.05) near++
        }
        assertTrue(near > 300 && far > 300, "near=$near far=$far")
    }

    @Test
    fun `at the edge of the disc depth shrinks to the rim fraction so the cloud is an ellipsoid`() {
        for (node in 1L..500L) {
            assertTrue(abs(SceneModel.depthFor(node, 1.0)) <= SceneModel.ELLIPSOID_EDGE_DEPTH + 1e-12)
        }
        val inner = (1L..500L).maxOf { abs(SceneModel.depthFor(it, 0.0)) }
        assertTrue(inner > 0.6)
    }

    @Test
    fun `laid out orbs carry the depth for their node and radius`() {
        val s = SceneModel(48_000)
        for (n in 100L..130L) s.onEvent(MeshEvent.NodeSeen(n, null, null, true, 0), 0)
        s.update(0)
        for (o in s.orbs) {
            assertEquals(SceneModel.depthFor(o.node, o.x * o.x + o.y * o.y), o.z, 1e-12)
        }
        assertTrue(s.orbs.map { it.z }.toSet().size > 20)
    }

    @Test
    fun `drift in z is small and differs between nodes`() {
        val s = SceneModel(48_000)
        for (t in listOf(0L, 10L, 77L, 500L)) {
            s.update(t * 48_000)
            assertTrue(abs(s.driftZ(5)) <= 0.0121)
        }
        assertNotEquals(s.driftZ(5), s.driftZ(6))
    }
}
