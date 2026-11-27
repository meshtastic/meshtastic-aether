package org.meshtastic.aether.ble

import kotlinx.coroutines.flow.Flow
import org.meshtastic.sdk.RadioTransport

/** True where a radio can be reached over Bluetooth: desktop, Android, iOS, and a browser with Web Bluetooth. */
internal expect fun bleSupported(): Boolean

/** A cold scan for Meshtastic radios, or null where the platform cannot scan (the browser, see [bleChooser]). */
expect fun bleScan(): Flow<BleRadio>?

/**
 * Opens the platform's own radio chooser and returns the pick, or null when it was dismissed. Null where radios are
 * found by [bleScan] instead. A browser only opens its chooser from a user gesture. The chooser throws
 * [BleChooserException] when it cannot open or grant a radio.
 */
internal expect fun bleChooser(): (suspend () -> BleRadio?)?

/** The platform radio chooser failed: no Web Bluetooth, an insecure page, or no user gesture. */
internal class BleChooserException(message: String?, cause: Throwable) : Exception(message, cause)

/** The link to a scanned or chosen radio, or null where [bleSupported] is false. */
internal expect fun createBleTransport(radio: BleRadio): RadioTransport?

/**
 * Asks for whatever the platform needs before a scan (Android's Bluetooth permissions); true when scanning may
 * start.
 */
internal expect suspend fun ensureBlePermissions(): Boolean
