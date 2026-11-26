package org.meshtastic.aether.ble

import kotlinx.coroutines.flow.Flow
import org.meshtastic.sdk.RadioTransport

internal actual fun bleSupported(): Boolean = false

actual fun bleScan(): Flow<BleRadio>? = null

internal actual fun createBleTransport(radio: BleRadio): RadioTransport? = null

internal actual suspend fun ensureBlePermissions(): Boolean = false
