package org.meshtastic.aether.visual

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
import org.meshtastic.aether.mesh.PacketKind
import org.meshtastic.aether.music.Rng
import org.meshtastic.aether.theme.LocalAmbientPalette
import org.meshtastic.aether.ui.AmbientEngine
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import kotlin.math.sqrt

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
    val projection = remember { Projection() }
    val tmp = remember { Projected() }
    val pa = remember { Projected() }
    val pb = remember { Projected() }
    val buffers = remember { OrbBuffers() }
    val halos = remember { HaloPainter(haloSprite()) }
    LaunchedEffect(engine) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { scene.frameStats.record(it) }
            scene.update(engine.playbackFrame())
            frame++
        }
    }
    val palette = LocalAmbientPalette.current
    val bgRgb = remember(palette) { palette.backgroundTop.toRgb() }
    val colors = remember(palette) { HashMap<Long, Color>() }
    fun nodeColor(node: Long): Color = colors.getOrPut(node) { nodeRgb(node, bgRgb).toColor() }
    val shifted = remember(palette) { HashMap<Long, Color>() }

    /** [nodeColor] with its hue turned by [deg] whole degrees (-32..31); cached so the frame does not allocate. */
    fun shiftedColor(node: Long, deg: Int): Color =
        if (deg == 0) nodeColor(node) else shifted.getOrPut((node shl 6) or (deg + 32).toLong()) { shiftColor(nodeColor(node), deg.toDouble(), 1.0, 1.0) }
    Canvas(modifier) {
        frame.let { }
        val t = engine.playbackSeconds()
        val minute = scene.minuteOfDay
        val dim = palette.dim(minute).toFloat()
        val coreDim = sqrt(dim)
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

        val k = scene.cloudBreath
        projection.update(scene.rotation, CloudMotion.CAMERA_DISTANCE)
        val zReach = CloudMotion.Z_EXTENT * k

        /** Projects a layout point into [out] as screen pixels, with the perspective scale and depth. */
        fun project(x: Double, y: Double, z: Double, out: Projected) {
            projection.project(x * k, y * k, z * zReach, out)
            out.x = cx + out.x * rx
            out.y = cy + out.y * ry
        }

        val n = scene.orbs.size
        buffers.fit(n)
        var oi = 0
        for (o in scene.orbs) {
            project(o.shownX + scene.driftX(o.node), o.shownY + scene.driftY(o.node), o.shownZ + o.zOffset + scene.driftZ(o.node), tmp)
            o.projX = tmp.x
            o.projY = tmp.y
            o.projScale = tmp.scale
            o.projDepth = tmp.depth
            buffers.orbs[oi] = o
            buffers.depth[oi] = tmp.depth
            oi++
        }
        DepthSort.backToFront(buffers.depth, buffers.order, n)

        /** Fills [out] with the screen position, scale and depth of [node] (null is the anchor); false when unknown. */
        fun resolve(node: Long?, out: Projected): Boolean {
            if (node == null || node == scene.anchor?.node) {
                val a = scene.anchor
                if (a == null) {
                    out.x = cx.toDouble()
                    out.y = cy.toDouble()
                    out.scale = 1.0
                    out.depth = 0.0
                } else {
                    project(scene.driftX(a.node), scene.driftY(a.node), 0.0, out)
                }
                return true
            }
            val o = scene.orb(node) ?: return false
            out.x = o.projX
            out.y = o.projY
            out.scale = o.projScale
            out.depth = o.projDepth
            return true
        }

        for (e in scene.edges) {
            if (!resolve(e.a, pa) || !resolve(e.b, pb)) continue
            drawLine(
                palette.edge.copy(alpha = (0.12 * scene.edgeFade(e) * dim).toFloat()),
                Offset(pa.x.toFloat(), pa.y.toFloat()),
                Offset(pb.x.toFloat(), pb.y.toFloat()),
                strokeWidth = dp1 * ((pa.scale + pb.scale) / 2).toFloat(),
            )
        }
        val ring = scene.anchorBreath.toFloat()
        resolve(null, pa)
        val ringCenter = Offset(pa.x.toFloat(), pa.y.toFloat())
        val ringRadius = radius * ANCHOR_RING * (0.9f + 0.2f * ring) * pa.scale.toFloat()
        halos.draw(this, ringCenter, ringRadius * 1.6f, palette.anchor, anchorFillAlpha(ring) * coreDim)
        drawCircle(
            palette.anchor.copy(alpha = anchorRingAlpha(ring) * coreDim),
            radius = ringRadius,
            center = ringCenter,
            style = Stroke(width = dp1 * pa.scale.toFloat()),
        )
        for (idx in 0 until n) {
            val o = buffers.orbs[buffers.order[idx]] ?: continue
            val g = scene.glow(o)
            val breath = scene.breath(o.node).toFloat()
            val lift = (o.brightness * o.flicker).toFloat()
            val s = o.projScale.toFloat()
            val depthT = CloudMotion.depthT(o.projDepth)
            val c = Offset(o.projX.toFloat(), o.projY.toFloat())
            val color = cool(desaturate(nodeColor(o.node), sat), CloudMotion.coolMix(depthT))
            val haloColor = if (o.haloHueDegrees == 0) color else cool(desaturate(shiftedColor(o.node, o.haloHueDegrees), sat), CloudMotion.coolMix(depthT))
            val haloR = radius * 0.04f * (1f + g.toFloat()) * breath * s * o.haloScale.toFloat()
            val haloAlpha = (orbHaloAlpha(g) * CloudMotion.brightness(depthT) * lift).coerceAtMost(ORB_ALPHA_CAP / 2)
            halos.draw(this, c, haloR * 3f, haloColor, haloAlpha * dim)
            val coreR = maxOf(1.5.dp.toPx(), radius * 0.008f * (0.9f + g.toFloat() * 0.6f)) * breath * s * o.coreSize.toFloat()
            val coreA = (CloudMotion.shadeAlpha(orbCoreAlpha(g), depthT) * o.coreAlpha.toFloat() * lift).coerceAtMost(ORB_ALPHA_CAP)
            drawCircle(color.copy(alpha = coreA * coreDim), radius = coreR, center = c)
        }
        for (pu in scene.pulses) {
            if (!resolve(pu.fromNode, pa) || !resolve(pu.toNode, pb)) continue
            val p = scene.progress(pu).toFloat()
            val tp = (p - 0.25f).coerceAtLeast(0f)
            val head = Offset((pa.x + (pb.x - pa.x) * p).toFloat(), (pa.y + (pb.y - pa.y) * p).toFloat())
            val tail = Offset((pa.x + (pb.x - pa.x) * tp).toFloat(), (pa.y + (pb.y - pa.y) * tp).toFloat())
            val headScale = (pa.scale + (pb.scale - pa.scale) * p).toFloat()
            val color = desaturate(nodeColor(pu.colorNode), sat)
            val a = (if (p >= 1f) 0.4f else 0.85f) * dim
            drawLine(color.copy(alpha = a * 0.5f), tail, head, strokeWidth = 2.dp.toPx() * headScale)
            drawCircle(color.copy(alpha = a), radius = radius * 0.012f * headScale, center = head)
        }
        for (rp in scene.ripples) {
            val o = scene.orb(rp.node) ?: continue
            val s = o.projScale.toFloat()
            val p = scene.progress(rp).toFloat()
            val fade = (1f - p) * (1f - p) * (if (rp.echo == 0) 0.8f else 0.35f) * dim
            val maxR = radius * (0.12f + 0.25f * rp.strength) * s
            val base = if (rp.kind == PacketKind.Other) palette.text else shiftedColor(rp.node, rp.hueDegrees)
            val color = cool(desaturate(base, sat), rp.coolMix)
            val width = EventLook.rippleWidthDp(rp.kind, rp.widthTrim, p.toDouble()).toFloat() * dp1 * s
            val center = Offset(o.projX.toFloat(), o.projY.toFloat())
            for (ri in 0 until rp.ringCount) {
                val ringR = maxR * p * (1f - RING_INSET * ri)
                val ringColor = color.copy(alpha = fade * (1f - RING_FADE * ri))
                if (rp.style == RingStyle.Dashed) {
                    val sweep = 360f / DASH_COUNT
                    for (d in 0 until DASH_COUNT) {
                        drawArc(
                            ringColor,
                            startAngle = d * sweep,
                            sweepAngle = sweep / 2,
                            useCenter = false,
                            topLeft = Offset(center.x - ringR, center.y - ringR),
                            size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2),
                            style = Stroke(width = width),
                        )
                    }
                } else {
                    drawCircle(ringColor, radius = ringR, center = center, style = Stroke(width = width))
                }
            }
        }
    }
}

/** Each extra ring of a long text is this fraction of the radius smaller than the one outside it, and this much fainter. */
private const val RING_INSET = 0.22f
private const val RING_FADE = 0.3f

/** A dashed ring is this many dashes with a gap after each. */
private const val DASH_COUNT = 16

/** Reused per-frame arrays for the orb draw order; they grow only when the field does. */
private class OrbBuffers {
    var orbs = arrayOfNulls<Orb>(0)
    var depth = DoubleArray(0)
    var order = IntArray(0)
    private var size = -1

    /** Makes room for [n] orbs and, when the count changed, restarts the order as the identity. */
    fun fit(n: Int) {
        if (n > orbs.size) {
            val cap = maxOf(n, orbs.size * 2, 64)
            orbs = arrayOfNulls(cap)
            depth = DoubleArray(cap)
            order = IntArray(cap)
            size = -1
        }
        if (n != size) {
            for (i in 0 until n) order[i] = i
            size = n
        }
    }
}

private val COOL_TINT = Color(0.55f, 0.70f, 1.0f)

/** [c] pulled toward a cool blue by [mix] 0..1. */
private fun cool(c: Color, mix: Float): Color =
    if (mix <= 0f) c else Color(
        c.red + (COOL_TINT.red - c.red) * mix,
        c.green + (COOL_TINT.green - c.green) * mix,
        c.blue + (COOL_TINT.blue - c.blue) * mix,
        c.alpha,
    )

/** The anchor ring's radius as a fraction of the scene radius. */
private const val ANCHOR_RING = 0.035f
