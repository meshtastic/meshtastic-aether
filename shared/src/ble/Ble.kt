package org.meshtastic.ambient.ble

import kotlinx.coroutines.flow.Flow
import org.meshtastic.sdk.RadioTransport

/** A radio seen by a scan. [handle] is the platform scan result the transport is built from. */
class BleRadio(val name: String?, val rssi: Int?, val address: String, val handle: Any) {
    override fun toString(): String = "BleRadio(name=$name, rssi=$rssi, address=$address)"
}

/** True where the SDK has a Bluetooth transport (desktop, Android, iOS; not the browser). */
internal expect fun bleSupported(): Boolean

/** A cold scan for Meshtastic radios, or null where [bleSupported] is false. */
expect fun bleScan(): Flow<BleRadio>?

/** The link to a scanned radio, or null where [bleSupported] is false. */
internal expect fun createBleTransport(radio: BleRadio): RadioTransport?

/** Asks for whatever the platform needs before a scan (Android's Bluetooth permissions); true when scanning may start. */
internal expect suspend fun ensureBlePermissions(): Boolean
