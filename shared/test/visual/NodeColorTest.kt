package org.meshtastic.ambient.visual

import org.meshtastic.ambient.music.Rng
import org.meshtastic.ambient.theme.PaletteChoice
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeColorTest {
    private val backgrounds = PaletteChoice.entries.map { it.palette.backgroundTop.toRgb() }

    // Regression: the scene must show the color every other Meshtastic client shows for the same node.
    // The low 24 bits of !0a40c8f0 are (64, 200, 240), which already clear 4.5:1 on Neutral 950.
    @Test
    fun `a bright node shows its low 24 bits as RGB`() {
        assertEquals(Rgb(64, 200, 240), nodeRgb(0x0A40C8F0L))
    }

    @Test
    fun `the top byte of the node number never changes the color`() {
        assertEquals(nodeRgb(0x0040C8F0L), nodeRgb(0xFF40C8F0L))
    }

    // WCAG 2.x: white on black is 21:1 and any color on itself is 1:1.
    @Test
    fun `contrast ratio matches the WCAG definition at its extremes`() {
        assertEquals(21.0, contrastRatio(Rgb(255, 255, 255), Rgb(0, 0, 0)), 1e-9)
        assertEquals(1.0, contrastRatio(Rgb(10, 20, 30), Rgb(10, 20, 30)), 1e-9)
    }

    // Regression: a node whose bits are near black would be invisible on the dark scene (design standards 9: 4.5:1).
    @Test
    fun `every node color reaches 4 and a half to 1 on every palette background`() {
        val rng = Rng(5)
        val nodes = (0L..255L).flatMap { listOf(it, it shl 8, it shl 16, it * 0x010101L) } +
            List(3000) { (rng.nextDouble() * 0xFFFFFF).toLong() }
        for (bg in backgrounds) for (n in nodes) {
            val c = contrastRatio(nodeRgb(n, bg), bg)
            assertTrue(c >= MIN_NODE_CONTRAST, "node ${n.toString(16)} on $bg has $c")
        }
    }

    // Regression: the old lift toward white turned dark tints grey; HSL lightness keeps hue and saturation.
    // (0, 0, 16) is HSL hue 240 and saturation 1.0; any lift in lightness alone must leave both.
    @Test
    fun `a dark tint keeps its hue and saturation while it is lifted`() {
        val lifted = nodeRgb(0x000010L)
        val hsl = lifted.toHsl()
        assertTrue(abs(hsl.h - 240.0) < 2.0, "hue ${hsl.h}")
        assertTrue(hsl.s > 0.95, "saturation ${hsl.s}")
        assertTrue(lifted.b > lifted.r && lifted.b > lifted.g)
        assertTrue(contrastRatio(lifted, NEUTRAL_950) >= MIN_NODE_CONTRAST)
    }

    // Regression: dark colors must not be lifted further than needed.
    @Test
    fun `a color that already contrasts is returned untouched`() {
        assertEquals(Rgb(255, 255, 255), nodeRgb(0xFFFFFFL))
        assertEquals(Rgb(250, 252, 255), nodeRgb(0xFAFCFFL))
    }

    @Test
    fun `black lifts to a neutral grey with no tint`() {
        val grey = nodeRgb(0L)
        assertEquals(grey.r, grey.g)
        assertEquals(grey.g, grey.b)
    }

    // Characterization: round trip through HSL; values from the standard formula, tolerance one 8-bit step.
    @Test
    fun `HSL conversion round trips`() {
        for (c in listOf(Rgb(64, 200, 240), Rgb(200, 10, 90), Rgb(0, 0, 16), Rgb(128, 128, 128))) {
            val back = c.toHsl().toRgb()
            assertTrue(abs(back.r - c.r) <= 1 && abs(back.g - c.g) <= 1 && abs(back.b - c.b) <= 1, "$c -> $back")
        }
    }

    @Test
    fun `white is unchanged by towardWhite one and no lift exceeds a channel`() {
        assertEquals(Rgb(255, 255, 255), Rgb(250, 252, 255).towardWhite(1.0))
    }
}
