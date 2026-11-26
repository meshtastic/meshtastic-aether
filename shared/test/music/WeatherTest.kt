package org.meshtastic.ambient.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeatherTest {
    private val hour = 3_600_000L

    @Test
    fun `a flat range returns the absolute default mapping rather than NaN`() {
        val w = WeatherScaler()
        repeat(10) { w.observe(Quantity.Temperature, 20f, it * hour) }
        val v = w.scale(Quantity.Temperature, 20f)
        assertTrue(!v.isNaN())
        assertEquals((20f + 10f) / 45f, v, 1e-6f)
    }

    @Test
    fun `fewer than two samples use the absolute default range`() {
        val w = WeatherScaler()
        assertEquals(0.5f, w.scale(Quantity.Humidity, 50f), 1e-6f)
        w.observe(Quantity.Humidity, 80f, 0L)
        assertEquals(0.8f, w.scale(Quantity.Humidity, 80f), 1e-6f)
    }

    @Test
    fun `a cold season at its own range maps to the same brightness as a warm one`() {
        val winter = WeatherScaler()
        val summer = WeatherScaler()
        for (i in 0..10) {
            winter.observe(Quantity.Temperature, -5f + 1.5f * i, i * hour)
            summer.observe(Quantity.Temperature, 20f + 1.5f * i, i * hour)
        }
        assertEquals(winter.scale(Quantity.Temperature, 10f), summer.scale(Quantity.Temperature, 35f), 1e-6f)
        assertEquals(0f, winter.scale(Quantity.Temperature, -5f), 1e-6f)
        assertEquals(winter.scale(Quantity.Temperature, 2.5f), summer.scale(Quantity.Temperature, 27.5f), 1e-6f)
        assertEquals(0.5f, winter.scale(Quantity.Temperature, 2.5f), 1e-6f)
    }

    @Test
    fun `readings older than the rolling week stop stretching the range`() {
        val w = WeatherScaler()
        val day = 24 * hour
        w.observe(Quantity.Temperature, -30f, 0L)
        w.observe(Quantity.Temperature, 10f, 8 * day)
        w.observe(Quantity.Temperature, 20f, 8 * day + hour)
        assertEquals(1f, w.scale(Quantity.Temperature, 20f), 1e-6f)
        assertEquals(0f, w.scale(Quantity.Temperature, 10f), 1e-6f)
    }

    @Test
    fun `values outside the observed range clamp and non finite values give the middle`() {
        val w = WeatherScaler()
        w.observe(Quantity.Wind, 0f, 0L)
        w.observe(Quantity.Wind, 10f, hour)
        assertEquals(1f, w.scale(Quantity.Wind, 50f))
        assertEquals(0f, w.scale(Quantity.Wind, -4f))
        assertEquals(0.5f, w.scale(Quantity.Wind, Float.NaN))
    }

    @Test
    fun `non finite observations are ignored`() {
        val w = WeatherScaler()
        w.observe(Quantity.Pressure, Float.NaN, 0L)
        w.observe(Quantity.Pressure, Float.POSITIVE_INFINITY, hour)
        assertEquals((1010f - 980f) / 60f, w.scale(Quantity.Pressure, 1010f), 1e-6f)
    }
}
