@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.meshtastic.aether.ble.BleChooserException
import org.meshtastic.aether.ble.BleRadio
import org.meshtastic.aether.ble.RadioList
import org.meshtastic.aether.ble.bleChooser
import org.meshtastic.aether.ble.bleScan
import org.meshtastic.aether.ble.ensureBlePermissions
import org.meshtastic.aether.log.Log
import kotlinx.io.IOException
import org.meshtastic.sdk.MeshtasticException

/**
 * Scan for Meshtastic radios over Bluetooth and pick one; the scan stops when the section leaves the screen. Where the
 * platform has its own [chooser] (the browser), one button opens it instead.
 */
@Composable
internal fun BluetoothSection(
    onPick: (BleRadio) -> Unit,
    modifier: Modifier = Modifier,
    onInteract: () -> Unit = {},
    chooser: (suspend () -> BleRadio?)? = remember { bleChooser() },
) {
    if (chooser != null) {
        ChooserSection(chooser, onPick, modifier, onInteract)
        return
    }
    val scan = remember { BleScan() }
    LaunchedEffect(scan.scanning) { if (scan.scanning) scan.scanUntilStopped() }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            {
                onInteract()
                scan.scanning = !scan.scanning
            },
            ButtonDefaults.shapes(),
            Modifier.fillMaxWidth().heightIn(min = MinTouch),
        ) { Text(if (scan.scanning) "Stop scanning" else "Scan for Bluetooth radios") }
        scan.error?.let { ErrorSurface(it) }
        if (scan.scanning && scan.radios.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadingIndicator(Modifier.size(LoadingSize))
                Text("Looking for radios...", style = MaterialTheme.typography.bodyLarge)
            }
        }
        PickList(scan.radios, ::radioRow, onPick)
    }
}

/** A Bluetooth scan's state: whether it runs, the radios seen recently, and why it last stopped on its own. */
@Stable
private class BleScan {
    var scanning by mutableStateOf(false)
    var radios by mutableStateOf(emptyList<BleRadio>())
    var error by mutableStateOf<String?>(null)

    fun stop(reason: String?) {
        error = reason
        scanning = false
    }

    fun fail(e: Exception) {
        Log.e("ble", "scan failed", e)
        stop(e.message ?: e::class.simpleName)
    }
}

/** Scans until cancelled; a missing permission, missing Bluetooth or a failure stops it with the reason shown. */
private suspend fun BleScan.scanUntilStopped() {
    error = null
    try {
        openScan()?.let { collectRadios(it) }
    } catch (e: MeshtasticException) {
        fail(e) // the SDK's scan flow failing
    } catch (e: IOException) {
        fail(e) // Kable's UnmetRequirementException as the scanner is built: Bluetooth off or not permitted
    } catch (e: IllegalStateException) {
        fail(e) // Kable's scanner state errors, raised before the SDK's flow wraps them
    } finally {
        Log.i("ble", "scan stopped")
    }
}

/** The platform's scan once permission is granted, or null with the scan stopped and the reason set. */
private suspend fun BleScan.openScan(): Flow<BleRadio>? {
    if (!ensureBlePermissions()) {
        Log.w("ble", "scan not started: permission not granted")
        stop("Bluetooth permission was not granted.")
        return null
    }
    val flow = bleScan()
    if (flow == null) stop("Bluetooth is not available on this device.")
    return flow
}

/** Shows each radio as it is seen, and refreshes the list so radios that went quiet drop out. */
private suspend fun BleScan.collectRadios(flow: Flow<BleRadio>) {
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
            if (list.onSeen(radio, now)) {
                Log.i("ble", "found ${radio.name ?: "unnamed"} ${radio.address} rssi=${radio.rssi}")
            }
            radios = list.visible(now)
        }
    }
}

/** One button that opens the platform's radio chooser; a dismissed chooser is not an error. */
@Composable
private fun ChooserSection(
    chooser: suspend () -> BleRadio?,
    onPick: (BleRadio) -> Unit,
    modifier: Modifier,
    onInteract: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            {
                onInteract()
                error = null
                choosing = true
                // Undispatched, so the browser still sees the click as the gesture that opens its chooser.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        val radio = chooser()
                        Log.i("ble", if (radio == null) "chooser dismissed" else "chose ${radio.address}")
                        radio?.let(onPick)
                    } catch (e: BleChooserException) {
                        Log.e("ble", "chooser failed", e)
                        error = e.message ?: e::class.simpleName
                    } finally {
                        choosing = false
                    }
                }
            },
            ButtonDefaults.shapes(),
            Modifier.fillMaxWidth().heightIn(min = MinTouch),
            enabled = !choosing,
        ) { Text("Choose a Bluetooth radio") }
        error?.let { ErrorSurface(it) }
    }
}

private val LoadingSize = 48.dp

/** What a [PickList] row shows: its [title], the [detail] under it and the [trailing] status. */
internal class PickRow(val title: String, val detail: String, val trailing: String)

/** Found radios or ports as one contained list, each row picking its item, the rows joined by segmented gaps. */
@Composable
internal fun <T> PickList(items: List<T>, row: (T) -> PickRow, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        items.forEachIndexed { index, item ->
            val shown = row(item)
            SegmentedListItem(
                onClick = { onPick(item) },
                shapes = ListItemDefaults.segmentedShapes(index, items.size),
                supportingContent = { Text(shown.detail) },
                trailingContent = { Text(shown.trailing) },
                content = { Text(shown.title) },
            )
        }
    }
}

private fun radioRow(radio: BleRadio) =
    PickRow(radio.name ?: "Unnamed radio", "Address ending ${addressTail(radio.address)}", signalLabel(radio.rssi))

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
