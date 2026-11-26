@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.meshtastic.aether.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.meshtastic.aether.ble.bleSupported
import org.meshtastic.aether.getPlatform
import org.meshtastic.aether.serial.serialSupported
import org.meshtastic.aether.getPlatform

/**
 * The prefilled address of the firmware's WebSocket phone API (ESP32 and meshtasticd both serve `/api/v1/ws`).
 * The browser gets `wss://`, because an HTTPS page may only open that; the native apps get `ws://`, because a stock
 * node's certificate is self-signed and they would refuse it.
 */
internal fun defaultWebSocketUrl(secure: Boolean): String =
    if (secure) "wss://meshtastic.local/api/v1/ws" else "ws://meshtastic.local/api/v1/ws"
private const val DEFAULT_HOST = "meshtastic.local"
private const val TAGLINE = "The mesh, on the ether."
private val GLYPH_SIZE = 56.dp
private val MediumColumnWidth = 560.dp
private val PortFieldWidth = 120.dp
private val ShortHeight = 480.dp
private val PaneMaxWidth = 480.dp

/** A pending automatic reconnect: shown as a banner with a cancel button while [countdown] runs. */
internal class PendingConnect(val link: LinkChoice, val countdown: Countdown) {
    val label: String get() = link.label()

    /** The seconds the countdown started with, for the progress bar. */
    val totalSeconds: Int = countdown.secondsLeft

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
    is LinkChoice.Serial -> label
}

/** The TCP fields read as a link, or the reason they cannot be: a blank port means the default, anything else must be 1..65535. */
internal sealed interface TcpEntry {
    data class Valid(val link: LinkChoice.Tcp) : TcpEntry

    data class Invalid(val message: String) : TcpEntry
}

internal const val PORT_ERROR = "Port must be a number from 1 to 65535"
internal const val HOST_ERROR = "Enter the node's host"
internal const val WS_BLANK_ERROR = "Enter the WebSocket URL"
internal const val WS_SCHEME_ERROR = "The URL must start with ws:// or wss:// and name a host"

internal fun tcpEntry(host: String, port: String): TcpEntry {
    val h = host.trim()
    if (h.isEmpty()) return TcpEntry.Invalid(HOST_ERROR)
    val p = port.trim()
    if (p.isEmpty()) return TcpEntry.Valid(LinkChoice.Tcp(h, LinkChoice.DEFAULT_TCP_PORT))
    val number = p.toIntOrNull()
    return if (number == null || number !in 1..MAX_TCP_PORT) TcpEntry.Invalid(PORT_ERROR) else TcpEntry.Valid(LinkChoice.Tcp(h, number))
}

private const val MAX_TCP_PORT = 65_535

/** Why [url] cannot be a WebSocket link, or null when it can: it needs a `ws://` or `wss://` scheme and a host after it. */
internal fun webSocketError(url: String): String? {
    val u = url.trim()
    if (u.isEmpty()) return WS_BLANK_ERROR
    val rest = listOf("ws://", "wss://").firstOrNull { u.startsWith(it, ignoreCase = true) }?.let { u.substring(it.length) }
    return if (rest.isNullOrBlank() || rest.startsWith("/")) WS_SCHEME_ERROR else null
}

/** The link the visible fields describe for [kind], or null while they are invalid or the kind needs a pick (Bluetooth). */
internal fun currentLink(kind: LinkKind, host: String, port: String, ws: String): LinkChoice? = when (kind) {
    LinkKind.Demo -> LinkChoice.Demo
    LinkKind.Network -> (tcpEntry(host, port) as? TcpEntry.Valid)?.link
    LinkKind.WebSocket -> if (webSocketError(ws) == null) LinkChoice.WebSocket(ws.trim()) else null
    LinkKind.Bluetooth, LinkKind.Serial -> null
}

/**
 * The first screen: the demo first, then a radio over TCP or Bluetooth, or a WebSocket, chosen with [LinkTypeChooser].
 * [pending] is a saved link about to reconnect; any edit or chooser change cancels it, and Connect now uses what is on
 * screen. [failure] is why the last attempt did not start. [lastTcp] prefills the TCP fields. [supportsTcp] and
 * [bluetooth] default to the platform's capabilities.
 */
@Composable
internal fun ConnectScreen(
    onChoose: (LinkChoice) -> Unit,
    pending: PendingConnect?,
    failure: String?,
    lastTcp: LinkChoice.Tcp?,
    modifier: Modifier = Modifier,
    supportsTcp: Boolean = remember { getPlatform().supportsTcp },
    bluetooth: Boolean = remember { bleSupported() },
    serial: Boolean = remember { serialSupported() },
    secureWebSocket: Boolean = remember { getPlatform().isBrowser },
    focusChooser: Boolean = false,
    lastKind: LinkKind? = null,
    onKind: (LinkKind) -> Unit = {},
) {
    val chooserFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // The chooser sits under BoxWithConstraints, which composes during layout, so the first frames may not have it yet.
        if (focusChooser) repeat(FOCUS_TRIES) { if (runCatching { chooserFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect else withFrameNanos { } }
    }
    val kinds = availableKinds(supportsTcp, bluetooth, serial)
    val pendingLink = pending?.link
    var host by remember { mutableStateOf(lastTcp?.host ?: DEFAULT_HOST) }
    var port by remember { mutableStateOf((lastTcp?.port ?: LinkChoice.DEFAULT_TCP_PORT).toString()) }
    var ws by remember { mutableStateOf((pendingLink as? LinkChoice.WebSocket)?.url ?: defaultWebSocketUrl(secureWebSocket)) }
    var kind by remember { mutableStateOf(initialKind(kinds, pendingLink?.kind(), lastKind)) }
    var chosen by remember { mutableStateOf<LinkKind?>(null) }
    val selected = if (kind in kinds) kind else kinds.first()

    fun cancelPending() {
        pending?.countdown?.cancel()
    }

    fun choose(from: LinkKind, link: LinkChoice) {
        chosen = from
        onChoose(link)
    }

    val link = currentLink(selected, host, port, ws)
    val submit = { link?.let { choose(selected, it) } ?: Unit }
    val active = pending?.takeIf { !it.countdown.cancelled }

    val banner: (@Composable () -> Unit)? = active?.let { p ->
        {
            val seconds = p.countdown.secondsLeft
            val total = p.totalSeconds.coerceAtLeast(1)
            val target = ((total - seconds + 1).coerceAtMost(total)).toFloat() / total
            val progress by animateFloatAsState(target, tween(ONE_SECOND_MS, easing = LinearEasing))
            CountdownBanner(
                text = p.text(seconds),
                progress = { progress },
                onNow = link?.let { l -> { choose(selected, l) } },
                onCancel = p.countdown::cancel,
                announce = seconds == p.totalSeconds,
            )
        }
    }

    val chooser: @Composable () -> Unit = {
        LinkTypeChooser(kinds, selected, onSelect = {
            cancelPending()
            kind = it
            onKind(it)
        }, selectedFocus = chooserFocus)
    }

    val fields: @Composable ColumnScope.(Boolean) -> Unit = { sideBySide ->
        when (selected) {
            LinkKind.Demo -> Text(
                "Plays the scripted demo mesh. No radio needed.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LinkKind.Network -> {
                val entry = tcpEntry(host, port)
                val hostError = if (host.isBlank()) HOST_ERROR else null
                val portError = (entry as? TcpEntry.Invalid)?.message?.takeIf { hostError == null }
                val hostField: @Composable (Modifier) -> Unit = { m ->
                    OutlinedTextField(
                        host,
                        {
                            cancelPending()
                            host = it
                        },
                        m,
                        label = { Text("Node host") },
                        singleLine = true,
                        isError = hostError != null,
                        supportingText = hostError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    )
                }
                val portField: @Composable (Modifier) -> Unit = { m ->
                    OutlinedTextField(
                        port,
                        {
                            cancelPending()
                            port = it.filter(Char::isDigit)
                        },
                        m,
                        label = { Text("Port") },
                        singleLine = true,
                        isError = portError != null,
                        supportingText = portError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                    )
                }
                if (sideBySide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        hostField(Modifier.weight(1f))
                        portField(Modifier.width(PortFieldWidth))
                    }
                } else {
                    hostField(Modifier.fillMaxWidth())
                    portField(Modifier.fillMaxWidth())
                }
            }

            LinkKind.Bluetooth -> BluetoothSection({ choose(LinkKind.Bluetooth, LinkChoice.Ble(it)) }, onInteract = ::cancelPending)

            LinkKind.Serial -> SerialSection({ choose(LinkKind.Serial, LinkChoice.Serial(it.path, it.label)) }, onInteract = ::cancelPending)

            LinkKind.WebSocket -> {
                val wsError = webSocketError(ws).takeIf { ws.isNotBlank() } ?: if (ws.isBlank()) WS_BLANK_ERROR else null
                OutlinedTextField(
                    ws,
                    {
                        cancelPending()
                        ws = it
                    },
                    Modifier.fillMaxWidth(),
                    label = { Text("WebSocket URL") },
                    singleLine = true,
                    isError = wsError != null,
                    supportingText = wsError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                )
            }
        }
        if (failure != null && (chosen == null || chosen == selected)) ErrorSurface(failure)
    }

    val primary: (@Composable () -> Unit)? = if (selected == LinkKind.Bluetooth || selected == LinkKind.Serial) {
        null
    } else {
        {
            Button(
                { submit() },
                Modifier.fillMaxWidth().heightIn(min = MinTouch),
                enabled = link != null,
            ) {
                Text(
                    when (selected) {
                        LinkKind.Demo -> "Start the demo"
                        LinkKind.Network -> "Connect over TCP"
                        else -> "Connect over WebSocket"
                    },
                )
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val twoPanes = widthClass(maxWidth) == WidthClass.Expanded || maxHeight < ShortHeight
        if (twoPanes) {
            Row(
                Modifier.fillMaxSize().padding(ScreenGutter),
                horizontalArrangement = Arrangement.spacedBy(ScreenGutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    Brand()
                    banner?.invoke()
                }
                Surface(
                    Modifier.weight(1f).widthIn(max = PaneMaxWidth),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PaneHeading(selected, kinds.size > 1)
                        chooser()
                        fields(true)
                        primary?.invoke()
                    }
                }
            }
        } else {
            val medium = widthClass(maxWidth) == WidthClass.Medium
            val column = if (medium) Modifier.widthIn(max = MediumColumnWidth) else Modifier
            Column(
                Modifier.fillMaxSize().padding(ScreenGutter),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    Brand()
                    Column(column.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        banner?.invoke()
                        PaneHeading(selected, kinds.size > 1)
                        chooser()
                        fields(medium)
                    }
                }
                if (primary != null) Column(column.fillMaxWidth().padding(top = 12.dp)) { primary() }
            }
        }
    }
}

private const val ONE_SECOND_MS = 1_000

@Composable
private fun Brand() {
    AetherGlyph(GLYPH_SIZE)
    Text("Meshtastic Aether", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text(TAGLINE, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        "It only listens. Nothing is sent to the mesh.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PaneHeading(kind: LinkKind, direct: Boolean) {
    val text = when {
        kind == LinkKind.WebSocket && !direct -> "Connect to a node's WebSocket"
        else -> "Connect"
    }
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

private const val FOCUS_TRIES = 10
