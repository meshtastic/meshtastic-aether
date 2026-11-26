package org.meshtastic.aether.ui

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
import org.meshtastic.aether.ble.bleSupported
import org.meshtastic.aether.getPlatform

private const val DEFAULT_WS_URL = "ws://meshtastic.local:80/ws"
private const val DEFAULT_HOST = "meshtastic.local"
private const val TAGLINE = "The mesh, on the ether."
private val GLYPH_SIZE = 56.dp

/** A pending automatic reconnect: shown as a banner with a cancel button while [countdown] runs. */
internal class PendingConnect(val link: LinkChoice, val countdown: Countdown) {
    val label: String get() = link.label()

    /** The banner line for [seconds] left. */
    fun text(seconds: Int): String =
        if (link == LinkChoice.Demo) "Starting the demo in $seconds s" else "Connecting to $label in $seconds s"
}

/** A short name for a link, for the countdown banner. */
internal fun LinkChoice.label(): String = when (this) {
    LinkChoice.Demo -> "the demo"
    LinkChoice.Replay -> "the capture replay"
    is LinkChoice.Tcp -> "$host:$port"
    is LinkChoice.WebSocket -> url
    is LinkChoice.Ble -> radio.name ?: radio.address
}

/** The TCP fields read as a link, or the reason they cannot be: a blank port means the default, anything else must be 1..65535. */
internal sealed interface TcpEntry {
    data class Valid(val link: LinkChoice.Tcp) : TcpEntry

    data class Invalid(val message: String) : TcpEntry
}

internal const val PORT_ERROR = "Port must be a number from 1 to 65535"

internal fun tcpEntry(host: String, port: String): TcpEntry {
    val h = host.trim()
    if (h.isEmpty()) return TcpEntry.Invalid("Enter the node's host")
    val p = port.trim()
    if (p.isEmpty()) return TcpEntry.Valid(LinkChoice.Tcp(h, LinkChoice.DEFAULT_TCP_PORT))
    val number = p.toIntOrNull()
    return if (number == null || number !in 1..MAX_TCP_PORT) TcpEntry.Invalid(PORT_ERROR) else TcpEntry.Valid(LinkChoice.Tcp(h, number))
}

private const val MAX_TCP_PORT = 65_535

/** Which button of the connect screen was pressed last, so a failure shows beside it. */
private enum class Section { Demo, Tcp, Ble, WebSocket }

@Composable
private fun FailureText(failure: String?, show: Boolean) {
    if (show && failure != null) Text(failure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
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
    val bluetooth = remember { bleSupported() }
    var host by remember { mutableStateOf(lastTcp?.host ?: DEFAULT_HOST) }
    var port by remember { mutableStateOf((lastTcp?.port ?: LinkChoice.DEFAULT_TCP_PORT).toString()) }
    var ws by remember { mutableStateOf(DEFAULT_WS_URL) }
    var advanced by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<Section?>(null) }
    fun choose(section: Section, link: LinkChoice) {
        chosen = section
        onChoose(link)
    }
    val tcp = tcpEntry(host, port)
    val directLink = platform.supportsTcp || bluetooth
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenGutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        AetherGlyph(GLYPH_SIZE)
        Text("Meshtastic Aether", style = MaterialTheme.typography.headlineMedium)
        Text(TAGLINE, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "It only listens. Nothing is sent to the mesh.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FailureText(failure, show = chosen == null)
            if (pending != null && !pending.countdown.cancelled) {
                val seconds = pending.countdown.secondsLeft
                Text(
                    pending.text(seconds),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { contentDescription = pending.text(seconds).replace(" s", " seconds") },
                )
                TextButton(pending.countdown::cancel, Modifier.heightIn(min = MinTouch)) { Text("Cancel") }
            }
            Button({ choose(Section.Demo, LinkChoice.Demo) }, Modifier.fillMaxWidth().heightIn(min = MinTouch)) { Text("Start the demo") }
            FailureText(failure, show = chosen == Section.Demo)
            if (directLink) {
                Text("Connect a radio", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            } else {
                Text("Connect to a node's WebSocket", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            }
            if (platform.supportsTcp) {
                val portError = (tcp as? TcpEntry.Invalid)?.message?.takeIf { host.isNotBlank() }
                OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), label = { Text("Node host") }, singleLine = true)
                OutlinedTextField(
                    port,
                    { port = it.filter(Char::isDigit) },
                    Modifier.fillMaxWidth(),
                    label = { Text("Port") },
                    singleLine = true,
                    isError = portError != null,
                    supportingText = portError?.let { { Text(it) } },
                )
                Button(
                    { (tcp as? TcpEntry.Valid)?.let { choose(Section.Tcp, it.link) } },
                    Modifier.fillMaxWidth().heightIn(min = MinTouch),
                    enabled = tcp is TcpEntry.Valid,
                ) { Text("Connect over TCP") }
                FailureText(failure, show = chosen == Section.Tcp)
            }
            if (bluetooth) {
                BluetoothSection { choose(Section.Ble, LinkChoice.Ble(it)) }
                FailureText(failure, show = chosen == Section.Ble)
            }
            if (directLink) {
                TextButton({ advanced = !advanced }, Modifier.heightIn(min = MinTouch)) { Text(if (advanced) "Hide advanced" else "Advanced") }
            }
            if (advanced || !directLink) {
                OutlinedTextField(ws, { ws = it }, Modifier.fillMaxWidth(), label = { Text("WebSocket URL") }, singleLine = true)
                Button(
                    { choose(Section.WebSocket, LinkChoice.WebSocket(ws.trim())) },
                    Modifier.fillMaxWidth().heightIn(min = MinTouch),
                    enabled = ws.isNotBlank(),
                ) { Text("Connect over WebSocket") }
                FailureText(failure, show = chosen == Section.WebSocket)
            }
        }
    }
}
