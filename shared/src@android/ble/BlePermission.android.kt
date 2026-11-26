package org.meshtastic.ambient.ble

/** Set by the host activity: asks for BLUETOOTH_SCAN and BLUETOOTH_CONNECT and returns whether both were granted. */
object AndroidBlePermissions {
    var request: (suspend () -> Boolean)? = null
}

internal actual suspend fun ensureBlePermissions(): Boolean = AndroidBlePermissions.request?.invoke() ?: true
