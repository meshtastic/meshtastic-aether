package org.meshtastic.aether.visual

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A [SpatialFrame] as triangles that face the viewer, for an engine with no sprites, no lines and no additive blend
 * (Android XR's SceneCore). A sprite is a fan: a crisp disc is opaque to its rim, a halo fades linearly from its centre
 * to nothing at the rim, which is [haloFalloff]. A line is a ribbon [LINE_WIDTH_METERS] wide. Colors are premultiplied
 * by their alpha, which SceneCore's blend expects: a straight color at alpha 0 still draws at full strength there.
 * Unused capacity is left as zero-area triangles, so a fixed-size buffer can be uploaded whole.
 */
class SpatialMesh(spriteCapacity: Int, lineCapacity: Int) {
    val maxVertices = spriteCapacity * SPRITE_VERTICES + lineCapacity * LINE_VERTICES
    val maxIndices = spriteCapacity * SPRITE_INDICES + lineCapacity * LINE_INDICES

    /** x, y, z per vertex, in the frame's metres. */
    val positions = FloatArray(maxVertices * XYZ)

    /** One RGBA8888 color per vertex, red in the lowest byte, the order of a little-endian UBYTE4_NORM attribute. */
    val colors = IntArray(maxVertices)

    val indices = IntArray(maxIndices)

    /** How many of [indices] the last [build] filled; the rest repeat vertex 0. */
    var indexCount = 0
        private set

    var vertexCount = 0
        private set

    private val spriteCapacity = spriteCapacity
    private val lineCapacity = lineCapacity
    private val right = FloatArray(XYZ)
    private val up = FloatArray(XYZ)

    /**
     * Fills the buffers from [frame] as seen by a viewer whose eyes are at [eyeX], [eyeY], [eyeZ] in the frame's
     * coordinates. Sprites and lines past the capacity are dropped, the frame's last ones first.
     */
    fun build(frame: SpatialFrame, eyeX: Float, eyeY: Float, eyeZ: Float) {
        vertexCount = 0
        indexCount = 0
        val s = frame.sprites
        for (i in 0 until minOf(frame.spriteCount, spriteCapacity)) {
            val at = i * SpatialFrame.SPRITE_STRIDE
            facing(s[at] - eyeX, s[at + 1] - eyeY, s[at + 2] - eyeZ)
            fan(s, at)
        }
        val l = frame.lines
        for (i in 0 until minOf(frame.lineVertexCount / 2, lineCapacity)) {
            ribbon(l, 2 * i * SpatialFrame.LINE_STRIDE, eyeX, eyeY, eyeZ)
        }
        indices.fill(0, indexCount)
    }

    /** Sets [right] and [up] to unit vectors across the view direction ([dx], [dy], [dz], from the eye). */
    private fun facing(dx: Float, dy: Float, dz: Float) {
        // right = d x worldUp, up = right x d; straight up or down falls back to world x as right.
        var rx = -dz
        var rz = dx
        var rl = sqrt(rx * rx + rz * rz)
        if (rl < TINY) {
            rx = 1f
            rz = 0f
            rl = 1f
        }
        rx /= rl
        rz /= rl
        var ux = -rz * dy
        var uy = rz * dx - rx * dz
        var uz = rx * dy
        val ul = sqrt(ux * ux + uy * uy + uz * uz)
        if (ul < TINY) {
            ux = 0f
            uy = 1f
            uz = 0f
        } else {
            ux /= ul
            uy /= ul
            uz /= ul
        }
        right[0] = rx
        right[1] = 0f
        right[2] = rz
        up[0] = ux
        up[1] = uy
        up[2] = uz
    }

    private fun fan(s: FloatArray, at: Int) {
        val radius = s[at + 3]
        val r = s[at + 4]
        val g = s[at + 5]
        val b = s[at + 6]
        val a = s[at + 7]
        val crisp = s[at + 8] >= 0.5f
        val centre = vertexCount
        vertex(s[at], s[at + 1], s[at + 2], rgba(r, g, b, a))
        val rim = rgba(r, g, b, if (crisp) a else 0f)
        for (k in 0 until FAN_SEGMENTS) {
            val angle = 2 * PI * k / FAN_SEGMENTS
            val c = (cos(angle) * radius).toFloat()
            val n = (sin(angle) * radius).toFloat()
            vertex(
                s[at] + right[0] * c + up[0] * n,
                s[at + 1] + right[1] * c + up[1] * n,
                s[at + 2] + right[2] * c + up[2] * n,
                rim,
            )
        }
        for (k in 0 until FAN_SEGMENTS) {
            indices[indexCount++] = centre
            indices[indexCount++] = centre + 1 + k
            indices[indexCount++] = centre + 1 + (k + 1) % FAN_SEGMENTS
        }
    }

    private fun ribbon(l: FloatArray, at: Int, eyeX: Float, eyeY: Float, eyeZ: Float) {
        val b = at + SpatialFrame.LINE_STRIDE
        val ax = l[at]
        val ay = l[at + 1]
        val az = l[at + 2]
        val bx = l[b]
        val by = l[b + 1]
        val bz = l[b + 2]
        // Across the line and across the view: (b - a) x (midpoint - eye).
        val tx = bx - ax
        val ty = by - ay
        val tz = bz - az
        val vx = (ax + bx) / 2 - eyeX
        val vy = (ay + by) / 2 - eyeY
        val vz = (az + bz) / 2 - eyeZ
        var wx = ty * vz - tz * vy
        var wy = tz * vx - tx * vz
        var wz = tx * vy - ty * vx
        val wl = sqrt(wx * wx + wy * wy + wz * wz)
        if (wl < TINY) {
            wx = 0f
            wy = 0f
            wz = 0f
        } else {
            val k = LINE_WIDTH_METERS / 2 / wl
            wx *= k
            wy *= k
            wz *= k
        }
        val ca = rgba(l[at + 3], l[at + 4], l[at + 5], l[at + 6])
        val cb = rgba(l[b + 3], l[b + 4], l[b + 5], l[b + 6])
        val first = vertexCount
        vertex(ax - wx, ay - wy, az - wz, ca)
        vertex(ax + wx, ay + wy, az + wz, ca)
        vertex(bx + wx, by + wy, bz + wz, cb)
        vertex(bx - wx, by - wy, bz - wz, cb)
        indices[indexCount++] = first
        indices[indexCount++] = first + 1
        indices[indexCount++] = first + 2
        indices[indexCount++] = first
        indices[indexCount++] = first + 2
        indices[indexCount++] = first + 3
    }

    private fun vertex(x: Float, y: Float, z: Float, color: Int) {
        val at = vertexCount * XYZ
        positions[at] = x
        positions[at + 1] = y
        positions[at + 2] = z
        colors[vertexCount] = color
        vertexCount++
    }

    companion object {
        /** Each sprite is a centre and this many rim points. */
        const val FAN_SEGMENTS = 24
        const val SPRITE_VERTICES = FAN_SEGMENTS + 1
        const val SPRITE_INDICES = FAN_SEGMENTS * 3
        const val LINE_VERTICES = 4
        const val LINE_INDICES = 6

        /** A line is drawn this wide, about a pixel at the cloud's far edge on a headset. */
        const val LINE_WIDTH_METERS = 0.006f

        private const val XYZ = 3
        private const val TINY = 1e-6f
        private const val CHANNEL = 255f
        private const val HALF = 0.5f

        private fun channel(v: Float): Int = (v.coerceIn(0f, 1f) * CHANNEL + HALF).toInt()

        /** [r], [g], [b] premultiplied by [a], packed red-first. */
        internal fun rgba(r: Float, g: Float, b: Float, a: Float): Int =
            channel(r * a) or (channel(g * a) shl 8) or (channel(b * a) shl 16) or (channel(a) shl 24)
    }
}
