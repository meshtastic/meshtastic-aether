package org.meshtastic.ambient.visual

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeColorTest {
    // Regression: the scene must show the color every other Meshtastic client shows for the same node.
    // !0a40c8f0 is hilltop; its low 24 bits 0x40c8f0 are (64, 200, 240), luma 0.610 so it needs no lift.
    @Test
    fun `a bright node shows its low 24 bits as RGB`() {
        assertEquals(Rgb(64, 200, 240), nodeRgb(0x0A40C8F0L))
    }

    @Test
    fun `the top byte of the node number never changes the color`() {
        assertEquals(nodeRgb(0x0040C8F0L), nodeRgb(0xFF40C8F0L))
    }

    // Regression: a node whose bits are near black would be invisible on the black scene.
    // Black lifts by (0.45 - 0) / (1 - 0) = 0.45 of the way to white: 0.45 * 255 = 114.75, rounded to 115.
    @Test
    fun `black is lifted to the minimum grey and not left invisible`() {
        assertEquals(Rgb(115, 115, 115), nodeRgb(0L))
    }

    // (0, 0, 16): luma 1.824 / 255 = 0.007153, lift 0.446039; r and g 0.446039 * 255 = 113.74 -> 114,
    // b 16 + 0.446039 * 239 = 122.60 -> 123. The blue tint survives the lift.
    @Test
    fun `a dark tint keeps its hue while it is lifted`() {
        val lifted = nodeRgb(0x000010L)
        assertEquals(Rgb(114, 114, 123), lifted)
        assertTrue(lifted.b > lifted.r)
        assertTrue(lifted.luma >= MIN_NODE_LUMA - 0.005)
    }

    @Test
    fun `white is unchanged and no lift exceeds a channel`() {
        assertEquals(Rgb(255, 255, 255), nodeRgb(0xFFFFFFL))
        assertEquals(Rgb(255, 255, 255), Rgb(250, 252, 255).towardWhite(1.0))
    }
}
