package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.meshtastic.aether.ble.BleRadio
import org.meshtastic.aether.ble.RadioList
import org.meshtastic.aether.ble.bleScan
import org.meshtastic.aether.ble.ensureBlePermissions
import org.meshtastic.aether.log.Log

/** Scan for Meshtastic radios over Bluetooth and pick one; the scan stops when the section leaves the screen. */
@Composable
internal fun BluetoothSection(onPick: (BleRadio) -> Unit) {
    var scanning by remember { mutableStateOf(false) }
    var radios by remember { mutableStateOf(emptyList<BleRadio>()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        error = null
        try {
            if (!ensureBlePermissions()) {
                error = "Bluetooth permission was not granted."
                Log.w("ble", "scan not started: permission not granted")
                scanning = false
                return@LaunchedEffect
            }
            val flow = bleScan()
            if (flow == null) {
                error = "Bluetooth is not available on this device."
                scanning = false
                return@LaunchedEffect
            }
            val list = RadioList()
            Log.i("ble", "scan started")
            coroutineScope {
                launch {
                    while (true) {
                        delay(REFRESH_MILLIS)
                        radios = list.visible(nowMillis())
                    }
                }
                flow.collect { radio ->
                    val now = nowMillis()
                    if (list.onSeen(radio, now)) Log.i("ble", "found ${radio.name ?: "unnamed"} ${radio.address} rssi=${radio.rssi}")
                    radios = list.visible(now)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("ble", "scan failed", e)
            error = e.message ?: e::class.simpleName
            scanning = false
        } finally {
            Log.i("ble", "scan stopped")
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ scanning = !scanning }, Modifier.fillMaxWidth().heightIn(min = MinTouch)) { Text(if (scanning) "Stop scanning" else "Scan for Bluetooth radios") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (scanning && radios.isEmpty()) Text("Looking for radios...", style = MaterialTheme.typography.bodySmall)
        for (radio in radios) {
            OutlinedButton({ onPick(radio) }, Modifier.fillMaxWidth().heightIn(min = MinTouch)) {
                Text("${radio.name ?: "Unnamed radio"}  ${radio.rssi?.let { "$it dBm" } ?: ""}".trim())
            }
        }
    }
}

private const val REFRESH_MILLIS = 2_000L
