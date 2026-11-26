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
import org.meshtastic.ambient.ui.AmbientEngine
import kotlin.math.PI
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

private fun kindHue(kind: PacketKind): Float = when (kind) {
    PacketKind.Text -> 40f
    PacketKind.Position -> 150f
    PacketKind.Telemetry -> 190f
    PacketKind.NodeInfo -> 270f
    PacketKind.Routing -> 320f
    PacketKind.Traceroute -> 340f
    PacketKind.NeighborInfo -> 210f
    PacketKind.Other, PacketKind.Encrypted -> 0f
}

private fun nodeColor(node: Long): Color = nodeRgb(node).let { Color(it.r / 255f, it.g / 255f, it.b / 255f) }

/** A node's ripples wear its color, Text rings pushed toward white; encrypted and other traffic stays neutral. */
private fun rippleColor(node: Long, kind: PacketKind): Color = when (kind) {
    PacketKind.Encrypted, PacketKind.Other -> Color.hsv(kindHue(kind), if (kind == PacketKind.Other) 0f else 0.55f, 1f)
    PacketKind.Text -> nodeRgb(node).towardWhite(0.5).let { Color(it.r / 255f, it.g / 255f, it.b / 255f) }
    else -> nodeColor(node)
}

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
    Canvas(modifier) {
        frame.let { }
        val t = engine.playbackSeconds()
        val dim = scene.dim.toFloat()
        val minute = scene.minuteOfDay
        val hue = (225.0 + 45.0 * sin(2 * PI * (minute - 360) / 1440.0)).toFloat()
        val tex = engine.texture
        val move = 0.4f + 1.2f * ((tex?.detune ?: 0.2f) + (tex?.noise ?: 0.1f))
        val sway = (sin(t * 0.021 * move) * 0.15).toFloat()
        val top = Color.hsv(hue, 0.55f, 0.16f * dim)
        val bottom = Color.hsv((hue + 40f) % 360f, 0.6f, 0.07f * dim)
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
        val radius = minOf(size.width, size.height) / 2 * 0.9f
        if (scene.haze > 0.005) {
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = (scene.haze * 0.12 * dim).toFloat()), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius * 1.4f,
                ),
                radius = radius * 1.4f,
                center = Offset(cx, cy),
            )
        }
        drawCircle(Color.White.copy(alpha = 0.25f * dim), radius = 3f, center = Offset(cx, cy))

        fun place(o: Orb): Offset = Offset(
            cx + ((o.x + scene.driftX(o.node, t)) * radius).toFloat(),
            cy + ((o.y + scene.driftY(o.node, t)) * radius).toFloat(),
        )

        for (o in scene.orbs) {
            val g = scene.glow(o).toFloat()
            val r = radius * 0.035f * (0.7f + g)
            val c = place(o)
            val color = nodeColor(o.node)
            drawCircle(
                Brush.radialGradient(
                    listOf(color.copy(alpha = (g * dim).coerceIn(0f, 1f)), Color.Transparent),
                    center = c,
                    radius = r * 3f,
                ),
                radius = r * 3f,
                center = c,
            )
        }
        for (rp in scene.ripples) {
            val o = scene.orb(rp.node) ?: continue
            val p = scene.progress(rp).toFloat()
            val fade = (1f - p) * (1f - p) * (if (rp.echo == 0) 0.8f else 0.35f) * dim
            val maxR = radius * (0.12f + 0.25f * rp.strength)
            drawCircle(
                rippleColor(rp.node, rp.kind).copy(alpha = fade),
                radius = maxR * p,
                center = place(o),
                style = Stroke(width = 2f + 3f * (1f - p)),
            )
        }
    }
}
