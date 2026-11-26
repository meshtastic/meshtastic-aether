package org.meshtastic.aether.ui

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

/** Expected values are the numbers in the 1024 unit icon SVG (`branding/icon.svg`), divided by 1024. */
class AetherGlyphGeometryTest {
    private val unit = 1024f
    private val tolerance = 0.5f / 1024f

    // Regression: an orb that drifts off the icon's center or size no longer matches the launcher icon.
    @Test
    fun `the orb is a circle of radius 132 about the center of the 1024 icon`() {
        assertEquals(132f / unit, GlyphGeometry.ORB_RADIUS, tolerance)
        assertEquals(512f / unit, GlyphGeometry.CENTER, tolerance)
        assertEquals(336f / unit, GlyphGeometry.HALO_RADIUS, tolerance)
        assertEquals(470f / unit, GlyphGeometry.ORB_HIGHLIGHT_X, tolerance)
        assertEquals(466f / unit, GlyphGeometry.ORB_HIGHLIGHT_Y, tolerance)
    }

    // Regression: a ring tilted the other way or by another angle changes the glyph's silhouette.
    @Test
    fun `the ring is a 300 by 96 ellipse rotated minus 24 degrees`() {
        assertEquals(-24f, GlyphGeometry.RING_ROTATION_DEGREES, 0f)
        assertEquals(300f / unit, GlyphGeometry.RING_HALF_WIDTH, tolerance)
        assertEquals(96f / unit, GlyphGeometry.RING_HALF_HEIGHT, tolerance)
    }

    @Test
    fun `the ring strokes are 30 for the back arc 36 for the front arc and 52 for its shadow`() {
        assertEquals(30f / unit, GlyphGeometry.BACK_ARC_STROKE, tolerance)
        assertEquals(36f / unit, GlyphGeometry.FRONT_ARC_STROKE, tolerance)
        assertEquals(52f / unit, GlyphGeometry.FRONT_ARC_SHADOW_STROKE, tolerance)
    }

    // Regression: swapped sweep signs draw the back arc in front of the orb (SVG sweep 1 passes y -96, sweep 0 passes y +96).
    @Test
    fun `the back arc passes over the top and the front arc under the bottom`() {
        // Compose angles run clockwise from 3 o'clock with y down, so 270 is the top and 90 the bottom.
        assertEquals(270f, GlyphGeometry.BACK_ARC_START + GlyphGeometry.BACK_ARC_SWEEP / 2, 0f)
        assertEquals(90f, GlyphGeometry.FRONT_ARC_START + GlyphGeometry.FRONT_ARC_SWEEP / 2, 0f)
        assertEquals(180f, GlyphGeometry.BACK_ARC_START, 0f)
        assertEquals(180f, GlyphGeometry.FRONT_ARC_START, 0f)
        assertEquals(180f, GlyphGeometry.BACK_ARC_SWEEP, 0f)
        assertEquals(-180f, GlyphGeometry.FRONT_ARC_SWEEP, 0f)
    }

    // Regression: a node moved off the ring floats free of the orbit.
    @Test
    fun `the back nodes sit at the SVG coordinates in the ring frame`() {
        val (a, b) = GlyphGeometry.backNodes
        assertEquals(-196f / unit, a.x, tolerance)
        assertEquals(-73f / unit, a.y, tolerance)
        assertEquals(20f / unit, a.radius, tolerance)
        assertEquals(150f / unit, b.x, tolerance)
        assertEquals(-83f / unit, b.y, tolerance)
        assertEquals(14f / unit, b.radius, tolerance)
    }

    @Test
    fun `the bright node is radius 30 at 212 68 with a glow of radius 58`() {
        val n = GlyphGeometry.brightNode
        assertEquals(212f / unit, n.x, tolerance)
        assertEquals(68f / unit, n.y, tolerance)
        assertEquals(30f / unit, n.radius, tolerance)
        assertEquals(58f / unit, n.glowRadius, tolerance)
    }

    // The SVG rotates the ring frame by -24 degrees about the center (512 512): world = 512 + R(-24) * local, with the
    // world positions worked out by hand from cos 24 = 0.9135 and sin 24 = 0.4067.
    @Test
    fun `the rotated node positions land where the SVG puts them in the icon`() {
        fun world(x: Float, y: Float): Pair<Float, Float> {
            val r = GlyphGeometry.RING_ROTATION_DEGREES.toDouble() * PI / 180.0
            val wx = GlyphGeometry.CENTER + x * kotlin.math.cos(r).toFloat() - y * kotlin.math.sin(r).toFloat()
            val wy = GlyphGeometry.CENTER + x * kotlin.math.sin(r).toFloat() + y * kotlin.math.cos(r).toFloat()
            return wx to wy
        }
        val bright = GlyphGeometry.brightNode
        val (bx, by) = world(bright.x, bright.y)
        assertEquals(733.33f / unit, bx, 1f / unit)
        assertEquals(487.89f / unit, by, 1f / unit)
        val (ax, ay) = world(GlyphGeometry.backNodes[0].x, GlyphGeometry.backNodes[0].y)
        assertEquals(303.25f / unit, ax, 1f / unit)
        assertEquals(525.03f / unit, ay, 1f / unit)
    }
}
