package org.meshtastic.aether.visual

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/** No orb, core or halo, is ever drawn more opaque than this: a flash keeps its hue instead of burning to white. */
const val ORB_ALPHA_CAP = 0.7f

/** Opacity of the small crisp core for a glow 0..1; at the idle floor it stays at least 0.2. */
fun orbCoreAlpha(glow: Double): Float = glow.toFloat().coerceIn(0f, ORB_ALPHA_CAP)

/** Opacity at the centre of the faint halo for a glow 0..1, never above half the cap. */
fun orbHaloAlpha(glow: Double): Float = (glow * 0.3).toFloat().coerceIn(0f, ORB_ALPHA_CAP / 2)

/** Degrees of background hue offset by the local time of day: 0 at 06:00 and 18:00, +12 at noon, -12 at midnight. */
fun timeHueOffset(minuteOfDay: Int): Double = 12.0 * sin(2 * PI * (minuteOfDay - 360) / 1440.0)

/** [c] with its hue rotated by [hueDeg], saturation scaled by [saturation] and brightness by [value]. */
fun shiftColor(c: Color, hueDeg: Double, saturation: Double, value: Double): Color {
    val r = c.red.toDouble()
    val g = c.green.toDouble()
    val b = c.blue.toDouble()
    val hi = max(r, max(g, b))
    val lo = min(r, min(g, b))
    val d = hi - lo
    var h = when {
        d < 1e-12 -> 0.0
        hi == r -> ((g - b) / d).mod(6.0)
        hi == g -> (b - r) / d + 2
        else -> (r - g) / d + 4
    } * 60.0
    h = (h + hueDeg).mod(360.0)
    val s = (if (hi < 1e-12) 0.0 else d / hi) * saturation
    val v = hi * value
    val hp = h / 60.0
    val chroma = v * s
    val x = chroma * (1 - abs(hp.mod(2.0) - 1))
    val m = v - chroma
    val (r1, g1, b1) = when (hp.toInt()) {
        0 -> Triple(chroma, x, 0.0)
        1 -> Triple(x, chroma, 0.0)
        2 -> Triple(0.0, chroma, x)
        3 -> Triple(0.0, x, chroma)
        4 -> Triple(x, 0.0, chroma)
        else -> Triple(chroma, 0.0, x)
    }
    return Color((r1 + m).toFloat().coerceIn(0f, 1f), (g1 + m).toFloat().coerceIn(0f, 1f), (b1 + m).toFloat().coerceIn(0f, 1f), c.alpha)
}

/** [c] pulled toward its own grey by [saturation] (1 unchanged, 0 grey). */
fun desaturate(c: Color, saturation: Double): Color {
    val grey = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    val k = saturation.toFloat().coerceIn(0f, 1f)
    return Color(grey + (c.red - grey) * k, grey + (c.green - grey) * k, grey + (c.blue - grey) * k, c.alpha)
}

fun Color.toRgb(): Rgb = Rgb(round(red * 255).toInt(), round(green * 255).toInt(), round(blue * 255).toInt())
