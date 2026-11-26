package org.meshtastic.ambient.visual

import kotlin.math.roundToInt

data class Rgb(val r: Int, val g: Int, val b: Int) {
    /** Rec. 601 luma 0..1, the weights android's `nodeColorsFromNum` uses. */
    val luma: Double get() = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0

    /** This color moved [amount] (0..1) of the way to white. */
    fun towardWhite(amount: Double): Rgb =
        Rgb(mixUp(r, amount), mixUp(g, amount), mixUp(b, amount))

    private fun mixUp(channel: Int, amount: Double) = (channel + amount * (255 - channel)).roundToInt().coerceIn(0, 255)
}

/** Darkest luma a node's color may have on the scene's black background; darker colors are lifted toward white. */
const val MIN_NODE_LUMA = 0.45

/**
 * The node's own color as every Meshtastic client derives it: the low 24 bits of the node number read as RGB.
 * A color too dark to see on black keeps its hue and is lifted toward white until its luma reaches [MIN_NODE_LUMA].
 */
fun nodeRgb(node: Long): Rgb {
    val raw = Rgb(((node shr 16) and 0xFF).toInt(), ((node shr 8) and 0xFF).toInt(), (node and 0xFF).toInt())
    val luma = raw.luma
    if (luma >= MIN_NODE_LUMA) return raw
    return raw.towardWhite((MIN_NODE_LUMA - luma) / (1.0 - luma))
}
