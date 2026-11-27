package org.meshtastic.aether.serial

/**
 * A serial port a radio may be on. [path] is what a saved link stores and reopens (a stable `/dev/serial/by-id` path
 * where Linux has one), [label] is what the list shows, and [likelyMeshtastic] marks a USB id that Meshtastic boards
 * use.
 */
data class SerialRadio(val path: String, val label: String, val likelyMeshtastic: Boolean)
