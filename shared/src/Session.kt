package org.meshtastic.aether

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.meshtastic.aether.log.Log
import org.meshtastic.aether.mesh.DemoCapture
import org.meshtastic.aether.mesh.DemoMeshSource
import org.meshtastic.aether.mesh.MeshSource
import org.meshtastic.aether.ui.LOCAL_NETWORK_DENIED
import org.meshtastic.aether.ui.LinkChoice
import org.meshtastic.aether.ui.LiveLink
import org.meshtastic.aether.ui.LoopingReplaySource
import org.meshtastic.aether.ui.PendingConnect
import org.meshtastic.aether.ui.Settings
import org.meshtastic.aether.ui.nowMillis
import org.meshtastic.aether.ui.startFailureText
import org.meshtastic.aether.ui.unreachableText
import org.meshtastic.sdk.MeshtasticException

/** One running session: where events come from, how to open the link, and how to close it. */
internal class Session(
    val source: MeshSource,
    val open: suspend () -> Unit = {},
    val close: suspend () -> Unit = {},
    val seed: Long = nowMillis(),
    val demo: Boolean = false,
    val failureText: (Throwable) -> String = { unreachableText(null) },
)

/** A session on a live radio link; a failed open names [target]. */
private fun liveSession(link: LiveLink, target: String): Session =
    Session(link.source, link::connect, link::disconnect, failureText = { unreachableText(target) })

/**
 * The local network permission for one session: the first [check] asks, and after a denial later checks only look,
 * so a retrying link never prompts again but still picks up a grant made in system settings.
 */
internal class LocalNetworkGate(private val permit: suspend (ask: Boolean) -> Boolean) {
    var denied = false
        private set

    suspend fun check(): Boolean {
        denied = !permit(!denied)
        return !denied
    }
}

/** A live session over the network, which opens only once [gate] allows it. */
private fun networkSession(
    link: LiveLink,
    target: String,
    gate: LocalNetworkGate = LocalNetworkGate(::ensureLocalNetworkPermission),
): Session = Session(
    link.source,
    open = {
        if (!gate.check()) throw MeshtasticException.Transport(LOCAL_NETWORK_DENIED, null)
        link.connect()
    },
    close = link::disconnect,
    failureText = { if (gate.denied) LOCAL_NETWORK_DENIED else unreachableText(target) },
)

internal fun sessionFor(choice: LinkChoice): Session = when (choice) {
    LinkChoice.Demo -> {
        Log.i("app", "session demo")
        Session(DemoMeshSource(::nowMillis), demo = true)
    }
    LinkChoice.Replay -> {
        Log.i("app", "session replay")
        Session(LoopingReplaySource(DemoCapture::lines, speed = 1.0, nowMillis = ::nowMillis))
    }
    is LinkChoice.Tcp -> {
        val link = LiveLink.tcp(choice.host, choice.port) ?: error("TCP is not available on this target")
        Log.i("app", "session tcp ${choice.host}:${choice.port}")
        networkSession(link, "${choice.host}:${choice.port}")
    }
    is LinkChoice.Ble -> {
        val link = LiveLink.ble(choice.radio) ?: error("Bluetooth is not available on this target")
        Log.i("app", "session ble ${choice.radio.name} ${choice.radio.address}")
        liveSession(link, choice.radio.name ?: choice.radio.address)
    }
    is LinkChoice.Serial -> {
        val link = LiveLink.serial(choice.path) ?: error("USB serial is not available on this target")
        Log.i("app", "session serial ${choice.path}")
        liveSession(link, choice.label)
    }
    is LinkChoice.WebSocket -> {
        require(choice.url.startsWith("ws://") || choice.url.startsWith("wss://")) {
            "WebSocket address must start with ws:// or wss://"
        }
        val link = LiveLink.webSocket(choice.url)
        Log.i("app", "session websocket ${choice.url}")
        networkSession(link, choice.url)
    }
}

/** Which screen shows and why: the running session, why the last start failed, and a pending reconnect. */
@Stable
internal class AppSession(
    private val settings: Settings,
    session: Session?,
    failure: String?,
    pending: PendingConnect?,
) {
    var session by mutableStateOf(session)
    var failure by mutableStateOf(failure)
    var pending by mutableStateOf(pending)

    /** Starts [choice] and saves it as the link to reconnect to; a link that cannot start shows why. */
    fun start(choice: LinkChoice) {
        try {
            session = sessionFor(choice)
            failure = null
            pending = null
            settings.link = choice
        } catch (e: IllegalArgumentException) {
            failStart(e) // an address the endpoint parsers reject (sessionFor's and the SDK's require())
        } catch (e: IllegalStateException) {
            failStart(e) // a transport this target lacks (sessionFor's error())
        } catch (e: MeshtasticException) {
            failStart(e) // a serial port that cannot be opened: not granted, detached or gone
        }
    }

    private fun failStart(e: Exception) {
        Log.e("app", "could not start session", e)
        failure = startFailureText(e)
    }

    /** Back to the connect screen, with no reconnect pending. */
    fun stop() {
        session = null
        pending = null
    }
}
