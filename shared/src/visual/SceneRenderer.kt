package org.meshtastic.aether.visual

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.meshtastic.aether.mesh.PacketKind
import org.meshtastic.aether.music.Rng
import org.meshtastic.aether.theme.AmbientPalette
import org.meshtastic.aether.ui.AmbientEngine
import kotlin.math.sqrt

private const val DITHER_SIZE = 96
private const val DITHER_SEED = 77L

/**
 * The dither's strength, baked into the tile: a per-draw alpha on the shader brush costs a full-screen offscreen pass
 * on a weak GPU. White at two of 255 lifts a dark pixel by up to two code values and black leaves it, enough to break
 * a band and no more.
 */
private const val DITHER_ALPHA_CODE = 2
private const val ALPHA_SHIFT = 24
private const val WHITE_RGB = 0xFFFFFF
private const val WHITE_SHARE = 0.5

/** The background gradient sways around the centre; [SceneModel.sway] integrates its phase. */
private const val GRADIENT_CENTER = 0.5f
private const val BOTTOM_HUE_OFFSET_DEG = 20.0

private const val MIN_HAZE = 0.005
private const val HAZE_SPREAD = 1.4f

/** The anchor ring's radius as a fraction of the scene radius, breathing from 0.9 to 1.1 of it. */
private const val ANCHOR_RING = 0.035f
private const val ANCHOR_RING_REST = 0.9f
private const val ANCHOR_RING_BREATH = 0.2f
private const val ANCHOR_FILL_SPREAD = 1.6f

// Orb sizes as fractions of the scene radius; the halo sprite fades to nothing over three halo radii.
private const val HALO_RADIUS = 0.04f
private const val HALO_SPRITE_SPREAD = 3f
private const val CORE_RADIUS = 0.008f
private const val CORE_REST = 0.9f
private const val CORE_GLOW_GROWTH = 0.6f

/** A pulse draws a trail this much of its path long, fainter once it arrives. */
private const val PULSE_TAIL = 0.25f
private const val PULSE_ALPHA = 0.85f
private const val ARRIVED_PULSE_ALPHA = 0.4f

private const val RIPPLE_ALPHA = 0.8f
private const val ECHO_RIPPLE_ALPHA = 0.35f
private const val RIPPLE_REACH = 0.12f
private const val RIPPLE_REACH_PER_STRENGTH = 0.25f

/**
 * Each extra ring of a long text is this fraction of the radius smaller than the one outside it, and this much
 * fainter.
 */
private const val RING_INSET = 0.22f
private const val RING_FADE = 0.3f

/** A dashed ring is this many dashes with a gap after each. */
private const val DASH_COUNT = 16
private const val FULL_CIRCLE_DEG = 360f

private val COOL_TINT = Color(0.55f, 0.70f, 1.0f)

/** Draws one frame of the scene. Holds every buffer a frame needs, so a steady frame allocates nothing new. */
internal class SceneRenderer {
    private val ditherBrush = ShaderBrush(ImageShader(ditherTile(), TileMode.Repeated, TileMode.Repeated))
    private val screen = ScreenProjector()
    private val pa = Projected()
    private val pb = Projected()
    private val halos = HaloPainter(haloSprite())
    private val colors = SceneColors()

    // This frame's light: the palette's dim for the hour, the core's gentler share of it and the saturation.
    private var dim = 1f
    private var coreDim = 1f
    private var sat = 1.0
    private var dp1 = 1f

    fun draw(scope: DrawScope, engine: AmbientEngine, scene: SceneModel, palette: AmbientPalette) {
        colors.use(palette)
        val t = engine.playbackSeconds()
        val minute = scene.minuteOfDay
        dim = palette.dim(minute).toFloat()
        coreDim = sqrt(dim)
        with(scope) {
            scene.aspect = size.width.toDouble() / size.height.coerceAtLeast(1f)
            sat = scene.saturation
            drawBackground(scene, palette, t)
            if (!palette.trueBlack) drawRect(ditherBrush)
            screen.begin(scene, size.width, size.height)
            dp1 = 1.dp.toPx()
            drawHaze(scene, palette)
            screen.projectOrbs()
            drawEdges(scene, palette)
            drawAnchor(scene, palette)
            drawOrbs(scene)
            drawPulses(scene)
            drawRipples(scene, palette)
        }
    }

    private fun DrawScope.drawBackground(scene: SceneModel, palette: AmbientPalette, t: Double) {
        val hueDeg = timeHueOffset(scene.minuteOfDay) + scene.hueShift
        val breathe = scene.fieldBreath(t)
        val sway = scene.sway.toFloat()
        val top = shiftColor(palette.backgroundTop, hueDeg, sat, dim * breathe)
        val bottom = shiftColor(palette.backgroundBottom, hueDeg + BOTTOM_HUE_OFFSET_DEG, sat, dim * breathe)
        drawRect(
            Brush.linearGradient(
                listOf(top, bottom),
                start = Offset(size.width * (GRADIENT_CENTER + sway), 0f),
                end = Offset(size.width * (GRADIENT_CENTER - sway), size.height),
            ),
        )
    }

    private fun DrawScope.drawHaze(scene: SceneModel, palette: AmbientPalette) {
        if (scene.haze > MIN_HAZE) {
            val center = Offset(screen.cx, screen.cy)
            val hazeRadius = screen.radius * HAZE_SPREAD
            val hazeColor = desaturate(palette.haze, sat).copy(alpha = (scene.haze * 0.12 * dim).toFloat())
            drawCircle(
                Brush.radialGradient(listOf(hazeColor, Color.Transparent), center = center, radius = hazeRadius),
                radius = hazeRadius,
                center = center,
            )
        }
    }

    private fun DrawScope.drawEdges(scene: SceneModel, palette: AmbientPalette) {
        for (e in scene.edges) {
            if (!screen.resolve(e.a, pa) || !screen.resolve(e.b, pb)) continue
            drawLine(
                palette.edge.copy(alpha = (0.12 * scene.edgeFade(e) * dim).toFloat()),
                Offset(pa.x.toFloat(), pa.y.toFloat()),
                Offset(pb.x.toFloat(), pb.y.toFloat()),
                strokeWidth = dp1 * ((pa.scale + pb.scale) / 2).toFloat(),
            )
        }
    }

    private fun DrawScope.drawAnchor(scene: SceneModel, palette: AmbientPalette) {
        val ring = scene.anchorBreath.toFloat()
        screen.resolve(null, pa)
        val ringCenter = Offset(pa.x.toFloat(), pa.y.toFloat())
        val breathing = ANCHOR_RING_REST + ANCHOR_RING_BREATH * ring
        val ringRadius = screen.radius * ANCHOR_RING * breathing * pa.scale.toFloat()
        val color = scene.anchor?.let { colors.node(it.node) } ?: palette.anchor
        halos.draw(this, ringCenter, ringRadius * ANCHOR_FILL_SPREAD, color, anchorFillAlpha(ring) * coreDim)
        drawCircle(
            color.copy(alpha = anchorRingAlpha(ring) * coreDim),
            radius = ringRadius,
            center = ringCenter,
            style = Stroke(width = dp1 * pa.scale.toFloat()),
        )
    }

    private fun DrawScope.drawOrbs(scene: SceneModel) {
        val radius = screen.radius
        for (idx in 0 until screen.orbCount) {
            val o = screen.orbAt(idx) ?: continue
            val g = scene.glow(o)
            val breath = scene.breath(o.node).toFloat()
            val lift = (o.brightness * o.flicker).toFloat()
            val s = o.projScale.toFloat()
            val depthT = CloudMotion.depthT(o.projDepth)
            val c = Offset(o.projX.toFloat(), o.projY.toFloat())
            val coolMix = CloudMotion.coolMix(depthT)
            val color = cool(desaturate(colors.node(o.node), sat), coolMix)
            val haloColor = if (o.haloHueDegrees == 0) {
                color
            } else {
                cool(desaturate(colors.shifted(o.node, o.haloHueDegrees), sat), coolMix)
            }
            val haloR = radius * HALO_RADIUS * (1f + g.toFloat()) * breath * s * o.haloScale.toFloat()
            val haloAlpha = (orbHaloAlpha(g) * CloudMotion.brightness(depthT) * lift).coerceAtMost(ORB_ALPHA_CAP / 2)
            halos.draw(this, c, haloR * HALO_SPRITE_SPREAD, haloColor, haloAlpha * dim)
            val glowR = radius * CORE_RADIUS * (CORE_REST + g.toFloat() * CORE_GLOW_GROWTH)
            val coreR = maxOf(1.5.dp.toPx(), glowR) * breath * s * o.coreSize.toFloat()
            val coreA = (CloudMotion.shadeAlpha(orbCoreAlpha(g), depthT) * o.coreAlpha.toFloat() * lift)
                .coerceAtMost(ORB_ALPHA_CAP)
            drawCircle(color.copy(alpha = coreA * coreDim), radius = coreR, center = c)
        }
    }

    private fun DrawScope.drawPulses(scene: SceneModel) {
        for (pu in scene.pulses) {
            if (!screen.resolve(pu.fromNode, pa) || !screen.resolve(pu.toNode, pb)) continue
            val p = scene.progress(pu).toFloat()
            val tp = (p - PULSE_TAIL).coerceAtLeast(0f)
            val head = Offset((pa.x + (pb.x - pa.x) * p).toFloat(), (pa.y + (pb.y - pa.y) * p).toFloat())
            val tail = Offset((pa.x + (pb.x - pa.x) * tp).toFloat(), (pa.y + (pb.y - pa.y) * tp).toFloat())
            val headScale = (pa.scale + (pb.scale - pa.scale) * p).toFloat()
            val color = desaturate(colors.node(pu.colorNode), sat)
            val a = (if (p >= 1f) ARRIVED_PULSE_ALPHA else PULSE_ALPHA) * dim
            drawLine(color.copy(alpha = a * 0.5f), tail, head, strokeWidth = 2.dp.toPx() * headScale)
            drawCircle(color.copy(alpha = a), radius = screen.radius * 0.012f * headScale, center = head)
        }
    }

    private fun DrawScope.drawRipples(scene: SceneModel, palette: AmbientPalette) {
        for (rp in scene.ripples) {
            val o = scene.orb(rp.node) ?: continue
            val s = o.projScale.toFloat()
            val p = scene.progress(rp).toFloat()
            val fade = (1f - p) * (1f - p) * (if (rp.echo == 0) RIPPLE_ALPHA else ECHO_RIPPLE_ALPHA) * dim
            val maxR = screen.radius * (RIPPLE_REACH + RIPPLE_REACH_PER_STRENGTH * rp.strength) * s
            val base = if (rp.kind == PacketKind.Other) palette.text else colors.shifted(rp.node, rp.hueDegrees)
            val color = cool(desaturate(base, sat), rp.coolMix)
            val width = RippleLook.rippleWidthDp(rp.kind, rp.widthTrim, p.toDouble()).toFloat() * dp1 * s
            val center = Offset(o.projX.toFloat(), o.projY.toFloat())
            for (ri in 0 until rp.ringCount) {
                val ringR = maxR * p * (1f - RING_INSET * ri)
                val ringColor = color.copy(alpha = fade * (1f - RING_FADE * ri))
                if (rp.style == RingStyle.Dashed) {
                    drawDashedRing(ringColor, center, ringR, width)
                } else {
                    drawCircle(ringColor, radius = ringR, center = center, style = Stroke(width = width))
                }
            }
        }
    }

    private fun DrawScope.drawDashedRing(color: Color, center: Offset, radius: Float, width: Float) {
        val sweep = FULL_CIRCLE_DEG / DASH_COUNT
        for (d in 0 until DASH_COUNT) {
            drawArc(
                color,
                startAngle = d * sweep,
                sweepAngle = sweep / 2,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = width),
            )
        }
    }
}

/** The dither tile's pixels as ARGB: half white and half black at a faint alpha, so the gradient does not band. */
internal fun ditherPixels(): IntArray {
    val rng = Rng(DITHER_SEED)
    val alpha = DITHER_ALPHA_CODE shl ALPHA_SHIFT
    return IntArray(DITHER_SIZE * DITHER_SIZE) {
        val white = rng.nextDouble() < WHITE_SHARE
        alpha or if (white) WHITE_RGB else 0
    }
}

/**
 * The dither tile; laid over the gradient so projectors and LCDs do not band. A [AmbientPalette.trueBlack] look
 * draws none: any white dither lights a black pixel, and black dither cannot move one so dark.
 */
private fun ditherTile(): ImageBitmap {
    val bmp = ImageBitmap(DITHER_SIZE, DITHER_SIZE)
    val canvas = Canvas(bmp)
    val paint = Paint()
    ditherPixels().forEachIndexed { i, argb ->
        val x = i % DITHER_SIZE
        val y = i / DITHER_SIZE
        paint.color = Color(argb)
        canvas.drawRect(Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f), paint)
    }
    return bmp
}

/** [c] pulled toward a cool blue by [mix] 0..1. */
private fun cool(c: Color, mix: Float): Color =
    if (mix <= 0f) {
        c
    } else {
        Color(
            c.red + (COOL_TINT.red - c.red) * mix,
            c.green + (COOL_TINT.green - c.green) * mix,
            c.blue + (COOL_TINT.blue - c.blue) * mix,
            c.alpha,
        )
    }
