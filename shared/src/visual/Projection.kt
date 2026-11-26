package org.meshtastic.ambient.visual

import org.meshtastic.ambient.mood.RotationState
import kotlin.math.cos
import kotlin.math.sin

/** A projected point: [x] and [y] are screen offsets from the centre in the same units as the input, [scale] the perspective factor, [depth] the distance past the cloud centre (positive is away). */
class Projected {
    var x = 0.0
    var y = 0.0
    var scale = 1.0
    var depth = 0.0
}

/**
 * Perspective projection of the cloud. Input axes: x right, y down, z away from the viewer. [update] turns the cloud
 * by yaw about the vertical axis, then tilts it by pitch about the horizontal one, and places the camera
 * [cameraDistance] in front of the centre; the sines and cosines are computed there, once per frame. The origin
 * always lands at the screen centre with scale 1.
 */
class Projection {
    private var sinYaw = 0.0
    private var cosYaw = 1.0
    private var sinPitch = 0.0
    private var cosPitch = 1.0
    private var distance = DEFAULT_CAMERA_DISTANCE

    fun update(yaw: Double, pitch: Double, cameraDistance: Double) {
        sinYaw = sin(yaw)
        cosYaw = cos(yaw)
        sinPitch = sin(pitch)
        cosPitch = cos(pitch)
        distance = cameraDistance
    }

    /** Turns by the integrated [rotation]: yaw as is, pitch the axis tilt plus the wobble on top. */
    fun update(rotation: RotationState, cameraDistance: Double) =
        update(rotation.yawRadians, rotation.tiltRadians + rotation.wobbleRadians, cameraDistance)

    fun project(x: Double, y: Double, z: Double, out: Projected) {
        val xr = x * cosYaw + z * sinYaw
        val zr = -x * sinYaw + z * cosYaw
        val yr = y * cosPitch - zr * sinPitch
        val depth = y * sinPitch + zr * cosPitch
        val s = distance / (distance + depth).coerceAtLeast(distance * MIN_DISTANCE_FRACTION)
        out.x = xr * s
        out.y = yr * s
        out.scale = s
        out.depth = depth
    }

    companion object {
        const val DEFAULT_CAMERA_DISTANCE = 3.0

        /** The camera never sits closer to a point than this fraction of its distance, so scale stays finite. */
        private const val MIN_DISTANCE_FRACTION = 0.2
    }
}

/** The slow motion and look of the cloud. Every constant is named; times are seconds. */
object CloudMotion {
    /** The cloud swells and shrinks by this fraction around its size... */
    const val BREATH_FRACTION = 0.03

    /** ...once a minute. */
    const val BREATH_PERIOD_SECONDS = 60.0

    /** How far an orb with depth 1 sits from the plane, in unit-disc units. */
    const val Z_EXTENT = 0.7

    /** Distance from the camera to the cloud centre, in unit-disc units. */
    const val CAMERA_DISTANCE = Projection.DEFAULT_CAMERA_DISTANCE

    /** Depth beyond which the depth cues stop growing, in unit-disc units (the cloud radius plus its z reach). */
    const val DEPTH_RANGE = 1.3

    /** Brightness multiplier of a far orb (before the alpha cap and idle floor)... */
    const val FAR_BRIGHTNESS = 0.7

    /** ...and of a near one. */
    const val NEAR_BRIGHTNESS = 1.3

    /** How far toward the cool tint the farthest orbs are pulled, 0..1. */
    const val FAR_COOL_MIX = 0.3

    /** Multiplier near 1.0 applied to every coordinate of the cloud, from the breathing phase accumulator [phase] in radians. */
    fun breathing(phase: Double): Double = 1.0 + BREATH_FRACTION * sin(phase)

    /** Depth normalised to -1 (nearest) .. 1 (farthest) for the depth cues. */
    fun depthT(depth: Double): Double = (depth / DEPTH_RANGE).coerceIn(-1.0, 1.0)

    /** Brightness multiplier for a point at [depthT]: [NEAR_BRIGHTNESS] at -1, [FAR_BRIGHTNESS] at 1, linear. */
    fun brightness(depthT: Double): Float {
        val k = (depthT + 1.0) / 2.0
        return (NEAR_BRIGHTNESS + (FAR_BRIGHTNESS - NEAR_BRIGHTNESS) * k).toFloat()
    }

    /** Cool-tint mix 0..[FAR_COOL_MIX] for a point at [depthT]: none at the middle and nearer, full at the far edge. */
    fun coolMix(depthT: Double): Float = (FAR_COOL_MIX * depthT.coerceIn(0.0, 1.0)).toFloat()

    /**
     * [alpha] shaded by depth, capped at [ORB_ALPHA_CAP], and never below the smaller of [alpha] and the idle floor,
     * so a far orb at rest stays as visible as an orb at the floor.
     */
    fun shadeAlpha(alpha: Float, depthT: Double): Float =
        maxOf(alpha * brightness(depthT), minOf(alpha, SceneModel.IDLE_FLOOR.toFloat())).coerceAtMost(ORB_ALPHA_CAP)
}

/** Draw order for the cloud, far to near, without allocating. */
object DepthSort {
    /**
     * Reorders the first [n] entries of [order] so [depth] at those indices is non-increasing (largest, farthest, first).
     * Insertion sort: the order from the previous frame is nearly sorted, so a frame costs about n steps.
     */
    fun backToFront(depth: DoubleArray, order: IntArray, n: Int) {
        for (i in 1 until n) {
            val v = order[i]
            val d = depth[v]
            var j = i - 1
            while (j >= 0 && depth[order[j]] < d) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = v
        }
    }
}
