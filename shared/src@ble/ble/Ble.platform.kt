package org.meshtastic.ambient.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.transport.ble.BleScanner
import org.meshtastic.sdk.transport.ble.BleTransport
import org.meshtastic.sdk.transport.ble.DiscoveredRadio

actual fun bleSupported(): Boolean = true

actual fun bleScan(): Flow<BleRadio>? = BleScanner.scan().map { BleRadio(it.name, it.rssi, it.address, it) }

actual fun createBleTransport(radio: BleRadio): RadioTransport? = BleTransport(radio.handle as DiscoveredRadio)
