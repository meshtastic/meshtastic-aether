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

/** How much of the glow the halo's centre takes. */
private const val HALO_GLOW_SHARE = 0.3

/** Opacity at the centre of the faint halo for a glow 0..1, never above half the cap. */
fun orbHaloAlpha(glow: Double): Float = (glow * HALO_GLOW_SHARE).toFloat().coerceIn(0f, ORB_ALPHA_CAP / 2)

private const val TIME_HUE_SWING_DEG = 12.0
private const val DAWN_MINUTE = 360
private const val MINUTES_PER_DAY = 1440.0

/** Degrees of background hue offset by the local time of day: 0 at 06:00 and 18:00, +12 at noon, -12 at midnight. */
fun timeHueOffset(minuteOfDay: Int): Double =
    TIME_HUE_SWING_DEG * sin(2 * PI * (minuteOfDay - DAWN_MINUTE) / MINUTES_PER_DAY)


/** [c] with its hue rotated by [hueDeg], saturation scaled by [saturation] and brightness by [value]. */
fun shiftColor(c: Color, hueDeg: Double, saturation: Double, value: Double): Color {
    val r = c.red.toDouble()
    val g = c.green.toDouble()
    val b = c.blue.toDouble()
    val hi = max(r, max(g, b))
    val lo = min(r, min(g, b))
    val d = hi - lo
    var h = when {
        d < GREY_EPSILON -> 0.0
        hi == r -> ((g - b) / d).mod(HUE_SECTORS)
        hi == g -> (b - r) / d + GREEN_SECTOR
        else -> (r - g) / d + BLUE_SECTOR
    } * SECTOR_DEG
    h = (h + hueDeg).mod(FULL_TURN_DEG)
    val s = (if (hi < GREY_EPSILON) 0.0 else d / hi) * saturation
    val v = hi * value
    val hp = h / SECTOR_DEG
    val chroma = v * s
    val x = chroma * (1 - abs(hp.mod(2.0) - 1))
    val m = v - chroma
    val (r1, g1, b1) = when (hp.toInt()) {
        RED_SECTOR -> Triple(chroma, x, 0.0)
        YELLOW_SECTOR -> Triple(x, chroma, 0.0)
        GREEN_SECTOR -> Triple(0.0, chroma, x)
        CYAN_SECTOR -> Triple(0.0, x, chroma)
        BLUE_SECTOR -> Triple(x, 0.0, chroma)
        else -> Triple(chroma, 0.0, x)
    }
    return Color(
        (r1 + m).toFloat().coerceIn(0f, 1f),
        (g1 + m).toFloat().coerceIn(0f, 1f),
        (b1 + m).toFloat().coerceIn(0f, 1f),
        c.alpha,
    )
}

// Rec. 709 luma weights.
private const val LUMA_RED = 0.2126f
private const val LUMA_GREEN = 0.7152f
private const val LUMA_BLUE = 0.0722f

/** [c] pulled toward its own grey by [saturation] (1 unchanged, 0 grey). */
fun desaturate(c: Color, saturation: Double): Color {
    val grey = LUMA_RED * c.red + LUMA_GREEN * c.green + LUMA_BLUE * c.blue
    val k = saturation.toFloat().coerceIn(0f, 1f)
    return Color(grey + (c.red - grey) * k, grey + (c.green - grey) * k, grey + (c.blue - grey) * k, c.alpha)
}

fun Color.toRgb(): Rgb =
    Rgb(round(red * CHANNEL_MAX).toInt(), round(green * CHANNEL_MAX).toInt(), round(blue * CHANNEL_MAX).toInt())
