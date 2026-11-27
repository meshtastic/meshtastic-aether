package org.meshtastic.aether.ui

/**
 * The glyph's geometry as fractions of its size, taken from the 1024 unit icon (`branding/icon.svg`), and its gradient
 * stop offsets from the same file. Ring values are in the ring's own frame: origin at the orb center, x along the
 * ring's long axis, before the [RING_ROTATION_DEGREES] turn.
 */
internal object GlyphGeometry {
    const val CENTER = 0.5f
    const val ORB_RADIUS = 132f / 1024f
    const val HALO_RADIUS = 336f / 1024f
    const val ORB_HIGHLIGHT_X = 470f / 1024f
    const val ORB_HIGHLIGHT_Y = 466f / 1024f
    const val ORB_GRADIENT_RADIUS = 190f / 1024f

    const val RING_ROTATION_DEGREES = -24f
    const val RING_HALF_WIDTH = 300f / 1024f
    const val RING_HALF_HEIGHT = 96f / 1024f
    const val BACK_ARC_STROKE = 30f / 1024f
    const val FRONT_ARC_STROKE = 36f / 1024f
    const val FRONT_ARC_SHADOW_STROKE = 52f / 1024f

    /**
     * Compose arc angles: degrees clockwise from 3 o'clock. The back arc passes over the top of the orb, the front arc
     * under it.
     */
    const val BACK_ARC_START = 180f
    const val BACK_ARC_SWEEP = 180f
    const val FRONT_ARC_START = 180f
    const val FRONT_ARC_SWEEP = -180f

    /** Where the halo has faded to its middle opacity (`halo`). */
    const val HALO_MID_STOP = 0.55f

    /** Where the orb's white highlight has turned light green, and where that has turned accent green (`orb`). */
    const val ORB_LIGHT_STOP = 0.3f
    const val ORB_ACCENT_STOP = 0.7f

    /** Where the front arc's gradient reaches its mid green (`ring`). */
    const val RING_MID_STOP = 0.6f

    /**
     * A node on the ring: [x] and [y] in the ring's frame, [radius] and [glowRadius] (0 for none), all fractions of
     * the size.
     */
    data class Node(val x: Float, val y: Float, val radius: Float, val glowRadius: Float = 0f)

    /** The two small nodes behind the orb, then the bright node in front. */
    val backNodes = listOf(
        Node(x = -196f / 1024f, y = -73f / 1024f, radius = 20f / 1024f),
        Node(x = 150f / 1024f, y = -83f / 1024f, radius = 14f / 1024f),
    )
    val brightNode = Node(x = 212f / 1024f, y = 68f / 1024f, radius = 30f / 1024f, glowRadius = 58f / 1024f)

    /**
     * The M mark on the orb: its width and its center's lift above the orb's center, as fractions of the orb's
     * radius.
     */
    const val MARK_WIDTH = 240f / 216f
    const val MARK_LIFT = 10f / 108f

    /** The mark's own box, the official artwork's 100 x 55 viewBox. */
    const val MARK_BOX_WIDTH = 100f
    const val MARK_BOX_HEIGHT = 55f
}
