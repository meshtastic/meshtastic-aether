package org.meshtastic.ambient.mood

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParamValuesTest {
    @Test
    fun `neutral parameters are today's constants`() {
        val p = ParamValues.NEUTRAL
        assertEquals(600.0, p.rotationPeriodSeconds)
        assertEquals(1, p.rotationDirection)
        assertEquals(0.0, p.tiltDegrees)
        assertEquals(3.0, p.wobbleDegrees)
        assertEquals(0.0, p.directionStrength)
        assertEquals(1.0, p.phraseSpeed)
        assertEquals(0.0, p.modeLean)
        assertEquals(0.0, p.scalePentatonicBias)
    }

    @Test
    fun `describe lists every parameter with its driver or no data`() {
        val rows = ParamValues.NEUTRAL.describe()
        assertEquals(
            listOf("turn time", "rotation direction", "tilt", "wobble", "phrase speed", "mode lean", "pentatonic bias"),
            rows.map { it.name },
        )
        assertTrue(rows.all { it.driver == null })
        val driven = ParamValues(drivers = ParamDrivers(rotationSpeed = "activity")).describe()
        assertEquals("activity", driven.first().driver)
        assertNull(driven.last().driver)
    }

    @Test
    fun `describe formats values as plain text`() {
        val rows = ParamValues(
            rotationPeriodSeconds = 424.26, rotationDirection = -1, tiltDegrees = 9.05, wobbleDegrees = 3.5,
            phraseSpeed = 0.75, modeLean = -0.456, scalePentatonicBias = 0.0,
        ).describe().associateBy { it.name }
        assertEquals("424", rows.getValue("turn time").value)
        assertEquals("s", rows.getValue("turn time").unit)
        assertEquals("target counter-clockwise", rows.getValue("rotation direction").value)
        assertEquals("9.1", rows.getValue("tilt").value)
        assertEquals("3.5", rows.getValue("wobble").value)
        assertEquals("0.75", rows.getValue("phrase speed").value)
        assertEquals("-0.46", rows.getValue("mode lean").value)
        assertEquals("dark", rows.getValue("mode lean").unit)
        assertEquals("", rows.getValue("pentatonic bias").unit)
        assertEquals("0.00", rows.getValue("pentatonic bias").value)
    }

    @Test
    fun `a value that rounds to zero is not shown negative`() {
        assertEquals("0.00", ParamValues(modeLean = -0.001).describe().first { it.name == "mode lean" }.value)
    }

    @Test
    fun `a parameter with no data says it is holding its value and why`() {
        val rows = ParamValues.NEUTRAL.describe().associateBy { it.name }
        assertEquals("holding 3.0 deg - no wind reports", rows.getValue("wobble").driverText)
        assertEquals("holding 600 s - no activity data", rows.getValue("turn time").driverText)
        assertEquals("holding 1.00 x - no activity data", rows.getValue("phrase speed").driverText)
    }

    @Test
    fun `a driven parameter shows its driver with that signal's own value`() {
        val p = ParamValues(
            drivers = ParamDrivers(
                rotationSpeed = "activity", wobble = "senders", modeLean = "link quality and warmth",
                readings = mapOf("turn time" to "0.62", "wobble" to "0.40", "mode lean" to "0.71 and 0.30"),
            ),
        )
        val rows = p.describe().associateBy { it.name }
        assertEquals("activity 0.62", rows.getValue("turn time").driverText)
        assertEquals("senders 0.40 - no wind reports", rows.getValue("wobble").driverText)
        assertEquals("link quality and warmth 0.71 and 0.30", rows.getValue("mode lean").driverText)
    }

    @Test
    fun `the direction row shows the actual turning direction and names the target when they differ`() {
        val p = ParamValues(rotationDirection = -1, drivers = ParamDrivers(rotationDirection = "pressure trend", readings = mapOf("rotation direction" to "-0.42")))
        val turning = p.describe(turningDirection = 1).first { it.name == "rotation direction" }
        assertEquals("clockwise", turning.value)
        assertEquals("pressure trend -0.42 - target counter-clockwise", turning.driverText)
        val settled = p.describe(turningDirection = -1).first { it.name == "rotation direction" }
        assertEquals("counter-clockwise", settled.value)
        assertEquals("pressure trend -0.42", settled.driverText)
        assertEquals("target counter-clockwise", p.describe().first { it.name == "rotation direction" }.value)
    }
}
