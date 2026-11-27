package org.meshtastic.aether.visual

// The HSV and HSL hexcone: six 60-degree sectors of hue, red at 0, then yellow, green, cyan, blue and magenta.
internal const val HUE_SECTORS = 6.0
internal const val SECTOR_DEG = 60.0
internal const val FULL_TURN_DEG = 360.0
internal const val RED_SECTOR = 0
internal const val YELLOW_SECTOR = 1
internal const val GREEN_SECTOR = 2
internal const val CYAN_SECTOR = 3
internal const val BLUE_SECTOR = 4

/** A channel spread below this is grey, with no hue. */
internal const val GREY_EPSILON = 1e-12

/** The largest 8-bit channel value. */
internal const val CHANNEL_MAX = 255
