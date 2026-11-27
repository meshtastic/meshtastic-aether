package org.meshtastic.aether.visual

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// WCAG 2.x relative luminance: the sRGB transfer function (IEC 61966-2-1) undone, then the Rec. 709 weights.
private const val LUMINANCE_RED = 0.2126
private const val LUMINANCE_GREEN = 0.7152
private const val LUMINANCE_BLUE = 0.0722
private const val SRGB_LINEAR_KNEE = 0.04045
private const val SRGB_LINEAR_SLOPE = 12.92
private const val SRGB_OFFSET = 0.055
private const val SRGB_SCALE = 1.055
private const val SRGB_GAMMA = 2.4

/** The flare WCAG adds to both luminances of a contrast ratio. */
private const val CONTRAST_FLARE = 0.05

data class Rgb(val r: Int, val g: Int, val b: Int) {
    /** WCAG relative luminance 0..1. */
    val relativeLuminance: Double
        get() = LUMINANCE_RED * linear(r) + LUMINANCE_GREEN * linear(g) + LUMINANCE_BLUE * linear(b)

    /** This color moved [amount] (0..1) of the way to white. */
    fun towardWhite(amount: Double): Rgb =
        Rgb(mixUp(r, amount), mixUp(g, amount), mixUp(b, amount))

    private fun mixUp(channel: Int, amount: Double) =
        (channel + amount * (CHANNEL_MAX - channel)).roundToInt().coerceIn(0, CHANNEL_MAX)

    private fun linear(channel: Int): Double {
        val c = channel.toDouble() / CHANNEL_MAX
        return if (c <= SRGB_LINEAR_KNEE) c / SRGB_LINEAR_SLOPE else ((c + SRGB_OFFSET) / SRGB_SCALE).pow(SRGB_GAMMA)
    }
}

/** Hue 0..360, saturation and lightness 0..1. */
data class Hsl(val h: Double, val s: Double, val l: Double)

fun Rgb.toHsl(): Hsl {
    val rf = r.toDouble() / CHANNEL_MAX
    val gf = g.toDouble() / CHANNEL_MAX
    val bf = b.toDouble() / CHANNEL_MAX
    val hi = max(rf, max(gf, bf))
    val lo = min(rf, min(gf, bf))
    val l = (hi + lo) / 2
    val d = hi - lo
    if (d < GREY_EPSILON) return Hsl(0.0, 0.0, l)
    val s = d / (1 - abs(2 * l - 1))
    val h = when (hi) {
        rf -> ((gf - bf) / d).mod(HUE_SECTORS)
        gf -> (bf - rf) / d + GREEN_SECTOR
        else -> (rf - gf) / d + BLUE_SECTOR
    }
    return Hsl(h * SECTOR_DEG, s.coerceIn(0.0, 1.0), l)
}

fun Hsl.toRgb(): Rgb {
    val c = (1 - abs(2 * l - 1)) * s
    val hp = h.mod(FULL_TURN_DEG) / SECTOR_DEG
    val x = c * (1 - abs(hp.mod(2.0) - 1))
    val (r1, g1, b1) = when (hp.toInt()) {
        RED_SECTOR -> Triple(c, x, 0.0)
        YELLOW_SECTOR -> Triple(x, c, 0.0)
        GREEN_SECTOR -> Triple(0.0, c, x)
        CYAN_SECTOR -> Triple(0.0, x, c)
        BLUE_SECTOR -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    val m = l - c / 2
    return Rgb(toChannel(r1 + m), toChannel(g1 + m), toChannel(b1 + m))
}

private fun toChannel(unit: Double): Int = (unit * CHANNEL_MAX).roundToInt().coerceIn(0, CHANNEL_MAX)

/** WCAG 2.x contrast ratio 1..21 between two colors. */
fun contrastRatio(a: Rgb, b: Rgb): Double {
    val la = a.relativeLuminance
    val lb = b.relativeLuminance
    return (max(la, lb) + CONTRAST_FLARE) / (min(la, lb) + CONTRAST_FLARE)
}

/** Contrast the design standards require of a node color against its background. */
const val MIN_NODE_CONTRAST = 4.5

/** A little over [MIN_NODE_CONTRAST] so the 8-bit rounding of the lifted color never lands under it. */
private const val LIFT_TARGET = 4.6

/** Halvings of the lightness range; 24 resolve it far past 8-bit precision. */
private const val LIFT_STEPS = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

/** The brand Neutral 950, the darkest background any palette uses; the default for [nodeRgb]. */
val NEUTRAL_950 = Rgb(15, 16, 23)

/**
 * The node's own color as every Meshtastic client derives it: the low 24 bits of the node number read as RGB.
 * A color that would not reach [MIN_NODE_CONTRAST] against [background] keeps its hue and HSL saturation and has its
 * lightness raised until it does.
 */
fun nodeRgb(node: Long, background: Rgb = NEUTRAL_950): Rgb {
    val raw = Rgb(channelAt(node, RED_SHIFT), channelAt(node, GREEN_SHIFT), channelAt(node, 0))
    if (contrastRatio(raw, background) >= LIFT_TARGET) return raw
    val hsl = raw.toHsl()
    var lo = hsl.l
    var hi = 1.0
    var best = Hsl(hsl.h, hsl.s, 1.0).toRgb()
    repeat(LIFT_STEPS) {
        val mid = (lo + hi) / 2
        val candidate = Hsl(hsl.h, hsl.s, mid).toRgb()
        if (contrastRatio(candidate, background) >= LIFT_TARGET) {
            best = candidate
            hi = mid
        } else {
            lo = mid
        }
    }
    return best
}

/** The 8-bit channel of [node] starting [shift] bits up. */
private fun channelAt(node: Long, shift: Int): Int = (node shr shift).toUByte().toInt()
