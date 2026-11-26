package org.meshtastic.ambient.visual

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as GfxCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Rect
import org.meshtastic.ambient.mesh.PacketKind
import org.meshtastic.ambient.music.Rng
import org.meshtastic.ambient.theme.LocalAmbientPalette
import org.meshtastic.ambient.ui.AmbientEngine
import androidx.compose.ui.unit.dp
import kotlin.math.sin

private const val DITHER_SIZE = 96

/** Per-pixel noise tile; laid over the gradient at low alpha so projectors and OLEDs do not band. */
private fun ditherTile(): ImageBitmap {
    val bmp = ImageBitmap(DITHER_SIZE, DITHER_SIZE)
    val canvas = GfxCanvas(bmp)
    val rng = Rng(77)
    val paint = Paint()
    for (y in 0 until DITHER_SIZE) {
        for (x in 0 until DITHER_SIZE) {
            val v = rng.nextDouble().toFloat()
            paint.color = Color(v, v, v, 1f)
            canvas.drawRect(Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f), paint)
        }
    }
    return bmp
}

private fun Rgb.toColor(): Color = Color(r / 255f, g / 255f, b / 255f)

/** The full-screen ambient scene: gradient, haze, orbs and ripples, redrawn every frame on the audio clock. */
@Composable
fun AmbientScene(engine: AmbientEngine, modifier: Modifier = Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    val dither = remember { ditherTile() }
    val ditherBrush = remember(dither) { ShaderBrush(ImageShader(dither, TileMode.Repeated, TileMode.Repeated)) }
    val scene = engine.scene
    LaunchedEffect(engine) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            scene.update(engine.playbackFrame())
            frame++
        }
    }
    val palette = LocalAmbientPalette.current
    val bgRgb = remember(palette) { palette.backgroundTop.toRgb() }
    val colors = remember(palette) { HashMap<Long, Color>() }
    fun nodeColor(node: Long): Color = colors.getOrPut(node) { nodeRgb(node, bgRgb).toColor() }
    Canvas(modifier) {
        frame.let { }
        val t = engine.playbackSeconds()
        val minute = scene.minuteOfDay
        val dim = palette.dim(minute).toFloat()
        scene.aspect = size.width.toDouble() / size.height.coerceAtLeast(1f)
        val sat = scene.saturation
        val hueDeg = timeHueOffset(minute) + scene.hueShift
        val breathe = scene.fieldBreath(t)
        val tex = engine.texture
        val move = 0.4f + 1.2f * ((tex?.detune ?: 0.2f) + (tex?.noise ?: 0.1f))
        val sway = (sin(t * 0.021 * move) * 0.15).toFloat()
        val top = shiftColor(palette.backgroundTop, hueDeg, sat, dim * breathe)
        val bottom = shiftColor(palette.backgroundBottom, hueDeg + 20.0, sat, dim * breathe)
        drawRect(
            Brush.linearGradient(
                listOf(top, bottom),
                start = Offset(size.width * (0.5f + sway), 0f),
                end = Offset(size.width * (0.5f - sway), size.height),
            ),
        )
        drawRect(ditherBrush, alpha = 0.03f)

        val cx = size.width / 2
        val cy = size.height / 2
        val rx = size.width / 2 * 0.9f
        val ry = size.height / 2 * 0.9f
        val radius = minOf(rx, ry)
        val dp1 = 1.dp.toPx()
        if (scene.haze > 0.005) {
            drawCircle(
                Brush.radialGradient(
                    listOf(desaturate(palette.haze, sat).copy(alpha = (scene.haze * 0.12 * dim).toFloat()), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius * 1.4f,
                ),
                radius = radius * 1.4f,
                center = Offset(cx, cy),
            )
        }

        fun place(o: Orb): Offset = Offset(
            cx + ((o.x + scene.driftX(o.node, t)) * rx).toFloat(),
            cy + ((o.y + scene.driftY(o.node, t)) * ry).toFloat(),
        )

        fun at(node: Long?): Offset? = when {
            node == null -> scene.anchor?.let { Offset(cx + (scene.driftX(it.node, t) * rx).toFloat(), cy + (scene.driftY(it.node, t) * ry).toFloat()) } ?: Offset(cx, cy)
            node == scene.anchor?.node -> at(null)
            else -> scene.orb(node)?.let { place(it) }
        }

        for (e in scene.edges) {
            val a = at(e.a) ?: continue
            val b = at(e.b) ?: continue
            drawLine(palette.edge.copy(alpha = (0.12 * scene.edgeFade(e) * dim).toFloat()), a, b, strokeWidth = dp1)
        }
        val ring = scene.anchorBreath.toFloat()
        val ringAlpha = if (scene.linked) 0.45f + 0.25f * ring else 0.2f + 0.6f * ring
        drawCircle(
            palette.anchor.copy(alpha = ringAlpha * dim),
            radius = radius * ANCHOR_RING * (0.9f + 0.2f * ring),
            center = at(null) ?: Offset(cx, cy),
            style = Stroke(width = 2.dp.toPx()),
        )
        for (o in scene.orbs) {
            val g = scene.glow(o)
            val breath = scene.breath(o.node, t).toFloat()
            val c = place(o)
            val color = desaturate(nodeColor(o.node), sat)
            val haloR = radius * 0.04f * (1f + g.toFloat()) * breath
            drawCircle(
                Brush.radialGradient(
                    listOf(color.copy(alpha = orbHaloAlpha(g) * dim), Color.Transparent),
                    center = c,
                    radius = haloR * 3f,
                ),
                radius = haloR * 3f,
                center = c,
            )
            drawCircle(color.copy(alpha = orbCoreAlpha(g) * dim), radius = maxOf(1.5.dp.toPx(), radius * 0.008f * (0.9f + g.toFloat() * 0.6f)) * breath, center = c)
        }
        for (pu in scene.pulses) {
            val from = at(pu.fromNode) ?: continue
            val to = at(pu.toNode) ?: continue
            val p = scene.progress(pu).toFloat()
            val head = Offset(from.x + (to.x - from.x) * p, from.y + (to.y - from.y) * p)
            val tail = Offset(from.x + (to.x - from.x) * (p - 0.25f).coerceAtLeast(0f), from.y + (to.y - from.y) * (p - 0.25f).coerceAtLeast(0f))
            val color = desaturate(nodeColor(pu.colorNode), sat)
            val a = (if (p >= 1f) 0.4f else 0.85f) * dim
            drawLine(color.copy(alpha = a * 0.5f), tail, head, strokeWidth = 2.dp.toPx())
            drawCircle(color.copy(alpha = a), radius = radius * 0.012f, center = head)
        }
        for (rp in scene.ripples) {
            val o = scene.orb(rp.node) ?: continue
            val p = scene.progress(rp).toFloat()
            val fade = (1f - p) * (1f - p) * (if (rp.echo == 0) 0.8f else 0.35f) * dim
            val maxR = radius * (0.12f + 0.25f * rp.strength)
            val color = if (rp.kind == PacketKind.Other) palette.text else nodeColor(rp.node)
            val width = (if (rp.kind == PacketKind.Text) 2.5.dp.toPx() else 1.5.dp.toPx()) + 2.dp.toPx() * (1f - p)
            drawCircle(
                desaturate(color, sat).copy(alpha = fade),
                radius = maxR * p,
                center = place(o),
                style = Stroke(width = width),
            )
        }
    }
}

/** The anchor ring's radius as a fraction of the scene radius. */
private const val ANCHOR_RING = 0.035f
