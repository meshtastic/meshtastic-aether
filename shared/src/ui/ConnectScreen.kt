package org.meshtastic.ambient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.meshtastic.ambient.ble.bleSupported
import org.meshtastic.ambient.getPlatform

private const val DEFAULT_WS_URL = "ws://meshtastic.local:80/ws"
private const val DEFAULT_HOST = "meshtastic.local"

/** A pending automatic reconnect: shown as a banner with a cancel button while [countdown] runs. */
internal class PendingConnect(val label: String, val countdown: Countdown)

/** A short name for a link, for the countdown banner. */
internal fun LinkChoice.label(): String = when (this) {
    LinkChoice.Demo -> "the demo"
    LinkChoice.Replay -> "the capture replay"
    is LinkChoice.Tcp -> "$host:$port"
    is LinkChoice.WebSocket -> url
    is LinkChoice.Ble -> radio.name ?: radio.address
}

/**
 * The first screen: the demo first, then a radio over TCP or Bluetooth, WebSocket under Advanced. [pending] is a
 * saved link about to reconnect; [failure] is why the last attempt did not start. [lastTcp] prefills the TCP fields.
 */
@Composable
internal fun ConnectScreen(
    onChoose: (LinkChoice) -> Unit,
    pending: PendingConnect?,
    failure: String?,
    lastTcp: LinkChoice.Tcp?,
    modifier: Modifier = Modifier,
) {
    val platform = remember { getPlatform() }
    var host by remember { mutableStateOf(lastTcp?.host ?: DEFAULT_HOST) }
    var port by remember { mutableStateOf((lastTcp?.port ?: LinkChoice.DEFAULT_TCP_PORT).toString()) }
    var ws by remember { mutableStateOf(DEFAULT_WS_URL) }
    var advanced by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenGutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("Meshtastic Ambient", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Listen-only. Nothing leaves this device but the radio link.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (pending != null && !pending.countdown.cancelled) {
                val seconds = pending.countdown.secondsLeft
                Text(
                    "Connecting to ${pending.label} in $seconds s",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { contentDescription = "Connecting to ${pending.label} in $seconds seconds" },
                )
                TextButton(pending.countdown::cancel, Modifier.heightIn(min = MinTouch)) { Text("Cancel") }
            }
            Button({ onChoose(LinkChoice.Demo) }, Modifier.fillMaxWidth().heightIn(min = MinTouch)) { Text("Start the demo") }
            Text("Connect a radio", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            if (platform.supportsTcp) {
                OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), label = { Text("Node host") }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Port") }, singleLine = true)
                Button(
                    { onChoose(LinkChoice.parseTcp("${host.trim()}:$port") ?: LinkChoice.Tcp(host.trim(), LinkChoice.DEFAULT_TCP_PORT)) },
                    Modifier.fillMaxWidth().heightIn(min = MinTouch),
                    enabled = host.isNotBlank(),
                ) { Text("Connect over TCP") }
            }
            if (bleSupported()) BluetoothSection { onChoose(LinkChoice.Ble(it)) }
            TextButton({ advanced = !advanced }, Modifier.heightIn(min = MinTouch)) { Text(if (advanced) "Hide advanced" else "Advanced") }
            if (advanced) {
                OutlinedTextField(ws, { ws = it }, Modifier.fillMaxWidth(), label = { Text("WebSocket URL") }, singleLine = true)
                Button(
                    { onChoose(LinkChoice.WebSocket(ws.trim())) },
                    Modifier.fillMaxWidth().heightIn(min = MinTouch),
                    enabled = ws.isNotBlank(),
                ) { Text("Connect over WebSocket") }
            }
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
