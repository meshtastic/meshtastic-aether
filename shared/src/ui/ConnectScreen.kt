@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.meshtastic.aether.ble.bleSupported
import org.meshtastic.aether.getPlatform
import org.meshtastic.aether.serial.serialSupported


/**
 * The prefilled address of the firmware's WebSocket phone API (ESP32 and meshtasticd both serve `/api/v1/ws`).
 * The browser gets `wss://`, because an HTTPS page may only open that; the native apps get `ws://`, because a stock
 * node's certificate is self-signed and they would refuse it.
 */
internal fun defaultWebSocketUrl(secure: Boolean): String =
    if (secure) "wss://meshtastic.local/api/v1/ws" else "ws://meshtastic.local/api/v1/ws"

private const val DEFAULT_HOST = "meshtastic.local"

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

/**
 * The TCP fields read as a link, or the reason they cannot be: a blank port means the default, anything else must be
 * 1..65535.
 */
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
    val p = port.trim()
    val number = p.toIntOrNull()
    return when {
        h.isEmpty() -> TcpEntry.Invalid(HOST_ERROR)
        p.isEmpty() -> TcpEntry.Valid(LinkChoice.Tcp(h, LinkChoice.DEFAULT_TCP_PORT))
        number == null || number !in 1..MAX_TCP_PORT -> TcpEntry.Invalid(PORT_ERROR)
        else -> TcpEntry.Valid(LinkChoice.Tcp(h, number))
    }
}

private const val MAX_TCP_PORT = 65_535

/**
 * Why [url] cannot be a WebSocket link, or null when it can: it needs a `ws://` or `wss://` scheme and a host after it.
 */
internal fun webSocketError(url: String): String? {
    val u = url.trim()
    if (u.isEmpty()) return WS_BLANK_ERROR
    val scheme = listOf("ws://", "wss://").firstOrNull { u.startsWith(it, ignoreCase = true) }
    val rest = scheme?.let { u.substring(it.length) }
    return if (rest.isNullOrBlank() || rest.startsWith("/")) WS_SCHEME_ERROR else null
}

/**
 * The link the visible fields describe for [kind], or null while they are invalid or the kind needs a pick (Bluetooth).
 */
internal fun currentLink(kind: LinkKind, host: String, port: String, ws: String): LinkChoice? = when (kind) {
    LinkKind.Demo -> LinkChoice.Demo
    LinkKind.Network -> (tcpEntry(host, port) as? TcpEntry.Valid)?.link
    LinkKind.WebSocket -> if (webSocketError(ws) == null) LinkChoice.WebSocket(ws.trim()) else null
    LinkKind.Bluetooth, LinkKind.Serial -> null
}

/**
 * What the connect screen opens with: [pending] is a saved link about to reconnect, [failure] is why the last attempt
 * did not start, and [lastTcp] and [lastKind] prefill the TCP fields and pick the pane.
 */
@Stable
internal data class ConnectContext(
    val pending: PendingConnect?,
    val failure: String?,
    val lastTcp: LinkChoice.Tcp? = null,
    val lastKind: LinkKind? = null,
)

/**
 * What this target can connect over, by default the platform's capabilities. [secureWebSocket] prefills `wss://`;
 * [focusChooser] starts a D-pad or keyboard user on the link chooser.
 */
@Stable
internal data class ConnectTarget(
    val supportsTcp: Boolean = getPlatform().supportsTcp,
    val bluetooth: Boolean = bleSupported(),
    val serial: Boolean = serialSupported(),
    val secureWebSocket: Boolean = getPlatform().isBrowser,
    val focusChooser: Boolean = false,
)

/** The connect screen's edits: the TCP and WebSocket fields, the pane shown, and the pane of the last attempt. */
@Stable
internal class ConnectForm(host: String, port: String, ws: String, kind: LinkKind) {
    var host by mutableStateOf(host)
    var port by mutableStateOf(port)
    var ws by mutableStateOf(ws)
    var kind by mutableStateOf(kind)

    /** The pane that started the last attempt; its failure shows only there. */
    var chosen by mutableStateOf<LinkKind?>(null)

    /** Whether the last failure belongs on [pane]. */
    fun failureShowsOn(pane: LinkKind): Boolean = chosen == null || chosen == pane

    companion object {
        /** Keeps the typed fields and the pane across a visit to the settings screen. */
        val Saver: Saver<ConnectForm, Any> = listSaver(
            save = { listOf(it.host, it.port, it.ws, it.kind.name) },
            restore = { ConnectForm(it[0], it[1], it[2], LinkKind.valueOf(it[3])) },
        )
    }
}

private fun connectForm(context: ConnectContext, kinds: List<LinkKind>, secureWebSocket: Boolean): ConnectForm {
    val pendingLink = context.pending?.link
    return ConnectForm(
        host = context.lastTcp?.host ?: DEFAULT_HOST,
        port = (context.lastTcp?.port ?: LinkChoice.DEFAULT_TCP_PORT).toString(),
        ws = (pendingLink as? LinkChoice.WebSocket)?.url ?: defaultWebSocketUrl(secureWebSocket),
        kind = initialKind(kinds, pendingLink?.kind(), context.lastKind),
    )
}

/**
 * The first screen: the demo first, then a radio over TCP or Bluetooth, or a WebSocket, chosen with [LinkTypeChooser].
 * A pending reconnect in [context] is cancelled by any edit or chooser change, and Connect now uses what is on screen.
 * [onKind] hears each chooser change, so the next launch opens on it.
 */
@Composable
internal fun ConnectScreen(
    onChoose: (LinkChoice) -> Unit,
    context: ConnectContext,
    modifier: Modifier = Modifier,
    target: ConnectTarget = remember { ConnectTarget() },
    onKind: (LinkKind) -> Unit = {},
) {
    // Once per visit: a layout change recomposes the chooser and must not pull focus out of a field being typed in.
    var focusChooser by remember { mutableStateOf(target.focusChooser) }
    val kinds = availableKinds(target.supportsTcp, target.bluetooth, target.serial)
    val form = rememberSaveable(saver = ConnectForm.Saver) { connectForm(context, kinds, target.secureWebSocket) }
    val selected = if (form.kind in kinds) form.kind else kinds.first()
    val pending = context.pending

    fun cancelPending() {
        pending?.countdown?.cancel()
    }

    fun choose(from: LinkKind, link: LinkChoice) {
        form.chosen = from
        onChoose(link)
    }

    val link = currentLink(selected, form.host, form.port, form.ws)
    val active = pending?.takeIf { !it.countdown.cancelled }
    val fields: @Composable ColumnScope.(Boolean) -> Unit = { sideBySide ->
        LinkFields(selected, form, sideBySide, ::cancelPending, ::choose)
        if (context.failure != null && form.failureShowsOn(selected)) ErrorSurface(context.failure)
    }
    val slots = ConnectSlots(
        banner = active?.let { p -> { CountdownBanner(p, onNow = link?.let { l -> { choose(selected, l) } }) } },
        heading = { PaneHeading(selected, kinds.size > 1) },
        chooser = {
            LinkTypeChooser(kinds, selected, onSelect = {
                cancelPending()
                form.kind = it
                onKind(it)
            }, onStartFocus = if (focusChooser) { { focusChooser = false } } else null)
        },
        fields = fields,
        primary = if (selected.picksFromList) null else { { PrimaryButton(selected, link) { choose(selected, it) } } },
    )
    ConnectLayout(slots, modifier)
}
