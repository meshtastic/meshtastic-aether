@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.meshtastic.aether.log.Log
import org.meshtastic.aether.serial.SerialRadio
import org.meshtastic.aether.serial.listSerialPorts

/** Ports in the order the list shows them: likely Meshtastic radios first, then the rest, each by label. */
internal fun orderedPorts(ports: List<SerialRadio>): List<SerialRadio> =
    ports.sortedWith(compareByDescending<SerialRadio> { it.likelyMeshtastic }.thenBy { it.label.lowercase() })

internal const val NO_SERIAL_PORTS = "No USB serial devices found. Plug in a radio and look again."

/**
 * Find the radios on USB serial ports and pick one. Looking asks for USB permission where the platform needs it
 * (Android); [find] lists the ports and defaults to the platform's.
 */
@Composable
internal fun SerialSection(
    onPick: (SerialRadio) -> Unit,
    modifier: Modifier = Modifier,
    onInteract: () -> Unit = {},
    find: suspend () -> List<SerialRadio> = { listSerialPorts() },
) {
    var request by remember { mutableIntStateOf(0) }
    var looking by remember { mutableStateOf(false) }
    var ports by remember { mutableStateOf<List<SerialRadio>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(request) {
        if (request == 0) return@LaunchedEffect
        looking = true
        error = null
        try {
            ports = orderedPorts(find())
            Log.i("serial", "found ${ports?.size} port(s)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("serial", "listing ports failed", e)
            error = "Could not list the USB serial ports"
        } finally {
            looking = false
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            {
                onInteract()
                request++
            },
            Modifier.fillMaxWidth().heightIn(min = MinTouch),
            enabled = !looking,
        ) { Text(if (ports == null) "Find USB radios" else "Look again") }
        error?.let { ErrorSurface(it) }
        if (looking) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadingIndicator(Modifier.size(LoadingSize))
                Text("Looking for radios...", style = MaterialTheme.typography.bodyLarge)
            }
        }
        val found = ports
        if (found != null && !looking) {
            if (found.isEmpty()) Text(NO_SERIAL_PORTS, style = MaterialTheme.typography.bodyLarge)
            for (port in found) PortRow(port, onPick)
        }
    }
}

private val LoadingSize = 48.dp
private val RowHeight = 56.dp

@Composable
private fun PortRow(port: SerialRadio, onPick: (SerialRadio) -> Unit) {
    Surface(
        { onPick(port) },
        Modifier.fillMaxWidth().heightIn(min = RowHeight),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(port.label, style = MaterialTheme.typography.bodyLarge)
                Text(port.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (port.likelyMeshtastic) "Meshtastic" else "Other device", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
