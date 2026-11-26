package org.meshtastic.ambient.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Expected values are hand computed from each mapping's stated range, not read back from the functions. */
class TraitsTest {
    // Regression: a mapping that reacted to a missing attribute would change every note from a node that reports nothing.
    @Test
    fun `every mapping is neutral when its attribute is absent`() {
        assertEquals(0f, Traits.rssiSendDelta(null))
        assertEquals(0f, Traits.rssiBrightnessDelta(null))
        assertEquals(0.0, Traits.batteryDetuneCents(null))
        assertEquals(0, Traits.registerOffset(null, 100.0))
        assertEquals(0, Traits.registerOffset(500, null))
        assertEquals(1.0, Traits.uptimeLengthScale(null))
        assertEquals(0f, Traits.powerBrightnessDelta(null, null))
        assertEquals(1f, Traits.payloadVelocityTrim(null))
        assertEquals(4, Traits.textPhraseNotes(null))
        assertEquals(0f, Traits.channelPanOffset(null))
    }

    @Test
    fun `a weak signal is wetter and duller and a strong one drier and brighter by a tenth at the ends`() {
        assertEquals(0.1f, Traits.rssiSendDelta(-120), 1e-6f)
        assertEquals(-0.1f, Traits.rssiSendDelta(-30), 1e-6f)
        assertEquals(0f, Traits.rssiSendDelta(-75), 1e-6f)
        assertEquals(-0.1f, Traits.rssiBrightnessDelta(-120), 1e-6f)
        assertEquals(0.1f, Traits.rssiBrightnessDelta(-30), 1e-6f)
        assertEquals(0f, Traits.rssiBrightnessDelta(-75), 1e-6f)
        assertEquals(0.05f, Traits.rssiSendDelta(-97), 0.003f)
    }

    @Test
    fun `an rssi outside the range is clamped to its ends`() {
        assertEquals(0.1f, Traits.rssiSendDelta(-200), 1e-6f)
        assertEquals(-0.1f, Traits.rssiSendDelta(10), 1e-6f)
    }

    @Test
    fun `battery detune is zero from half charge up and fifteen cents at empty`() {
        for (pct in listOf(50, 80, 100, 101)) assertEquals(0.0, Traits.batteryDetuneCents(pct), "$pct")
        assertEquals(12.0, Traits.batteryDetuneCents(10), 1e-9)
        assertEquals(7.5, Traits.batteryDetuneCents(25), 1e-9)
        assertEquals(15.0, Traits.batteryDetuneCents(0), 1e-9)
        assertEquals(15.0, Traits.batteryDetuneCents(-5), 1e-9)
    }

    @Test
    fun `a twelve cent detune is a frequency ratio of 1 point 0069`() {
        assertEquals(1.006956, Traits.centsRatio(12.0), 1e-5)
        assertEquals(2.0, Traits.centsRatio(1200.0), 1e-12)
    }

    @Test
    fun `a node a hundred and fifty metres above the mean rings an octave higher and below it an octave lower`() {
        assertEquals(1, Traits.registerOffset(650, 500.0))
        assertEquals(1, Traits.registerOffset(2000, 500.0))
        assertEquals(0, Traits.registerOffset(649, 500.0))
        assertEquals(0, Traits.registerOffset(351, 500.0))
        assertEquals(-1, Traits.registerOffset(350, 500.0))
    }

    @Test
    fun `uptime lengthens a note from one times at boot to one and a half times at thirty days`() {
        assertEquals(1.0, Traits.uptimeLengthScale(0L), 1e-12)
        assertEquals(1.5, Traits.uptimeLengthScale(720L * 3600), 1e-9)
        assertEquals(1.5, Traits.uptimeLengthScale(10_000L * 3600), 1e-9)
        assertEquals(1.0527, Traits.uptimeLengthScale(3600L), 1e-4)
        assertEquals(1.2446, Traits.uptimeLengthScale(24L * 3600), 1e-4)
    }

    @Test
    fun `external power and a full cell brighten a note by a tenth and an empty cell dims it by a tenth`() {
        assertEquals(0.1f, Traits.powerBrightnessDelta(null, 101), 1e-6f)
        assertEquals(0.1f, Traits.powerBrightnessDelta(4.2f, 60), 1e-6f)
        assertEquals(-0.1f, Traits.powerBrightnessDelta(3.3f, 5), 1e-6f)
        assertEquals(0f, Traits.powerBrightnessDelta(3.75f, 50), 1e-6f)
        assertEquals(0.1f, Traits.powerBrightnessDelta(5.0f, null), 1e-6f)
        assertEquals(0f, Traits.powerBrightnessDelta(0f, 50), 1e-6f)
        assertEquals(0f, Traits.powerBrightnessDelta(Float.NaN, null), 1e-6f)
    }

    @Test
    fun `payload size trims velocity from 0 point 9 for empty to 1 point 1 for a full packet`() {
        assertEquals(0.9f, Traits.payloadVelocityTrim(0), 1e-6f)
        assertEquals(1.1f, Traits.payloadVelocityTrim(237), 1e-6f)
        assertEquals(1.0f, Traits.payloadVelocityTrim(118), 2e-3f)
        assertEquals(1.1f, Traits.payloadVelocityTrim(5000), 1e-6f)
        assertEquals(0.9f, Traits.payloadVelocityTrim(-3), 1e-6f)
    }

    @Test
    fun `a text phrase runs three notes under forty characters then one more per sixty up to six`() {
        assertEquals(3, Traits.textPhraseNotes(0))
        assertEquals(3, Traits.textPhraseNotes(39))
        assertEquals(4, Traits.textPhraseNotes(40))
        assertEquals(4, Traits.textPhraseNotes(99))
        assertEquals(5, Traits.textPhraseNotes(100))
        assertEquals(5, Traits.textPhraseNotes(159))
        assertEquals(6, Traits.textPhraseNotes(160))
        assertEquals(6, Traits.textPhraseNotes(10_000))
    }

    @Test
    fun `channels alternate east and west by eight hundredths per pair and the primary channel stays centred`() {
        assertEquals(0f, Traits.channelPanOffset(0))
        assertEquals(0.08f, Traits.channelPanOffset(1), 1e-6f)
        assertEquals(-0.08f, Traits.channelPanOffset(2), 1e-6f)
        assertEquals(0.16f, Traits.channelPanOffset(3), 1e-6f)
        assertEquals(-0.16f, Traits.channelPanOffset(4), 1e-6f)
        assertEquals(0.32f, Traits.channelPanOffset(7), 1e-6f)
        assertTrue((0..7).all { kotlin.math.abs(Traits.channelPanOffset(it)) <= 0.32f })
    }
}
