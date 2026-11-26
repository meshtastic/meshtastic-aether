package org.meshtastic.ambient.visual

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class Rgb(val r: Int, val g: Int, val b: Int) {
    /** WCAG relative luminance 0..1. */
    val relativeLuminance: Double
        get() = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)

    /** This color moved [amount] (0..1) of the way to white. */
    fun towardWhite(amount: Double): Rgb =
        Rgb(mixUp(r, amount), mixUp(g, amount), mixUp(b, amount))

    private fun mixUp(channel: Int, amount: Double) = (channel + amount * (255 - channel)).roundToInt().coerceIn(0, 255)

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}

/** Hue 0..360, saturation and lightness 0..1. */
data class Hsl(val h: Double, val s: Double, val l: Double)

fun Rgb.toHsl(): Hsl {
    val rf = r / 255.0
    val gf = g / 255.0
    val bf = b / 255.0
    val hi = max(rf, max(gf, bf))
    val lo = min(rf, min(gf, bf))
    val l = (hi + lo) / 2
    val d = hi - lo
    if (d < 1e-12) return Hsl(0.0, 0.0, l)
    val s = d / (1 - abs(2 * l - 1))
    val h = when (hi) {
        rf -> ((gf - bf) / d).mod(6.0)
        gf -> (bf - rf) / d + 2
        else -> (rf - gf) / d + 4
    }
    return Hsl(h * 60.0, s.coerceIn(0.0, 1.0), l)
}

fun Hsl.toRgb(): Rgb {
    val c = (1 - abs(2 * l - 1)) * s
    val hp = h.mod(360.0) / 60.0
    val x = c * (1 - abs(hp.mod(2.0) - 1))
    val (r1, g1, b1) = when (hp.toInt()) {
        0 -> Triple(c, x, 0.0)
        1 -> Triple(x, c, 0.0)
        2 -> Triple(0.0, c, x)
        3 -> Triple(0.0, x, c)
        4 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    val m = l - c / 2
    return Rgb(((r1 + m) * 255).roundToInt().coerceIn(0, 255), ((g1 + m) * 255).roundToInt().coerceIn(0, 255), ((b1 + m) * 255).roundToInt().coerceIn(0, 255))
}

/** WCAG 2.x contrast ratio 1..21 between two colors. */
fun contrastRatio(a: Rgb, b: Rgb): Double {
    val la = a.relativeLuminance
    val lb = b.relativeLuminance
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/** Contrast the design standards require of a node color against its background. */
const val MIN_NODE_CONTRAST = 4.5

/** A little over [MIN_NODE_CONTRAST] so the 8-bit rounding of the lifted color never lands under it. */
private const val LIFT_TARGET = 4.6

/** The brand Neutral 950, the darkest background any palette uses; the default for [nodeRgb]. */
val NEUTRAL_950 = Rgb(15, 16, 23)

/**
 * The node's own color as every Meshtastic client derives it: the low 24 bits of the node number read as RGB.
 * A color that would not reach [MIN_NODE_CONTRAST] against [background] keeps its hue and HSL saturation and has its
 * lightness raised until it does.
 */
fun nodeRgb(node: Long, background: Rgb = NEUTRAL_950): Rgb {
    val raw = Rgb(((node shr 16) and 0xFF).toInt(), ((node shr 8) and 0xFF).toInt(), (node and 0xFF).toInt())
    if (contrastRatio(raw, background) >= LIFT_TARGET) return raw
    val hsl = raw.toHsl()
    var lo = hsl.l
    var hi = 1.0
    var best = Hsl(hsl.h, hsl.s, 1.0).toRgb()
    repeat(24) {
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
