package org.meshtastic.aether.ble

import kotlinx.coroutines.flow.Flow
import org.meshtastic.sdk.RadioTransport

/** A radio seen by a scan or picked in a chooser. [handle] is the platform object the transport is built from. */
class BleRadio(val name: String?, val rssi: Int?, val address: String, val handle: Any) {
    override fun toString(): String = "BleRadio(name=$name, rssi=$rssi, address=$address)"
}

/** True where a radio can be reached over Bluetooth: desktop, Android, iOS, and a browser with Web Bluetooth. */
internal expect fun bleSupported(): Boolean

/** A cold scan for Meshtastic radios, or null where the platform cannot scan (the browser, see [bleChooser]). */
expect fun bleScan(): Flow<BleRadio>?

/**
 * Opens the platform's own radio chooser and returns the pick, or null when it was dismissed. Null where radios are
 * found by [bleScan] instead. A browser only opens its chooser from a user gesture.
 */
internal expect fun bleChooser(): (suspend () -> BleRadio?)?

/** The link to a scanned or chosen radio, or null where [bleSupported] is false. */
internal expect fun createBleTransport(radio: BleRadio): RadioTransport?

/** Asks for whatever the platform needs before a scan (Android's Bluetooth permissions); true when scanning may start. */
internal expect suspend fun ensureBlePermissions(): Boolean
