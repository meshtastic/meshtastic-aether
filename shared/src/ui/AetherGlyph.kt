package org.meshtastic.aether.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import org.meshtastic.aether.theme.Brand

/**
 * The glyph's geometry as fractions of its size, taken from the 1024 unit icon (`branding/icon.svg`). Ring values are
 * in the ring's own frame: origin at the orb center, x along the ring's long axis, before the [RING_ROTATION_DEGREES] turn.
 */
internal object GlyphGeometry {
    const val CENTER = 0.5f
    const val ORB_RADIUS = 132f / 1024f
    const val HALO_RADIUS = 336f / 1024f
    const val ORB_HIGHLIGHT_X = 470f / 1024f
    const val ORB_HIGHLIGHT_Y = 466f / 1024f
    const val ORB_GRADIENT_RADIUS = 190f / 1024f

    const val RING_ROTATION_DEGREES = -24f
    const val RING_HALF_WIDTH = 300f / 1024f
    const val RING_HALF_HEIGHT = 96f / 1024f
    const val BACK_ARC_STROKE = 30f / 1024f
    const val FRONT_ARC_STROKE = 36f / 1024f
    const val FRONT_ARC_SHADOW_STROKE = 52f / 1024f

    /** Compose arc angles: degrees clockwise from 3 o'clock. The back arc passes over the top of the orb, the front arc under it. */
    const val BACK_ARC_START = 180f
    const val BACK_ARC_SWEEP = 180f
    const val FRONT_ARC_START = 180f
    const val FRONT_ARC_SWEEP = -180f

    /** A node on the ring: [x] and [y] in the ring's frame, [radius] and [glowRadius] (0 for none), all fractions of the size. */
    data class Node(val x: Float, val y: Float, val radius: Float, val glowRadius: Float = 0f)

    /** The two small nodes behind the orb, then the bright node in front. */
    val backNodes = listOf(
        Node(-196f / 1024f, -73f / 1024f, 20f / 1024f),
        Node(150f / 1024f, -83f / 1024f, 14f / 1024f),
    )
    val brightNode = Node(212f / 1024f, 68f / 1024f, 30f / 1024f, glowRadius = 58f / 1024f)

    /** The M mark on the orb: its width and its center's lift above the orb's center, as fractions of the orb's radius. */
    const val MARK_WIDTH = 240f / 216f
    const val MARK_LIFT = 10f / 108f

    /** The mark's own box, the official artwork's 100 x 55 viewBox. */
    const val MARK_BOX_WIDTH = 100f
    const val MARK_BOX_HEIGHT = 55f
}

/**
 * The Meshtastic M mark as the official artwork draws it (`design/logo/svg/Mesh_Logo_*.svg`): its two paths with their
 * transforms applied, in its 100 x 55 box. Unaltered: the glyph only scales and places it.
 */
internal const val M_MARK_PATH =
    "M64.716,13.073L37.867,52.447L32.204,48.585L61.878,5.068C62.516,4.132 63.575,3.572 64.707,3.571" +
        "C65.839,3.570 66.899,4.128 67.538,5.062L97.281,48.512L91.625,52.384L64.716,13.073Z" +
        "M8.379,52.406L39.741,6.415L34.078,2.553L2.716,48.544L8.379,52.406Z"

private val markPath = PathParser().parsePathString(M_MARK_PATH).toPath()

private val GlyphSurface = Brand.Neutral950
private val Green300 = Color(0xFFB5F5CE)
private val Green400 = Color(0xFF8FF0B2)
private val Green700 = Color(0xFF2D8F52)
private val BackNodeColors = listOf(Green400.copy(alpha = 0.75f), Green300.copy(alpha = 0.6f))

/** The Aether icon's glyph without its background: a luminous orb, a tilted orbit ring with nodes, and a bright node. */
@Composable
fun AetherGlyph(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawGlyph(this.size.minDimension) }
}

private fun DrawScope.drawGlyph(side: Float) {
    val g = GlyphGeometry
    val c = Offset(side * g.CENTER, side * g.CENTER)
    drawCircle(
        Brush.radialGradient(
            0f to Brand.Accent.copy(alpha = 0.34f),
            0.55f to Brand.Accent.copy(alpha = 0.10f),
            1f to Brand.Accent.copy(alpha = 0f),
            center = c,
            radius = side * g.HALO_RADIUS,
        ),
        radius = side * g.HALO_RADIUS,
        center = c,
    )
    translate(c.x, c.y) {
        rotate(g.RING_ROTATION_DEGREES, Offset.Zero) {
            drawArcPair(side, front = false)
            g.backNodes.forEachIndexed { i, n -> drawNode(side, n, BackNodeColors[i]) }
        }
    }
    drawCircle(
        Brush.radialGradient(
            0f to Brand.Neutral100,
            0.3f to Green300,
            0.7f to Brand.Accent,
            1f to Green700,
            center = Offset(side * g.ORB_HIGHLIGHT_X, side * g.ORB_HIGHLIGHT_Y),
            radius = side * g.ORB_GRADIENT_RADIUS,
        ),
        radius = side * g.ORB_RADIUS,
        center = c,
    )
    drawMark(side, c)
    translate(c.x, c.y) {
        rotate(g.RING_ROTATION_DEGREES, Offset.Zero) {
            drawArcPair(side, front = true)
            val n = g.brightNode
            val p = Offset(n.x * side, n.y * side)
            drawCircle(
                Brush.radialGradient(
                    0f to Green300.copy(alpha = 0.9f),
                    1f to Green300.copy(alpha = 0f),
                    center = p,
                    radius = n.glowRadius * side,
                ),
                radius = n.glowRadius * side,
                center = p,
            )
            drawCircle(Brand.Neutral100, radius = n.radius * side, center = p)
        }
    }
}

/** The M mark on the orb in the official dark-on-green colorway, over the highlight and above the front ring. */
private fun DrawScope.drawMark(side: Float, center: Offset) {
    val g = GlyphGeometry
    val orb = side * g.ORB_RADIUS
    val scale = orb * g.MARK_WIDTH / g.MARK_BOX_WIDTH
    val left = center.x - g.MARK_BOX_WIDTH * scale / 2
    val top = center.y - orb * g.MARK_LIFT - g.MARK_BOX_HEIGHT * scale / 2
    withTransform({
        translate(left, top)
        scale(scale, scale, Offset.Zero)
    }) { drawPath(markPath, Brand.Primary) }
}

private fun DrawScope.drawNode(side: Float, n: GlyphGeometry.Node, color: Color) {
    drawCircle(color, radius = n.radius * side, center = Offset(n.x * side, n.y * side))
}

private fun DrawScope.drawArcPair(side: Float, front: Boolean) {
    val g = GlyphGeometry
    val topLeft = Offset(-g.RING_HALF_WIDTH * side, -g.RING_HALF_HEIGHT * side)
    val oval = Size(2 * g.RING_HALF_WIDTH * side, 2 * g.RING_HALF_HEIGHT * side)
    if (!front) {
        drawArc(
            Brand.Accent.copy(alpha = 0.38f),
            g.BACK_ARC_START,
            g.BACK_ARC_SWEEP,
            useCenter = false,
            topLeft = topLeft,
            size = oval,
            style = Stroke(g.BACK_ARC_STROKE * side, cap = StrokeCap.Round),
        )
        return
    }
    drawArc(
        GlyphSurface.copy(alpha = 0.4f),
        g.FRONT_ARC_START,
        g.FRONT_ARC_SWEEP,
        useCenter = false,
        topLeft = topLeft,
        size = oval,
        style = Stroke(g.FRONT_ARC_SHADOW_STROKE * side, cap = StrokeCap.Round),
    )
    drawArc(
        Brush.linearGradient(
            0f to Brand.Accent.copy(alpha = 0.55f),
            0.6f to Green400,
            1f to Green300,
            start = Offset(-g.RING_HALF_WIDTH * side, 0f),
            end = Offset(g.RING_HALF_WIDTH * side, 0f),
        ),
        g.FRONT_ARC_START,
        g.FRONT_ARC_SWEEP,
        useCenter = false,
        topLeft = topLeft,
        size = oval,
        style = Stroke(g.FRONT_ARC_STROKE * side, cap = StrokeCap.Round),
    )
}
