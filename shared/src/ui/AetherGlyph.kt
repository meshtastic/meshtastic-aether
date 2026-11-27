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
 * The Meshtastic M mark as the official artwork draws it (`design/logo/svg/Mesh_Logo_*.svg`): its two paths with their
 * transforms applied, in its 100 x 55 box. Unaltered: the glyph only scales and places it.
 */
internal const val M_MARK_PATH =
    "M64.716,13.073L37.867,52.447L32.204,48.585L61.878,5.068C62.516,4.132 63.575,3.572 64.707,3.571" +
        "C65.839,3.570 66.899,4.128 67.538,5.062L97.281,48.512L91.625,52.384L64.716,13.073Z" +
        "M8.379,52.406L39.741,6.415L34.078,2.553L2.716,48.544L8.379,52.406Z"

private val markPath = PathParser().parsePathString(M_MARK_PATH).toPath()

private val GlyphSurface = Brand.Neutral950
private val BackNodeColors = listOf(Brand.Green400.copy(alpha = 0.75f), Brand.Green300.copy(alpha = 0.6f))

/**
 * The Aether icon's glyph without its background: a luminous orb, a tilted orbit ring with nodes, and a bright node.
 */
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
            g.HALO_MID_STOP to Brand.Accent.copy(alpha = 0.10f),
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
            g.ORB_LIGHT_STOP to Brand.Green300,
            g.ORB_ACCENT_STOP to Brand.Accent,
            1f to Brand.Green700,
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
                    0f to Brand.Green300.copy(alpha = 0.9f),
                    1f to Brand.Green300.copy(alpha = 0f),
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
            g.RING_MID_STOP to Brand.Green400,
            1f to Brand.Green300,
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
