package org.meshtastic.aether.visual

import org.meshtastic.aether.mood.ParamValues
import org.meshtastic.aether.mood.RotationIntegrator
import org.meshtastic.aether.mood.RotationState
import org.meshtastic.aether.mood.RotationTarget
import org.meshtastic.aether.mood.RotationTargets
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln

/** Time constant of the scene's easing toward the mesh-driven rotation parameters. */
private const val PARAM_EASE_SECONDS = 10.0
private const val IMPULSE_TAU_SECONDS = 10.0

/**
 * The cloud's turning and pull: eases toward the mesh-driven period, tilt, wobble and gravity so the picture never
 * jumps to them, and integrates the rotation with the packet impulse.
 */
internal class SceneRotation {
    private val integrator = RotationIntegrator()
    private var easedPeriod = RotationTargets.NEUTRAL_PERIOD_SECONDS
    private var easedTilt = RotationTargets.NEUTRAL_TILT_DEGREES
    private var easedWobble = RotationTargets.NEUTRAL_WOBBLE_DEGREES
    private var impulse = 0.0

    /** The eased gravity, 0..1 once eased; see [easeGravity]. */
    var gravity = ParamValues.NEUTRAL_GRAVITY
        private set

    /** The integrated rotation as of the last [step]: yaw is the unwrapped angle, pitch is tilt plus wobble. */
    var rotation = RotationState(0.0, 2 * PI / RotationTargets.NEUTRAL_PERIOD_SECONDS, 0.0, 0.0, 1)
        private set

    /** A packet adds [SceneModel.PACKET_IMPULSE] to the impulse, capped at 1.0. */
    fun kick() {
        impulse = (impulse + SceneModel.PACKET_IMPULSE).coerceAtMost(1.0)
    }

    fun step(dtSeconds: Double, p: ParamValues) {
        val ease = 1 - exp(-dtSeconds / PARAM_EASE_SECONDS)
        val period = if (p.rotationPeriodSeconds.isFinite()) {
            p.rotationPeriodSeconds.coerceAtLeast(RotationTargets.MIN_PERIOD_SECONDS)
        } else {
            easedPeriod
        }
        easedPeriod = exp(ln(easedPeriod) + (ln(period) - ln(easedPeriod)) * ease)
        if (p.tiltDegrees.isFinite()) easedTilt += (p.tiltDegrees - easedTilt) * ease
        if (p.wobbleDegrees.isFinite()) easedWobble += (p.wobbleDegrees - easedWobble) * ease
        impulse *= exp(-dtSeconds / IMPULSE_TAU_SECONDS)
        val strength = p.directionStrength
        rotation = integrator.step(
            dtSeconds,
            RotationTarget(easedPeriod, p.rotationDirection, easedTilt, easedWobble, impulse, strength),
        )
    }

    /** Eases [gravity] toward [target]; a non-finite target leaves it. */
    fun easeGravity(dtSeconds: Double, target: Double) {
        if (target.isFinite()) gravity += (target - gravity) * (1 - exp(-dtSeconds / PARAM_EASE_SECONDS))
    }
}
