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

/** Opacity of the halo sprite at [r] (0 at the centre, 1 at the rim): 1 at the centre, linear to 0 at the rim, so there is no visible edge. */
internal fun haloFalloff(r: Float): Float = (1f - r).coerceIn(0f, 1f)

/** A white radial falloff, drawn once; tinting it stands in for a radial gradient brush built per orb per frame. */
internal fun haloSprite(): ImageBitmap {
    val bmp = ImageBitmap(SPRITE_SIZE, SPRITE_SIZE)
    val canvas = Canvas(bmp)
    val paint = Paint()
    val half = SPRITE_SIZE / 2f
    for (y in 0 until SPRITE_SIZE) {
        for (x in 0 until SPRITE_SIZE) {
            val dx = (x + 0.5f - half) / half
            val dy = (y + 0.5f - half) / half
            val r = kotlin.math.sqrt(dx * dx + dy * dy)
            paint.color = Color(1f, 1f, 1f, haloFalloff(r))
            canvas.drawRect(Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f), paint)
        }
    }
    return bmp
}

/** Draws the [haloSprite] tinted. Tint filters are cached by the color at 5 bits a channel, so a steady frame allocates none. */
internal class HaloPainter(private val sprite: ImageBitmap) {
    private val paint = Paint()
    private val filters = arrayOfNulls<ColorFilter>(1 shl 15)

    private fun filterFor(c: Color): ColorFilter {
        val r = (c.red.coerceIn(0f, 1f) * 31f + 0.5f).toInt()
        val g = (c.green.coerceIn(0f, 1f) * 31f + 0.5f).toInt()
        val b = (c.blue.coerceIn(0f, 1f) * 31f + 0.5f).toInt()
        val key = (r shl 10) or (g shl 5) or b
        return filters[key] ?: ColorFilter.tint(Color(r / 31f, g / 31f, b / 31f), BlendMode.SrcIn).also { filters[key] = it }
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

/** Anchor ring stroke opacity: 0.25 to 0.4 with the breath, a ring that is felt more than read. */
internal fun anchorRingAlpha(breath: Float): Float = 0.25f + 0.15f * breath.coerceIn(0f, 1f)

/** Opacity of the soft accent fill inside the anchor ring. */
internal fun anchorFillAlpha(breath: Float): Float = 0.10f + 0.08f * breath.coerceIn(0f, 1f)
