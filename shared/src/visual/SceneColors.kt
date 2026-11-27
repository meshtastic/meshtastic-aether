package org.meshtastic.aether.visual

import androidx.compose.ui.graphics.Color
import org.meshtastic.aether.theme.AmbientPalette

/** Hue turns of -32..31 whole degrees fit in the low 6 bits of a cache key, offset to 0..63. */
private const val HUE_KEY_BITS = 6
private const val HUE_KEY_OFFSET = 32

/** Node colors against the palette's background, cached so a frame does not allocate; a new palette starts over. */
internal class SceneColors {
    private var palette: AmbientPalette? = null
    private var background = NEUTRAL_950
    private val colors = HashMap<Long, Color>()
    private val shifted = HashMap<Long, Color>()

    fun use(palette: AmbientPalette) {
        if (palette == this.palette) return
        this.palette = palette
        background = palette.backgroundTop.toRgb()
        colors.clear()
        shifted.clear()
    }

    fun node(node: Long): Color = colors.getOrPut(node) { nodeRgb(node, background).toColor() }

    /** [node]'s color with its hue turned by [deg] whole degrees (-32..31). */
    fun shifted(node: Long, deg: Int): Color = if (deg == 0) {
        node(node)
    } else {
        shifted.getOrPut((node shl HUE_KEY_BITS) or (deg + HUE_KEY_OFFSET).toLong()) {
            shiftColor(node(node), deg.toDouble(), 1.0, 1.0)
        }
    }
}

private fun Rgb.toColor(): Color =
    Color(r.toFloat() / CHANNEL_MAX, g.toFloat() / CHANNEL_MAX, b.toFloat() / CHANNEL_MAX)
