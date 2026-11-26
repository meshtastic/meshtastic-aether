@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
internal fun BluetoothSection(onPick: (BleRadio) -> Unit, modifier: Modifier = Modifier, onInteract: () -> Unit = {}) {
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

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            {
                onInteract()
                scanning = !scanning
            },
            Modifier.fillMaxWidth().heightIn(min = MinTouch),
        ) { Text(if (scanning) "Stop scanning" else "Scan for Bluetooth radios") }
        error?.let { ErrorSurface(it) }
        if (scanning && radios.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadingIndicator(Modifier.size(LoadingSize))
                Text("Looking for radios...", style = MaterialTheme.typography.bodyLarge)
            }
        }
        for (radio in radios) RadioRow(radio, onPick)
    }
}

private val LoadingSize = 48.dp
private val RowHeight = 56.dp

@Composable
private fun RadioRow(radio: BleRadio, onPick: (BleRadio) -> Unit) {
    Surface(
        { onPick(radio) },
        Modifier.fillMaxWidth().heightIn(min = RowHeight),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(radio.name ?: "Unnamed radio", style = MaterialTheme.typography.bodyLarge)
                Text("Address ending ${addressTail(radio.address)}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(signalLabel(radio.rssi), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** An error in an errorContainer card that a screen reader announces when it appears. */
@Composable
internal fun ErrorSurface(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
            error(message)
        },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) { Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
}

/** RSSI at or above this reads as strong, in dBm. */
internal const val RSSI_STRONG = -60

/** RSSI at or above this (and below strong) reads as good. */
internal const val RSSI_GOOD = -70

/** RSSI at or above this (and below good) reads as fair; anything lower is weak. */
internal const val RSSI_FAIR = -80

/** A plain word for a signal strength in dBm; a radio that reported none is "Signal unknown". */
internal fun signalLabel(rssi: Int?): String = when {
    rssi == null -> "Signal unknown"
    rssi >= RSSI_STRONG -> "Strong"
    rssi >= RSSI_GOOD -> "Good"
    rssi >= RSSI_FAIR -> "Fair"
    else -> "Weak"
}

/** The last five characters of an address, enough to tell same-named radios apart. */
internal fun addressTail(address: String): String = address.takeLast(ADDRESS_TAIL)

private const val ADDRESS_TAIL = 5

private const val REFRESH_MILLIS = 2_000L
