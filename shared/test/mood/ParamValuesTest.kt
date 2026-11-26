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
        assertEquals(8.0, p.wobbleDegrees)
        assertEquals(1.0, p.phraseSpeed)
        assertEquals(0.0, p.modeLean)
        assertEquals(0.0, p.scalePentatonicBias)
    }

    @Test
    fun `describe lists every parameter with its driver or no data`() {
        val rows = ParamValues.NEUTRAL.describe()
        assertEquals(
            listOf("rotation speed", "rotation direction", "tilt", "wobble", "phrase speed", "mode lean", "pentatonic bias"),
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
        assertEquals("424", rows.getValue("rotation speed").value)
        assertEquals("s per turn", rows.getValue("rotation speed").unit)
        assertEquals("counter-clockwise", rows.getValue("rotation direction").value)
        assertEquals("9.1", rows.getValue("tilt").value)
        assertEquals("3.5", rows.getValue("wobble").value)
        assertEquals("0.75", rows.getValue("phrase speed").value)
        assertEquals("-0.46", rows.getValue("mode lean").value)
        assertEquals("0.00", rows.getValue("pentatonic bias").value)
    }

    @Test
    fun `a value that rounds to zero is not shown negative`() {
        assertEquals("0.00", ParamValues(modeLean = -0.001).describe().first { it.name == "mode lean" }.value)
    }
}
