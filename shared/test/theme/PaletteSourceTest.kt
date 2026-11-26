package org.meshtastic.aether.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hex values are copied from the client design standards v1.6 (`design/standards/meshtastic_design_standards_latest.md`). */
class PaletteSourceTest {
    /** Section 7.1 to 7.6, as hex without the alpha byte. */
    private val standardsPalette: Set<Int> = setOf(
        // 7.1 brand
        0x2C2D3C, 0x67EA94,
        // 7.2 neutral scale
        0x0F1017, 0x1A1B26, 0x2C2D3C, 0x3D3E50, 0x555668, 0x6E7082, 0x9496A6, 0xB8BAC8, 0xD5D6E0, 0xECEDF3, 0xF5F6FA,
        // 7.3 neutral variant scale
        0x1D1E2B, 0x303245, 0x444660, 0x5C5E78, 0x767892, 0x9698B0, 0xBDBFCF, 0xDADBE7, 0xEDEEF6, 0xF6F7FC,
        // 7.4 green scale
        0x002E13, 0x003D1A, 0x005C2E, 0x2D8F52, 0x3FB86D, 0x67EA94, 0x8FF0B2, 0xB5F5CE, 0xCCFADD, 0xE5FCEE, 0xF0FEF5,
        // 7.5 accent blue scale
        0x001849, 0x002366, 0x1A3F8C, 0x2855A8, 0x5C6BC0, 0x7B8AD0, 0x9BA8E0, 0xB0BFF0, 0xD0D8F5, 0xE0E3F8, 0xE8EAF6,
        // 7.6 error scale
        0x410002, 0x690005, 0x93000A, 0xBA1A1A, 0xE05252, 0xFF897D, 0xFFB4AB, 0xFFDAD6, 0xFDEAEA,
    )

    /** Section 2 allows pure black in dark mode, and 8.3 maps scrim to it. */
    private val black = 0x000000

    private fun Color.rgb(): Int = toArgb() and 0xFFFFFF

    private fun assertOnPalette(name: String, color: Color, allowBlack: Boolean = false) {
        val ok = color.rgb() in standardsPalette || (allowBlack && color.rgb() == black)
        assertTrue(ok, "$name is ${color.rgb().toString(16)}, not a design standards palette value")
    }

    // Regression: an invented background such as 1F2030 or 07070B drifts the scene off the brand.
    @Test
    fun `every look draws its colors from the standards palette`() {
        for (choice in PaletteChoice.entries) {
            val p = choice.palette
            val allowBlack = choice == PaletteChoice.OledNight
            assertOnPalette("${choice.name} backgroundTop", p.backgroundTop, allowBlack)
            assertOnPalette("${choice.name} backgroundBottom", p.backgroundBottom, allowBlack)
            assertOnPalette("${choice.name} haze", p.haze)
            assertOnPalette("${choice.name} edge", p.edge)
            assertOnPalette("${choice.name} anchor", p.anchor)
            assertOnPalette("${choice.name} text", p.text)
        }
    }

    @Test
    fun `the standard look fades from Neutral 900 at the top to Neutral 950`() {
        assertEquals(0x1A1B26, PaletteChoice.Standard.palette.backgroundTop.rgb())
        assertEquals(0x0F1017, PaletteChoice.Standard.palette.backgroundBottom.rgb())
    }

    @Test
    fun `the OLED night look is Neutral 950 over true black`() {
        assertEquals(0x0F1017, PaletteChoice.OledNight.palette.backgroundTop.rgb())
        assertEquals(black, PaletteChoice.OledNight.palette.backgroundBottom.rgb())
    }

    // Regression: a scheme role changed by hand no longer follows section 8.3.
    @Test
    fun `the M3 dark scheme matches the standards section 8 dot 3 role by role`() {
        val s = ambientColorScheme()
        val expected = mapOf(
            "primary" to (s.primary to 0x67EA94),
            "onPrimary" to (s.onPrimary to 0x0F1017),
            "primaryContainer" to (s.primaryContainer to 0x2D8F52),
            "onPrimaryContainer" to (s.onPrimaryContainer to 0xB5F5CE),
            "secondary" to (s.secondary to 0xB8BAC8),
            "onSecondary" to (s.onSecondary to 0x1A1B26),
            "secondaryContainer" to (s.secondaryContainer to 0x3D3E50),
            "onSecondaryContainer" to (s.onSecondaryContainer to 0xD5D6E0),
            "tertiary" to (s.tertiary to 0xB0BFF0),
            "onTertiary" to (s.onTertiary to 0x001849),
            "tertiaryContainer" to (s.tertiaryContainer to 0x2855A8),
            "onTertiaryContainer" to (s.onTertiaryContainer to 0xE8EAF6),
            "error" to (s.error to 0xFFB4AB),
            "onError" to (s.onError to 0x690005),
            "errorContainer" to (s.errorContainer to 0x93000A),
            "onErrorContainer" to (s.onErrorContainer to 0xFDEAEA),
            "background" to (s.background to 0x1A1B26),
            "onBackground" to (s.onBackground to 0xECEDF3),
            "surface" to (s.surface to 0x1A1B26),
            "onSurface" to (s.onSurface to 0xECEDF3),
            "surfaceVariant" to (s.surfaceVariant to 0x444660),
            "onSurfaceVariant" to (s.onSurfaceVariant to 0xBDBFCF),
            "outline" to (s.outline to 0x767892),
            "outlineVariant" to (s.outlineVariant to 0x444660),
            "inverseSurface" to (s.inverseSurface to 0xECEDF3),
            "inverseOnSurface" to (s.inverseOnSurface to 0x2C2D3C),
            "inversePrimary" to (s.inversePrimary to 0x2D8F52),
            "surfaceTint" to (s.surfaceTint to 0x67EA94),
            "scrim" to (s.scrim to black),
            "surfaceDim" to (s.surfaceDim to 0x0F1017),
            "surfaceBright" to (s.surfaceBright to 0x3D3E50),
            "surfaceContainerLowest" to (s.surfaceContainerLowest to 0x0F1017),
            "surfaceContainerLow" to (s.surfaceContainerLow to 0x1A1B26),
            "surfaceContainer" to (s.surfaceContainer to 0x242533),
            "surfaceContainerHigh" to (s.surfaceContainerHigh to 0x2C2D3C),
            "surfaceContainerHighest" to (s.surfaceContainerHighest to 0x3D3E50),
        )
        for ((role, pair) in expected) assertEquals(pair.second, pair.first.rgb(), "scheme role $role")
    }

    // 8.3 lists surfaceContainer as 242533, interpolated between Neutral 900 and 800, so it is the one non-palette value.
    @Test
    fun `every M3 scheme color except the interpolated surface container is a palette value`() {
        val s = ambientColorScheme()
        val colors = mapOf(
            "primary" to s.primary, "onPrimary" to s.onPrimary, "primaryContainer" to s.primaryContainer,
            "onPrimaryContainer" to s.onPrimaryContainer, "secondary" to s.secondary, "onSecondary" to s.onSecondary,
            "secondaryContainer" to s.secondaryContainer, "onSecondaryContainer" to s.onSecondaryContainer,
            "tertiary" to s.tertiary, "onTertiary" to s.onTertiary, "tertiaryContainer" to s.tertiaryContainer,
            "onTertiaryContainer" to s.onTertiaryContainer, "error" to s.error, "onError" to s.onError,
            "errorContainer" to s.errorContainer, "onErrorContainer" to s.onErrorContainer, "background" to s.background,
            "onBackground" to s.onBackground, "surface" to s.surface, "onSurface" to s.onSurface,
            "surfaceVariant" to s.surfaceVariant, "onSurfaceVariant" to s.onSurfaceVariant, "outline" to s.outline,
            "outlineVariant" to s.outlineVariant, "inverseSurface" to s.inverseSurface, "inverseOnSurface" to s.inverseOnSurface,
            "inversePrimary" to s.inversePrimary, "surfaceTint" to s.surfaceTint, "surfaceDim" to s.surfaceDim,
            "surfaceBright" to s.surfaceBright, "surfaceContainerLowest" to s.surfaceContainerLowest,
            "surfaceContainerLow" to s.surfaceContainerLow, "surfaceContainerHigh" to s.surfaceContainerHigh,
            "surfaceContainerHighest" to s.surfaceContainerHighest,
        )
        for ((role, color) in colors) assertOnPalette("scheme $role", color)
        assertEquals(0x242533, s.surfaceContainer.rgb())
        assertEquals(black, s.scrim.rgb())
    }
}
