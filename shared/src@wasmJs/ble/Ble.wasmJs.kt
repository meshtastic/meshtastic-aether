package org.meshtastic.ambient.ble

import kotlinx.coroutines.flow.Flow
import org.meshtastic.sdk.RadioTransport

actual fun bleSupported(): Boolean = false

actual fun bleScan(): Flow<BleRadio>? = null

actual fun createBleTransport(radio: BleRadio): RadioTransport? = null

actual suspend fun ensureBlePermissions(): Boolean = false
