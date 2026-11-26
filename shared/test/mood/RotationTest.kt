package org.meshtastic.ambient.mood

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RotationTest {
    private val deg = PI / 180.0

    private fun target(
        period: Double = 600.0,
        direction: Int = 1,
        tilt: Double = 0.0,
        wobble: Double = 8.0,
        impulse: Double = 0.0,
        strength: Double = 1.0,
    ) = RotationTarget(period, direction, tilt, wobble, impulse, strength)

    private fun settle(r: RotationIntegrator, t: RotationTarget, seconds: Int): RotationState {
        var s = r.step(0.0, t)
        repeat(seconds) { s = r.step(1.0, t) }
        return s
    }

    @Test
    fun `the neutral target reproduces today's yaw and pitch swing`() {
        val r = RotationIntegrator()
        var s = r.step(0.0, RotationTarget.NEUTRAL)
        for (i in 1..3000) {
            s = r.step(0.1, RotationTarget.NEUTRAL)
            val t = i * 0.1
            assertEquals(2 * PI * t / 600.0, s.yawRadians, 1e-9)
            assertEquals(8.0 * deg * sin(2 * PI * t / 90.0), s.tiltRadians + s.wobbleRadians, 1e-9)
        }
        assertEquals(1, s.direction)
    }

    @Test
    fun `the cloud eases to the busy speed and never turns faster than 150 seconds`() {
        val r = RotationIntegrator()
        val s = settle(r, target(period = 150.0, tilt = 0.0), 120)
        assertEquals(2 * PI / 150.0, s.angularVelocity, 1e-9)
        var worst = 0.0
        val limit = 2 * PI / 150.0
        repeat(2000) {
            val o = r.step(0.5, target(period = 10.0, impulse = 1.0))
            worst = maxOf(worst, abs(o.angularVelocity))
        }
        assertTrue(worst <= limit + 1e-12, "worst $worst")
    }

    @Test
    fun `angular velocity never changes faster than the acceleration cap`() {
        val r = RotationIntegrator()
        var prev = r.step(0.0, target()).angularVelocity
        val targets = listOf(target(period = 150.0), target(period = 1200.0), target(period = 150.0, direction = -1))
        for (t in targets) repeat(200) {
            val s = r.step(0.5, t)
            assertTrue(abs(s.angularVelocity - prev) <= RotationIntegrator.MAX_ACCELERATION * 0.5 + 1e-12)
            prev = s.angularVelocity
        }
    }

    @Test
    fun `yaw stays continuous when the period changes`() {
        val r = RotationIntegrator()
        settle(r, target(period = 600.0), 3000)
        var prev = r.step(0.0, target()).yawRadians
        var prevDelta = 2 * PI * 1.0 / 600.0
        val maxStep = 2 * PI / 150.0
        for (i in 0 until 120) {
            val s = r.step(1.0, target(period = 150.0))
            val delta = s.yawRadians - prev
            assertTrue(delta >= 0 && delta <= maxStep + 1e-12)
            assertTrue(abs(delta - prevDelta) <= RotationIntegrator.MAX_ACCELERATION + 1e-9, "jump at $i")
            prev = s.yawRadians
            prevDelta = delta
        }
    }

    @Test
    fun `a packet impulse is felt within seconds and decays when it stops`() {
        val r = RotationIntegrator()
        val base = settle(r, target(), 60).angularVelocity
        var s = base
        var peak = 0.0
        repeat(8) { peak = maxOf(peak, r.step(1.0, target(impulse = 0.5)).angularVelocity - base) }
        assertTrue(peak > 0.002, "peak $peak")
        repeat(60) { s = r.step(1.0, target(impulse = 0.0)).angularVelocity }
        assertTrue(s - base < 0.01 * peak, "left ${s - base}")
    }

    @Test
    fun `a direction flip decelerates through zero and turns within 30 seconds`() {
        val r = RotationIntegrator()
        settle(r, target(period = 150.0), 60)
        val flip = target(period = 150.0, direction = -1)
        var crossed = -1
        for (i in 1..30) {
            val s = r.step(1.0, flip)
            if (crossed < 0 && s.angularVelocity <= 0.0) crossed = i
        }
        assertTrue(crossed in 1..30, "crossed at $crossed")
        val s = r.step(1.0, flip)
        assertEquals(-1, s.direction)
        assertTrue(s.angularVelocity < 0.0)
    }

    @Test
    fun `evidence inside the hysteresis band never flips the direction`() {
        val r = RotationIntegrator()
        settle(r, target(period = 300.0), 30)
        val weak = target(period = 300.0, direction = -1, strength = 0.2)
        repeat(2 * 3600) {
            val s = r.step(1.0, weak)
            assertEquals(1, s.direction)
            assertTrue(s.angularVelocity > 0.0)
        }
        val strong = target(period = 300.0, direction = -1, strength = 0.25)
        var flipped = false
        repeat(60) { if (r.step(1.0, strong).direction == -1) flipped = true }
        assertTrue(flipped)
    }

    @Test
    fun `a second flip is refused until 30 minutes after the first`() {
        val r = RotationIntegrator()
        settle(r, target(period = 300.0), 30)
        val back = target(period = 300.0, direction = -1)
        var s = r.step(1.0, back)
        var guard = 0
        while (s.direction != -1 && guard++ < 100) s = r.step(1.0, back)
        assertEquals(-1, s.direction)
        val forward = target(period = 300.0, direction = 1)
        repeat(29 * 60) {
            s = r.step(1.0, forward)
            assertEquals(-1, s.direction)
            assertTrue(s.angularVelocity <= 0.0)
        }
        repeat(2 * 60) { s = r.step(1.0, forward) }
        assertEquals(1, s.direction)
        assertTrue(s.angularVelocity > 0.0)
    }

    @Test
    fun `a target with no direction preference keeps the current way`() {
        val r = RotationIntegrator()
        settle(r, target(), 30)
        repeat(600) {
            val s = r.step(1.0, target(direction = 0))
            assertEquals(1, s.direction)
        }
    }

    @Test
    fun `tilt plus wobble never exceed 20 degrees under any inputs`() {
        val r = RotationIntegrator()
        val wild = listOf(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -50.0, 0.0, 7.0, 14.0, 20.0, 35.0, 1e9,
        )
        var worst = 0.0
        for (a in wild) for (b in wild) {
            val t = target(tilt = a, wobble = b)
            repeat(400) {
                val s = r.step(5.0, t)
                worst = maxOf(worst, abs(s.tiltRadians) + abs(s.wobbleRadians))
            }
        }
        assertTrue(worst <= 20.0 * deg + 1e-12, "worst ${worst / deg} deg")
    }

    @Test
    fun `tilt moves gradually rather than jumping to its target`() {
        val r = RotationIntegrator()
        r.step(0.0, target(tilt = 14.0, wobble = 0.0))
        val s = r.step(1.0, target(tilt = 14.0, wobble = 0.0))
        assertTrue(s.tiltRadians / deg < 0.1)
        val later = settle(r, target(tilt = 14.0, wobble = 0.0), 400)
        assertEquals(14.0, later.tiltRadians / deg, 1e-9)
    }

    @Test
    fun `odd step sizes and non finite targets never give NaN`() {
        val r = RotationIntegrator()
        val nan = target(period = Double.NaN, tilt = Double.NaN, wobble = Double.NaN, impulse = Double.NaN, strength = Double.NaN)
        for (dt in listOf(0.0, -3.0, Double.NaN, Double.POSITIVE_INFINITY, 1e12, 0.016)) {
            val s = r.step(dt, nan)
            assertTrue(s.yawRadians.isFinite() && s.angularVelocity.isFinite() && s.tiltRadians.isFinite() && s.wobbleRadians.isFinite())
        }
        assertEquals(RotationState(0.0, 0.0, 0.0, 0.0, 1).direction, r.step(0.0, nan).direction)
    }
}
