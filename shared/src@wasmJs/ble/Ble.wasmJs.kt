@file:OptIn(ExperimentalWasmJsInterop::class)

package org.meshtastic.aether.ble

import kotlinx.coroutines.flow.Flow
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.transport.ble.requestBleTransport
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsException

@JsFun("() => typeof navigator !== 'undefined' && 'bluetooth' in navigator")
private external fun hasWebBluetooth(): Boolean

/** True in a browser with Web Bluetooth (Chrome and Edge, on a secure page). */
internal actual fun bleSupported(): Boolean = hasWebBluetooth()

/** A browser cannot scan: the person picks a radio in the browser's own chooser, see [bleChooser]. */
actual fun bleScan(): Flow<BleRadio>? = null

internal actual fun bleChooser(): (suspend () -> BleRadio?)? = {
    val transport = try {
        requestBleTransport()
    } catch (e: JsException) {
        throw BleChooserException(e.message, e) // the browser refused: insecure page or no user gesture
    } catch (e: IllegalStateException) {
        throw BleChooserException(e.message, e) // Kable: this browser has no Web Bluetooth
    }
    transport?.let { BleRadio(CHOSEN_NAME, null, it.identity.raw, it) }
}

internal actual fun createBleTransport(radio: BleRadio): RadioTransport? = radio.handle as? RadioTransport

internal actual suspend fun ensureBlePermissions(): Boolean = true

/** The chooser grants a device without handing back its advertised name. */
private const val CHOSEN_NAME = "Bluetooth radio"
