package org.meshtastic.aether.mood

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * What the mesh asks of the cloud's rotation right now.
 * [direction] is -1, +1 or 0 (no preference, keep turning the way it is); [directionStrength] is how far past neutral
 * the evidence for it is, 0..1, which the integrator compares with its hysteresis band. [impulse] is the 0..1 packet surge.
 */
data class RotationTarget(
    val targetPeriodSeconds: Double,
    val direction: Int,
    val tiltDegrees: Double,
    val wobbleDegrees: Double,
    val impulse: Double,
    val directionStrength: Double = 1.0,
) {
    companion object {
        val NEUTRAL = RotationTarget(
            RotationTargets.NEUTRAL_PERIOD_SECONDS, 1, RotationTargets.NEUTRAL_TILT_DEGREES,
            RotationTargets.NEUTRAL_WOBBLE_DEGREES, 0.0, 0.0,
        )
    }
}

/** Integrated rotation. Yaw is unwrapped radians, positive clockwise; direction is the way the cloud is turning now. */
data class RotationState(
    val yawRadians: Double,
    val angularVelocity: Double,
    val tiltRadians: Double,
    val wobbleRadians: Double,
    val direction: Int,
)

/**
 * Turns a [RotationTarget] into motion that is safe to look at: angular velocity eases with an acceleration cap,
 * a direction change decelerates through a stop and turns, a flip needs evidence past a hysteresis band and 30 minutes
 * since the last one, a packet impulse adds a small decaying push, and tilt plus wobble never leave 20 degrees.
 * Every phase is an accumulator, so a period that changes never makes the yaw jump.
 */
class RotationIntegrator(initialPeriodSeconds: Double = RotationTargets.NEUTRAL_PERIOD_SECONDS) {
    private var yaw = 0.0
    private var omega = 2 * PI / initialPeriodSeconds.coerceAtLeast(RotationTargets.MIN_PERIOD_SECONDS)
    private var total = omega
    private var dir = 1
    private var turning = false
    private var pendingDir = 1
    private var push = 0.0
    private var clockS = 0.0
    private var lastFlipS = -MIN_FLIP_SECONDS
    private var tilt = RotationTargets.NEUTRAL_TILT_DEGREES * DEG
    private var amp = RotationTargets.NEUTRAL_WOBBLE_DEGREES * DEG
    private var phase = 0.0

    /** Advances by [dtSeconds] (non-finite or negative counts as zero, more than two minutes as two) and returns the new state. */
    fun step(dtSeconds: Double, target: RotationTarget): RotationState {
        var rem = if (dtSeconds.isFinite() && dtSeconds > 0.0) minOf(dtSeconds, MAX_DT_SECONDS) else 0.0
        val period = finiteOr(target.targetPeriodSeconds, RotationTargets.NEUTRAL_PERIOD_SECONDS)
            .coerceAtLeast(RotationTargets.MIN_PERIOD_SECONDS)
        val speed = 2 * PI / period
        val impulse = clamp01(finiteOr(target.impulse, 0.0))
        val strength = clamp01(finiteOr(target.directionStrength, 0.0))
        val tTilt = finiteOr(target.tiltDegrees, 0.0).coerceIn(0.0, MAX_OFFSET_DEGREES) * DEG
        val tAmp = finiteOr(target.wobbleDegrees, 0.0).coerceIn(0.0, MAX_OFFSET_DEGREES) * DEG
        while (rem > 0.0) {
            val h = minOf(rem, SUB_STEP_SECONDS)
            rem -= h
            clockS += h
            if (!turning && target.direction != 0 && target.direction != dir && strength >= HYSTERESIS &&
                clockS - lastFlipS >= MIN_FLIP_SECONDS
            ) {
                turning = true
                pendingDir = if (target.direction > 0) 1 else -1
            }
            omega = moveToward(omega, if (turning) 0.0 else dir * speed, MAX_ACCELERATION * h)
            if (turning && omega == 0.0) {
                dir = pendingDir
                turning = false
                lastFlipS = clockS
            }
            val pushTarget = if (turning) 0.0 else dir * impulse * PUSH_MAX
            push += (pushTarget - push) * (1 - exp(-h / PUSH_TAU_SECONDS))
            total = (omega + push).coerceIn(-MAX_ANGULAR_VELOCITY, MAX_ANGULAR_VELOCITY)
            yaw += total * h
            tilt = moveToward(tilt, tTilt, TILT_RATE * h)
            amp = moveToward(amp, tAmp, TILT_RATE * h)
            phase = (phase + 2 * PI * h / WOBBLE_PERIOD_SECONDS) % (2 * PI)
        }
        val tiltOut = tilt.coerceIn(0.0, MAX_OFFSET_DEGREES * DEG)
        val ampOut = amp.coerceIn(0.0, MAX_OFFSET_DEGREES * DEG - tiltOut)
        return RotationState(yaw, total, tiltOut, ampOut * sin(phase), dir)
    }

    private fun moveToward(v: Double, goal: Double, maxDelta: Double): Double =
        if (goal > v) minOf(v + maxDelta, goal) else maxOf(v - maxDelta, goal)

    private fun finiteOr(v: Double, fallback: Double) = if (v.isFinite()) v else fallback

    companion object {
        /** Angular acceleration cap in rad/s2: from rest to the fastest turn in about 20 s. */
        const val MAX_ACCELERATION = 2 * PI / RotationTargets.MIN_PERIOD_SECONDS / 20.0

        /** One turn is never faster than 150 s. */
        const val MAX_ANGULAR_VELOCITY = 2 * PI / RotationTargets.MIN_PERIOD_SECONDS

        /** A flip needs this fraction of the trend range in favor of the new direction. */
        const val HYSTERESIS = 0.25

        /** Minimum time between completed flips. */
        const val MIN_FLIP_SECONDS = 30.0 * 60

        /** Tilt plus wobble never exceed this many degrees off level. */
        const val MAX_OFFSET_DEGREES = 20.0

        /** The wobble period; the neutral pitch swing is 90 s. */
        const val WOBBLE_PERIOD_SECONDS = 90.0

        private const val DEG = PI / 180.0
        private const val PUSH_MAX = 0.02
        private const val PUSH_TAU_SECONDS = 4.0
        private const val TILT_RATE = 0.05 * DEG
        private const val SUB_STEP_SECONDS = 1.0
        private const val MAX_DT_SECONDS = 120.0
    }
}
