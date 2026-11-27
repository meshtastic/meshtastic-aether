package org.meshtastic.aether.serial

import org.meshtastic.sdk.RadioTransport

/** True where the SDK has a serial transport (desktop and Android; not iOS or the browser). */
internal expect fun serialSupported(): Boolean

/** The serial ports present now; asks for USB permission first where the platform needs it (Android). */
internal expect suspend fun listSerialPorts(): List<SerialRadio>

/** The link to the port at [path], or null where [serialSupported] is false. */
internal expect fun createSerialTransport(path: String): RadioTransport?

private val BY_ID = Regex("""^usb-(.+)_[^_]+-if\d+(?:-port\d+)?$""")

/**
 * A readable name from a Linux `/dev/serial/by-id` path, which udev builds from the USB vendor and product strings:
 * `usb-RAKwireless_WisCore_RAK4631_Board_0123456789ABCDEF-if00` reads `RAKwireless WisCore RAK4631 Board`. Many boards
 * report the same generic USB product ("TinyUSB Serial"), so this is the name that tells them apart; null for any
 * other path.
 */
internal fun byIdLabel(path: String): String? =
    BY_ID.matchEntire(path.substringAfterLast('/'))?.groupValues?.get(1)?.replace('_', ' ')?.takeIf { it.isNotBlank() }
