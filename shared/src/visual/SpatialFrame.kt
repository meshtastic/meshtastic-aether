package org.meshtastic.aether.visual

import androidx.compose.ui.graphics.Color
import org.meshtastic.aether.theme.AmbientPalette
import org.meshtastic.aether.ui.AmbientEngine
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the cloud sits around a viewer who stands on the anchor, in metres. The axes are the ones WebXR, ARKit and
 * ARCore share: x right, y up, z toward the viewer's back, with the floor at y 0 and the anchor at the origin. East is
 * +x and north is -z, so a node due north is straight ahead as a session starts.
 */
object SpatialLayout {
    /** A node at the layout's centre stands this far from the viewer, never inside their head... */
    const val NEAR_METERS = 1.2

    /** ...and one at its rim this far. */
    const val FAR_METERS = 4.5

    /** The height the cloud's plane floats at. */
    const val EYE_METERS = 1.4

    /** How far above or below that plane an orb with depth 1 sits. */
    const val HEIGHT_REACH_METERS = 1.0

    /** No orb sinks closer to the floor than this. */
    const val MIN_HEIGHT_METERS = 0.2

    /** One scene radius of the flat view, for sprite sizes. */
    const val SIZE_METERS = 2.0

    /** The anchor ring's radius on the floor around the viewer, breathing from 0.9 to 1.1 of it. */
    const val ANCHOR_RING_METERS = 0.3

    /** The ring is drawn as this many straight segments. */
    const val RING_SEGMENTS = 48

    /** A layout point this close to the centre has no bearing and is placed due north. */
    private const val NO_BEARING = 1e-9

    /**
     * Degrees to turn the frame about +y (counter-clockwise seen from above) so its -z, north, points along a viewer's
     * horizontal facing ([forwardX], [forwardZ]).
     */
    fun facingYawDegrees(forwardX: Float, forwardZ: Float): Float =
        (atan2(-forwardX.toDouble(), -forwardZ.toDouble()) * DEGREES_PER_RADIAN).toFloat()

    private const val DEGREES_PER_RADIAN = 180.0 / PI

    /** Metres from the viewer for a point [layoutRadius] (0..1) from the layout's centre. */
    fun distance(layoutRadius: Double): Double =
        NEAR_METERS + (FAR_METERS - NEAR_METERS) * layoutRadius.coerceIn(0.0, 1.0)

    /**
     * Writes the place of the layout point ([x] east, [y] south, [z] toward the flat view's camera when negative) into
     * [out] as x, y, z metres; [breath] swells the distance. Returns the layout radius, clamped to 0..1.
     */
    fun place(x: Double, y: Double, z: Double, breath: Double, out: FloatArray): Double {
        val r = hypot(x, y)
        val d = distance(r) * breath
        val east = if (r < NO_BEARING) 0.0 else x / r
        val south = if (r < NO_BEARING) -1.0 else y / r
        out[0] = (east * d).toFloat()
        out[1] = (EYE_METERS - z * HEIGHT_REACH_METERS).coerceAtLeast(MIN_HEIGHT_METERS).toFloat()
        out[2] = (south * d).toFloat()
        return r.coerceIn(0.0, 1.0)
    }
}

private const val EDGE_ALPHA = 0.12
private const val PULSE_TAIL_ALPHA = 0.5f
private const val PULSE_HEAD_RADIUS = 0.012f

/**
 * One frame of the cloud for a renderer that has no [androidx.compose.ui.graphics.drawscope.DrawScope]: flat arrays a
 * headset or AR view uploads as they are. Everything is drawn additively, so nothing needs sorting and the same frame
 * reads on a dark field and over a camera image. The arrays are reused and grow only when the mesh does.
 */
class SpatialFrame internal constructor() {
    /**
     * [spriteCount] round sprites that face the viewer, [SPRITE_STRIDE] floats each: x, y, z, radius in metres, r, g,
     * b, a, then 1 for a crisp disc or 0 for a halo fading to its rim.
     */
    var sprites = FloatArray(SPRITE_STRIDE * INITIAL_SPRITES)
        private set
    var spriteCount = 0
        private set

    /** [lineVertexCount] vertices, two per segment, [LINE_STRIDE] floats each: x, y, z, r, g, b, a. */
    var lines = FloatArray(LINE_STRIDE * INITIAL_LINE_VERTICES)
        private set
    var lineVertexCount = 0
        private set

    /** The field's color behind the cloud as r, g, b, for a view with no camera image. */
    val background = FloatArray(RGB)

    private val colors = SceneColors()
    private val a = FloatArray(XYZ)
    private val b = FloatArray(XYZ)
    private var dim = 1f
    private var coreDim = 1f
    private var sat = 1.0

    internal fun fill(scene: SceneModel, palette: AmbientPalette, tSeconds: Double) {
        colors.use(palette)
        dim = palette.dim(scene.minuteOfDay).toFloat()
        coreDim = sqrt(dim)
        sat = scene.saturation
        spriteCount = 0
        lineVertexCount = 0
        val hueDeg = timeHueOffset(scene.minuteOfDay) + scene.hueShift
        val field = shiftColor(palette.backgroundTop, hueDeg, sat, dim * scene.fieldBreath(tSeconds))
        background[0] = field.red
        background[1] = field.green
        background[2] = field.blue
        addEdges(scene, palette)
        addAnchor(scene, palette)
        addOrbs(scene)
        addPulses(scene)
    }

    /** Fills [out] with where [node] is (null or our own node is the anchor, on the floor); false when unknown. */
    private fun resolve(scene: SceneModel, node: Long?, out: FloatArray): Boolean {
        val o = if (node == null || node == scene.anchor?.node) null else scene.orb(node) ?: return false
        if (o == null) out.fill(0f) else placeOrb(scene, o, out)
        return true
    }

    private fun placeOrb(scene: SceneModel, o: Orb, out: FloatArray): Double = SpatialLayout.place(
        o.shownX + o.phOx + scene.driftX(o.node),
        o.shownY + o.phOy + scene.driftY(o.node),
        o.shownZ + o.zOffset + scene.driftZ(o.node),
        scene.cloudBreath,
        out,
    )

    private fun addEdges(scene: SceneModel, palette: AmbientPalette) {
        for (e in scene.edges) {
            if (!resolve(scene, e.a, a) || !resolve(scene, e.b, b)) continue
            val alpha = (EDGE_ALPHA * scene.edgeFade(e) * dim).toFloat()
            line(a, b, palette.edge, alpha)
        }
    }

    private fun addAnchor(scene: SceneModel, palette: AmbientPalette) {
        val ring = scene.anchorBreath.toFloat()
        val radius = SpatialLayout.ANCHOR_RING_METERS * (ANCHOR_RING_REST + ANCHOR_RING_BREATH * ring)
        val alpha = anchorRingAlpha(ring) * coreDim
        val color = desaturate(scene.anchor?.let { colors.node(it.node) } ?: palette.anchor, sat)
        val step = 2 * PI / SpatialLayout.RING_SEGMENTS
        a[1] = 0f
        b[1] = 0f
        for (i in 0 until SpatialLayout.RING_SEGMENTS) {
            a[0] = (radius * cos(i * step)).toFloat()
            a[2] = (radius * sin(i * step)).toFloat()
            b[0] = (radius * cos((i + 1) * step)).toFloat()
            b[2] = (radius * sin((i + 1) * step)).toFloat()
            line(a, b, color, alpha)
        }
    }

    private fun addOrbs(scene: SceneModel) {
        for (o in scene.orbs) {
            val r = placeOrb(scene, o, a)
            val g = scene.glow(o)
            val breath = scene.breath(o.node).toFloat()
            val lift = (o.brightness * o.flicker).toFloat()
            val depthT = 2 * r - 1
            val coolMix = CloudMotion.coolMix(depthT)
            val color = cool(desaturate(colors.node(o.node), sat), coolMix)
            val haloColor = if (o.haloHueDegrees == 0) {
                color
            } else {
                cool(desaturate(colors.shifted(o.node, o.haloHueDegrees), sat), coolMix)
            }
            val haloR = HALO_RADIUS * (1f + g.toFloat()) * breath * o.haloScale.toFloat() * HALO_SPRITE_SPREAD
            val haloAlpha = (orbHaloAlpha(g) * CloudMotion.brightness(depthT) * lift).coerceAtMost(ORB_ALPHA_CAP / 2)
            sprite(a, haloR * SIZE, haloColor, haloAlpha * dim, crisp = false)
            val coreR = CORE_RADIUS * (CORE_REST + g.toFloat() * CORE_GLOW_GROWTH) * breath * o.coreSize.toFloat()
            val coreAlpha = (CloudMotion.shadeAlpha(orbCoreAlpha(g), depthT) * o.coreAlpha.toFloat() * lift)
                .coerceAtMost(ORB_ALPHA_CAP)
            sprite(a, coreR * SIZE, color, coreAlpha * coreDim, crisp = true)
        }
    }

    private fun addPulses(scene: SceneModel) {
        for (pu in scene.pulses) {
            if (!resolve(scene, pu.fromNode, a) || !resolve(scene, pu.toNode, b)) continue
            val p = scene.progress(pu).toFloat()
            val tp = (p - PULSE_TAIL).coerceAtLeast(0f)
            val color = desaturate(colors.node(pu.colorNode), sat)
            val alpha = (if (p >= 1f) ARRIVED_PULSE_ALPHA else PULSE_ALPHA) * dim
            val ax = a[0]
            val ay = a[1]
            val az = a[2]
            a[0] = ax + (b[0] - ax) * tp
            a[1] = ay + (b[1] - ay) * tp
            a[2] = az + (b[2] - az) * tp
            b[0] = ax + (b[0] - ax) * p
            b[1] = ay + (b[1] - ay) * p
            b[2] = az + (b[2] - az) * p
            line(a, b, color, alpha * PULSE_TAIL_ALPHA)
            sprite(b, PULSE_HEAD_RADIUS * SIZE, color, alpha, crisp = true)
        }
    }

    private fun sprite(at: FloatArray, radius: Float, color: Color, alpha: Float, crisp: Boolean) {
        var i = spriteCount * SPRITE_STRIDE
        if (i + SPRITE_STRIDE > sprites.size) sprites = sprites.copyOf(sprites.size * 2)
        val s = sprites
        s[i++] = at[0]
        s[i++] = at[1]
        s[i++] = at[2]
        s[i++] = radius
        s[i++] = color.red
        s[i++] = color.green
        s[i++] = color.blue
        s[i++] = alpha
        s[i] = if (crisp) 1f else 0f
        spriteCount++
    }

    private fun line(from: FloatArray, to: FloatArray, color: Color, alpha: Float) {
        if (lineVertexCount * LINE_STRIDE + 2 * LINE_STRIDE > lines.size) lines = lines.copyOf(lines.size * 2)
        vertex(from, color, alpha)
        vertex(to, color, alpha)
    }

    private fun vertex(at: FloatArray, color: Color, alpha: Float) {
        var i = lineVertexCount * LINE_STRIDE
        val l = lines
        l[i++] = at[0]
        l[i++] = at[1]
        l[i++] = at[2]
        l[i++] = color.red
        l[i++] = color.green
        l[i++] = color.blue
        l[i] = alpha
        lineVertexCount++
    }

    companion object {
        const val SPRITE_STRIDE = 9
        const val LINE_STRIDE = 7

        // Offsets into a sprite after its x, y, z.
        const val SPRITE_RADIUS = 3
        const val SPRITE_RED = 4
        const val SPRITE_GREEN = 5
        const val SPRITE_BLUE = 6
        const val SPRITE_ALPHA = 7
        const val SPRITE_CRISP = 8

        // Offsets into a line vertex after its x, y, z.
        const val LINE_RED = 3
        const val LINE_GREEN = 4
        const val LINE_BLUE = 5
        const val LINE_ALPHA = 6
        private const val INITIAL_SPRITES = 256
        private const val INITIAL_LINE_VERTICES = 512
        private const val XYZ = 3
        private const val RGB = 3
        private const val SIZE = SpatialLayout.SIZE_METERS.toFloat()
    }
}

/**
 * One session's cloud for a headset or AR view. [frame] advances the scene to the audio clock and returns it placed
 * around the viewer; call it once per displayed frame, on the thread that runs the app's UI. A renderer that sees
 * [closed] tears its view down: the session it showed has ended.
 */
class SpatialSource internal constructor(private val engine: AmbientEngine, private val palette: AmbientPalette) {
    private val out = SpatialFrame()

    var closed = false
        private set

    private var onClose: (() -> Unit)? = null

    /** Ends this view: the session it showed has ended or the host left it. Safe to call more than once. */
    fun close() {
        if (closed) return
        closed = true
        onClose?.invoke()
        onClose = null
    }

    /** Runs [action] once when this view closes, at once if it already has; a host uses it to end its XR session. */
    fun whenClosed(action: () -> Unit) {
        if (closed) action() else onClose = action
    }

    /** Advances the scene and returns this frame of it. */
    fun frame(): SpatialFrame {
        engine.scene.update(engine.playbackFrame())
        out.fill(engine.scene, palette, engine.playbackSeconds())
        return out
    }
}

/** The spatial views one session opened; [closeAll] ends them when the session does. */
internal class SpatialViews {
    private val open = ArrayList<SpatialSource>()

    fun open(engine: AmbientEngine, palette: AmbientPalette): SpatialSource {
        open.removeAll { it.closed }
        return SpatialSource(engine, palette).also { open += it }
    }

    fun closeAll() {
        for (s in open) s.close()
        open.clear()
    }
}
