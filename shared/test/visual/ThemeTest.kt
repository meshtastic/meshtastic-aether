package org.meshtastic.ambient.visual

import androidx.compose.ui.graphics.Color
import org.meshtastic.ambient.theme.Brand
import org.meshtastic.ambient.theme.PaletteChoice
import org.meshtastic.ambient.theme.ambientColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeTest {
    // Expected hex values are design standards v1.4 section 8.3, the dark role mapping.
    @Test
    fun `the Material scheme uses the standards dark mapping and not the purple defaults`() {
        val s = ambientColorScheme()
        assertEquals(Color(0xFF67EA94), s.primary)
        assertEquals(Color(0xFF0F1017), s.onPrimary)
        assertEquals(Color(0xFF1A1B26), s.surface)
        assertEquals(Color(0xFFFFB4AB), s.error)
        assertEquals(Color(0xFFECEDF3), s.onSurface)
    }

    // Regression: the night dim multiplied an already dark picture to black on a projector (review: Projector floor 0.8).
    @Test
    fun `the projector never dims below eighty percent and OLED night dims deepest`() {
        for (m in 0 until 1440) {
            assertTrue(PaletteChoice.Projector.palette.dim(m) >= 0.8 - 1e-9)
            assertTrue(PaletteChoice.OledNight.palette.dim(m) <= PaletteChoice.Standard.palette.dim(m) + 1e-9)
        }
        assertEquals(1.0, PaletteChoice.Standard.palette.dim(14 * 60), 1e-9)
        assertEquals(0.8, PaletteChoice.Projector.palette.dim(2 * 60), 1e-9)
        assertEquals(0.45, PaletteChoice.Standard.palette.dim(2 * 60), 1e-9)
    }

    @Test
    fun `OLED night has a true black bottom and the projector a brighter top than standard`() {
        assertEquals(Color(0xFF000000), PaletteChoice.OledNight.palette.backgroundBottom)
        assertTrue(PaletteChoice.Projector.palette.backgroundTop.toRgb().relativeLuminance > PaletteChoice.Standard.palette.backgroundTop.toRgb().relativeLuminance)
    }

    // Design standards 9: text and the accent must reach 4.5:1 on the background they sit on.
    @Test
    fun `palette text and anchor contrast with every background`() {
        for (c in PaletteChoice.entries) {
            val p = c.palette
            for (bg in listOf(p.backgroundTop, p.backgroundBottom)) {
                assertTrue(contrastRatio(p.text.toRgb(), bg.toRgb()) >= 4.5, "$c text")
                assertTrue(contrastRatio(p.anchor.toRgb(), bg.toRgb()) >= 4.5, "$c anchor")
            }
        }
    }

    @Test
    fun `the standard background is built from the brand neutrals`() {
        val p = PaletteChoice.Standard.palette
        assertEquals(Brand.Neutral950, p.backgroundBottom)
        assertEquals(Brand.Accent, p.anchor)
    }

    // Time-of-day offset is a sine zero at 06:00 and 18:00, plus 12 at noon (the brief: a small offset).
    @Test
    fun `the time of day hue offset is small and zero at six and eighteen`() {
        assertEquals(0.0, timeHueOffset(360), 1e-9)
        assertEquals(0.0, timeHueOffset(1080), 1e-9)
        assertEquals(12.0, timeHueOffset(720), 1e-9)
        assertTrue((0 until 1440).all { kotlin.math.abs(timeHueOffset(it)) <= 12.0 + 1e-9 })
    }

    // Regression: a hue shift must rotate the hue without changing brightness; zero saturation gives grey.
    @Test
    fun `shifting hue by 120 degrees moves red to green and zero saturation is grey`() {
        val g = shiftColor(Color(1f, 0f, 0f), 120.0, 1.0, 1.0)
        assertEquals(0f, g.red, 1e-5f)
        assertEquals(1f, g.green, 1e-5f)
        assertEquals(0f, g.blue, 1e-5f)
        val grey = shiftColor(Color(0.2f, 0.4f, 0.8f), 0.0, 0.0, 1.0)
        assertEquals(grey.red, grey.green, 1e-5f)
        assertEquals(grey.green, grey.blue, 1e-5f)
        assertEquals(0.8f, grey.red, 1e-5f)
    }

    // Regression: the flash burned to a white blob at alpha 1.0; the brief caps orb alpha near 0.7 and holds idle at 0.2 or more.
    @Test
    fun `orb alpha is capped near 0 point 7 and never invisible at the idle floor`() {
        assertEquals(0.7f, orbCoreAlpha(1.0), 1e-6f)
        assertTrue(orbHaloAlpha(1.0) <= 0.35f + 1e-6f)
        assertTrue(orbCoreAlpha(SceneModel.IDLE_FLOOR) >= 0.2f - 1e-6f)
        assertTrue(orbHaloAlpha(SceneModel.IDLE_FLOOR) > 0f)
        assertTrue(orbHaloAlpha(1.0) < orbCoreAlpha(1.0))
    }
}
