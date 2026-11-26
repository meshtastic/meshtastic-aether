package org.meshtastic.ambient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.meshtastic.ambient.getPlatform

/** What the person picked on the connect screen. */
sealed interface LinkChoice {
    data object Simulated : LinkChoice

    data object Replay : LinkChoice

    data class Tcp(val host: String, val port: Int) : LinkChoice

    data class WebSocket(val url: String) : LinkChoice
}

@Composable
fun ConnectScreen(onChoose: (LinkChoice) -> Unit, modifier: Modifier = Modifier) {
    val platform = remember { getPlatform() }
    var host by remember { mutableStateOf("meshtastic.local") }
    var port by remember { mutableStateOf("4403") }
    var ws by remember { mutableStateOf("ws://meshtastic.local:80/ws") }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("Meshtastic Ambient", style = MaterialTheme.typography.headlineMedium)
        Text("Listen-only. Nothing leaves this device but the radio link.", style = MaterialTheme.typography.bodySmall)
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ onChoose(LinkChoice.Simulated) }, Modifier.fillMaxWidth()) { Text("Simulated mesh") }
            Button({ onChoose(LinkChoice.Replay) }, Modifier.fillMaxWidth()) { Text("Replay demo capture") }
            if (platform.supportsTcp) {
                OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), label = { Text("Node host") }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Port") }, singleLine = true)
                Button(
                    { onChoose(LinkChoice.Tcp(host.trim(), port.toIntOrNull() ?: 4403)) },
                    Modifier.fillMaxWidth(),
                    enabled = host.isNotBlank(),
                ) { Text("Connect over TCP") }
            }
            OutlinedTextField(ws, { ws = it }, Modifier.fillMaxWidth(), label = { Text("WebSocket URL") }, singleLine = true)
            Button({ onChoose(LinkChoice.WebSocket(ws.trim())) }, Modifier.fillMaxWidth(), enabled = ws.isNotBlank()) {
                Text("Connect over WebSocket")
            }
            Text(
                if (platform.name.startsWith("Web")) {
                    "TODO: WebSerial link (needs a transport on the SDK's RadioTransport)."
                } else {
                    "TODO: USB serial and Bluetooth links need a device picker and are not wired yet."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
