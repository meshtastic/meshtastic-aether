package org.meshtastic.aether.visual

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

private const val SPRITE_SIZE = 64

/** A pixel is sampled at its centre. */
private const val PIXEL_CENTER = 0.5f

/**
 * Opacity of the halo sprite at [r] (0 at the centre, 1 at the rim): 1 at the centre, linear to 0 at the rim, so there
 * is no visible edge.
 */
internal fun haloFalloff(r: Float): Float = (1f - r).coerceIn(0f, 1f)

/** A white radial falloff, drawn once; tinting it stands in for a radial gradient brush built per orb per frame. */
internal fun haloSprite(): ImageBitmap {
    val bmp = ImageBitmap(SPRITE_SIZE, SPRITE_SIZE)
    val canvas = Canvas(bmp)
    val paint = Paint()
    val half = SPRITE_SIZE / 2f
    for (y in 0 until SPRITE_SIZE) {
        for (x in 0 until SPRITE_SIZE) {
            val dx = (x + PIXEL_CENTER - half) / half
            val dy = (y + PIXEL_CENTER - half) / half
            val r = kotlin.math.sqrt(dx * dx + dy * dy)
            paint.color = Color(1f, 1f, 1f, haloFalloff(r))
            canvas.drawRect(Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f), paint)
        }
    }
    return bmp
}

// Tint filters are cached by the color at 5 bits a channel, so a steady frame allocates none.
private const val TINT_BITS = 5
private const val TINT_LEVELS = 1 shl (3 * TINT_BITS)
private const val TINT_MAX = 31f
private const val ROUND_HALF_UP = 0.5f

/** Draws the [haloSprite] tinted, with one cached tint filter per 15-bit color. */
internal class HaloPainter(private val sprite: ImageBitmap) {
    private val paint = Paint()
    private val filters = arrayOfNulls<ColorFilter>(TINT_LEVELS)

    private fun level(channel: Float): Int = (channel.coerceIn(0f, 1f) * TINT_MAX + ROUND_HALF_UP).toInt()

    private fun filterFor(c: Color): ColorFilter {
        val r = level(c.red)
        val g = level(c.green)
        val b = level(c.blue)
        val key = (r shl (2 * TINT_BITS)) or (g shl TINT_BITS) or b
        return filters[key] ?: ColorFilter.tint(Color(r / TINT_MAX, g / TINT_MAX, b / TINT_MAX), BlendMode.SrcIn)
            .also { filters[key] = it }
    }

    /** Fills a disc of [radius] around [center] with [color] fading to nothing at the rim, at [alpha]. */
    fun draw(scope: DrawScope, center: Offset, radius: Float, color: Color, alpha: Float) {
        if (alpha <= 0f || radius <= 0f) return
        paint.colorFilter = filterFor(color)
        paint.alpha = alpha.coerceIn(0f, 1f)
        val scale = radius / (SPRITE_SIZE / 2f)
        scope.drawIntoCanvas { canvas ->
            canvas.save()
            canvas.translate(center.x, center.y)
            canvas.scale(scale, scale)
            canvas.drawImage(sprite, Offset(-SPRITE_SIZE / 2f, -SPRITE_SIZE / 2f), paint)
            canvas.restore()
        }
    }
}

private const val ANCHOR_RING_ALPHA = 0.25f
private const val ANCHOR_RING_BREATH_ALPHA = 0.15f
private const val ANCHOR_FILL_ALPHA = 0.10f
private const val ANCHOR_FILL_BREATH_ALPHA = 0.08f

/** Anchor ring stroke opacity: 0.25 to 0.4 with the breath, a ring that is felt more than read. */
internal fun anchorRingAlpha(breath: Float): Float =
    ANCHOR_RING_ALPHA + ANCHOR_RING_BREATH_ALPHA * breath.coerceIn(0f, 1f)

/** Opacity of the soft accent fill inside the anchor ring. */
internal fun anchorFillAlpha(breath: Float): Float =
    ANCHOR_FILL_ALPHA + ANCHOR_FILL_BREATH_ALPHA * breath.coerceIn(0f, 1f)
