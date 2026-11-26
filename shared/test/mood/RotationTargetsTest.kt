package org.meshtastic.aether.mood

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RotationTargetsTest {
    @Test
    fun `period runs from 1200 seconds at activity 0 to 150 at activity 1`() {
        assertEquals(1200.0, RotationTargets.periodSeconds(0.0, 1.0), 1e-6)
        assertEquals(150.0, RotationTargets.periodSeconds(1.0, 1.0), 1e-6)
        assertEquals(1200.0 * 0.125.pow(0.5), RotationTargets.periodSeconds(0.5, 1.0), 1e-6)
    }

    @Test
    fun `period is today's 600 seconds without confidence and halfway in log terms at half confidence`() {
        assertEquals(600.0, RotationTargets.periodSeconds(1.0, 0.0))
        assertEquals(kotlin.math.sqrt(600.0 * 150.0), RotationTargets.periodSeconds(1.0, 0.5), 1e-6)
    }

    @Test
    fun `period never drops under the 150 second floor for any activity or confidence`() {
        for (a in listOf(-5.0, 0.0, 0.7, 1.0, 9.0, Double.NaN)) for (c in listOf(-1.0, 0.0, 0.3, 1.0, 4.0)) {
            val p = RotationTargets.periodSeconds(a, c)
            assertTrue(p.isFinite() && p >= 150.0, "period $p")
        }
    }

    @Test
    fun `tilt spans 4 to 14 degrees from pressure and falls back to reach then to the neutral constant`() {
        assertEquals(4.0, RotationTargets.tiltDegrees(0.0, 1.0, 1.0, 1.0), 1e-9)
        assertEquals(14.0, RotationTargets.tiltDegrees(-1.0, 1.0, 0.0, 1.0), 1e-9)
        assertEquals(9.0, RotationTargets.tiltDegrees(0.5, 1.0, 0.0, 0.0), 1e-9)
        assertEquals(14.0, RotationTargets.tiltDegrees(0.0, 0.0, 1.0, 1.0), 1e-9)
        assertEquals(4.0, RotationTargets.tiltDegrees(0.0, 0.0, 0.0, 1.0), 1e-9)
        assertEquals(0.0, RotationTargets.tiltDegrees(0.9, 0.0, 0.9, 0.0), 1e-9)
    }

    @Test
    fun `wobble spans 1 to 6 degrees with wind then senders and is 3 without data`() {
        assertEquals(1.0, RotationTargets.wobbleDegrees(0.0, 1.0), 1e-9)
        assertEquals(6.0, RotationTargets.wobbleDegrees(1.0, 1.0), 1e-9)
        assertEquals(3.0, RotationTargets.wobbleDegrees(1.0, 0.0), 1e-9)
        assertEquals(6.0, RotationTargets.wobbleDegrees(0.0, 0.0, 1.0, 1.0), 1e-9)
        assertEquals(1.0 + 5.0 * 0.9, RotationTargets.wobbleDegrees(0.9, 1.0, 0.0, 1.0), 1e-9)
        assertEquals(4.5, RotationTargets.wobbleDegrees(0.0, 0.0, 1.0, 0.5), 1e-9)
    }

    @Test
    fun `channel use drives the wobble over senders and wind still wins over both`() {
        assertEquals(6.0, RotationTargets.wobbleDegrees(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), 1e-9)
        assertEquals(3.5, RotationTargets.wobbleDegrees(0.0, 0.0, 0.0, 1.0, 1.0, 0.5), 1e-9)
        assertEquals(1.0, RotationTargets.wobbleDegrees(0.0, 1.0, 1.0, 1.0, 1.0, 1.0), 1e-9)
    }

    @Test
    fun `the neutral wobble is within the driven range`() {
        assertTrue(RotationTargets.NEUTRAL_WOBBLE_DEGREES in RotationTargets.WOBBLE_MIN_DEGREES..RotationTargets.WOBBLE_MAX_DEGREES)
    }

    private fun choose(p: Double, pc: Double, t: Double, tc: Double, b: Double, bc: Double, a: Double, ac: Double) =
        RotationTargets.chooseDirection(p, pc, t, tc, b, bc, a, ac)

    @Test
    fun `direction follows pressure then temperature then battery then activity and is clockwise with no data`() {
        val all = choose(-0.4, 1.0, 0.7, 1.0, 0.8, 1.0, 0.9, 1.0)
        assertEquals(RotationTargets.DirectionChoice(-1, 0.4, RotationTargets.DirectionSource.Pressure), all)
        assertEquals(RotationTargets.DirectionChoice(1, 0.7, RotationTargets.DirectionSource.Temperature), choose(0.4, 0.0, 0.7, 0.5, -0.8, 1.0, -0.9, 1.0))
        assertEquals(RotationTargets.DirectionChoice(-1, 0.8, RotationTargets.DirectionSource.Battery), choose(0.0, 0.0, 0.0, 0.0, -0.8, 1.0, 0.9, 1.0))
        assertEquals(RotationTargets.DirectionChoice(-1, 0.9, RotationTargets.DirectionSource.Activity), choose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -0.9, 1.0))
        assertEquals(RotationTargets.DirectionChoice(1, 0.0, RotationTargets.DirectionSource.None), choose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
    }

    @Test
    fun `a flat trend falls through to the next source and a weak activity trend is not a source`() {
        assertEquals(RotationTargets.DirectionSource.Battery, choose(0.0, 1.0, 0.0, 1.0, 0.3, 1.0, 0.0, 0.0).source)
        assertEquals(RotationTargets.DirectionChoice(1, 0.0, RotationTargets.DirectionSource.None), choose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -0.2, 1.0))
        assertEquals(RotationTargets.DirectionSource.Activity, choose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.25, 1.0).source)
    }

    @Test
    fun `capped tilt and wobble together stay within 20 degrees for any inputs`() {
        val inputs = listOf(Double.NaN, Double.POSITIVE_INFINITY, -4.0, 0.0, 3.0, 14.0, 19.5, 25.0, 1e12)
        for (t in inputs) for (w in inputs) {
            val tilt = RotationTargets.cappedTilt(t)
            val wob = RotationTargets.cappedWobble(t, w)
            assertTrue(tilt >= 0.0 && wob >= 0.0 && tilt + wob <= 20.0 + 1e-12, "tilt $tilt wobble $wob")
        }
        assertEquals(14.0, RotationTargets.cappedTilt(14.0))
        assertEquals(6.0, RotationTargets.cappedWobble(14.0, 6.0))
    }
}
