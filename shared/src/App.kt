package org.meshtastic.ambient

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.meshtastic.ambient.audio.createAudioSink
import org.meshtastic.ambient.log.Log
import org.meshtastic.ambient.mesh.DemoMeshSource
import org.meshtastic.ambient.mesh.LinkState
import org.meshtastic.ambient.mesh.MeshSource
import org.meshtastic.ambient.theme.AmbientTheme
import org.meshtastic.ambient.theme.LocalAmbientPalette
import org.meshtastic.ambient.theme.PaletteChoice
import org.meshtastic.ambient.ui.AmbientEngine
import org.meshtastic.ambient.ui.ConnectScreen
import org.meshtastic.ambient.ui.Controls
import org.meshtastic.ambient.ui.Countdown
import org.meshtastic.ambient.ui.Effect
import org.meshtastic.ambient.ui.LinkChoice
import org.meshtastic.ambient.ui.LiveLink
import org.meshtastic.ambient.ui.LoopingReplaySource
import org.meshtastic.ambient.ui.PendingConnect
import org.meshtastic.ambient.ui.ScreenGutter
import org.meshtastic.ambient.ui.Settings
import org.meshtastic.ambient.ui.StatusOverlay
import org.meshtastic.ambient.ui.keyActionFor
import org.meshtastic.ambient.ui.label
import org.meshtastic.ambient.ui.nowMillis
import org.meshtastic.ambient.visual.AmbientScene
import org.meshtastic.ambient.mesh.DemoCapture

private const val AUTO_CONNECT_SECONDS = 5
private const val SECOND_MS = 1_000L
private const val OVERLAY_AUTO_HIDE_MS = 15_000L
private const val ERROR_AUTO_HIDE_MS = 30_000L
private const val CURSOR_HIDE_MS = 3_000L
private const val HINT_SHOW_MS = 8_000L
private const val HINT_FADE_MS = 1_500
private const val STATUS_ALPHA = 0.6f
private const val HINT_ALPHA = 0.5f

/** One running session: where events come from, how to open the link, and how to close it. */
private class Session(
    val source: MeshSource,
    val open: suspend () -> Unit = {},
    val close: suspend () -> Unit = {},
    val seed: Long = nowMillis(),
    val demo: Boolean = false,
)

private fun sessionFor(choice: LinkChoice): Session = when (choice) {
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
        Session(link.source, link::connect, link::disconnect)
    }
    is LinkChoice.Ble -> {
        val link = LiveLink.ble(choice.radio) ?: error("Bluetooth is not available on this target")
        Log.i("app", "session ble ${choice.radio.name} ${choice.radio.address}")
        Session(link.source, link::connect, link::disconnect)
    }
    is LinkChoice.WebSocket -> {
        require(choice.url.startsWith("ws://") || choice.url.startsWith("wss://")) { "WebSocket address must start with ws:// or wss://" }
        val link = LiveLink.webSocket(choice.url)
        Log.i("app", "session websocket ${choice.url}")
        Session(link.source, link::connect, link::disconnect)
    }
}

private fun failureText(e: Throwable): String = e.message ?: e::class.simpleName ?: "could not start"

/**
 * The app: a tap-to-start screen where the browser needs a gesture, then the connect screen, then the scene.
 *
 * - [onToggleFullscreen] is the host's fullscreen switch, null where the target has none; [isFullscreen] is its state.
 * - [initialLink] skips the connect screen; [startupError] is a launch argument the host could not read, shown as a
 *   failure on the connect screen.
 * - [hiddenCursor] is a blank pointer icon the app shows after 3 s without pointer movement, null where the host
 *   cannot hide the cursor.
 */
@Composable
fun App(
    onToggleFullscreen: (() -> Unit)? = null,
    initialLink: LinkChoice? = null,
    startupError: String? = null,
    isFullscreen: Boolean = false,
    hiddenCursor: PointerIcon? = null,
) {
    val platform = remember { getPlatform() }
    val settings = remember { Settings(createKeyValueStore()) }
    var palette by remember { mutableStateOf(settings.palette) }
    AmbientTheme(palette) {
        val startup = remember { initialLink?.let { runCatching { sessionFor(it) } } }
        var started by remember { mutableStateOf(!platform.needsTapToStart) }
        var session by remember { mutableStateOf(startup?.getOrNull()) }
        var failure by remember { mutableStateOf(startupError ?: startup?.exceptionOrNull()?.let(::failureText)) }
        val saved = remember { if (platform.autoConnects && initialLink == null && startupError == null) settings.link else null }
        var pending by remember { mutableStateOf(saved?.let { PendingConnect(it.label(), Countdown(AUTO_CONNECT_SECONDS)) }) }

        fun start(choice: LinkChoice) {
            try {
                session = sessionFor(choice)
                failure = null
                pending = null
                settings.link = choice
            } catch (e: Exception) {
                Log.e("app", "could not start session", e)
                failure = failureText(e)
            }
        }

        LaunchedEffect(Unit) { if (startup?.isSuccess == true && initialLink != null) settings.link = initialLink }
        LaunchedEffect(pending, started) {
            val p = pending ?: return@LaunchedEffect
            if (!started || saved == null) return@LaunchedEffect
            while (!p.countdown.cancelled) {
                delay(SECOND_MS)
                if (p.countdown.tick()) {
                    start(saved)
                    break
                }
            }
        }

        val colors = LocalAmbientPalette.current
        Box(Modifier.fillMaxSize().background(colors.backgroundBottom).safeContentPadding()) {
            val current = session
            when {
                !started -> Box(
                    Modifier.fillMaxSize()
                        .clickable(onClickLabel = "Start", role = Role.Button) {
                            platform.resumeAudio()
                            started = true
                        }
                        .semantics { contentDescription = "Tap to start" },
                    contentAlignment = Alignment.Center,
                ) { Text("Tap to start", style = MaterialTheme.typography.headlineSmall, color = colors.text) }

                current == null -> ConnectScreen(
                    onChoose = ::start,
                    pending = pending,
                    failure = failure,
                    lastTcp = settings.link as? LinkChoice.Tcp,
                )

                else -> Running(
                    session = current,
                    platform = platform,
                    palette = palette,
                    onPalette = {
                        palette = it
                        settings.palette = it
                    },
                    onToggleFullscreen = onToggleFullscreen,
                    isFullscreen = isFullscreen,
                    hiddenCursor = hiddenCursor,
                    onStop = {
                        session = null
                        pending = null
                    },
                )
            }
        }
    }
}

@Composable
private fun Running(
    session: Session,
    platform: Platform,
    palette: PaletteChoice,
    onPalette: (PaletteChoice) -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    isFullscreen: Boolean,
    hiddenCursor: PointerIcon?,
    onStop: () -> Unit,
) {
    val engine = remember(session) {
        AmbientEngine(session.source, createAudioSink(), session.seed, platform.lookaheadMillis, pinFormFull = session.demo)
            .also { platform.resumeAudio() }
    }
    LaunchedEffect(engine) {
        try {
            engine.run(session.open)
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }
    val controls = remember(engine) { Controls(engine.volume) }
    LaunchedEffect(controls.effectiveVolume) { engine.volume = controls.effectiveVolume }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val error = engine.status.error
    LaunchedEffect(error) {
        if (error != null) {
            controls.overlayOpen = true
            delay(ERROR_AUTO_HIDE_MS)
            engine.dismissError()
            controls.overlayOpen = false
        }
    }
    LaunchedEffect(controls.overlayOpen, error) {
        if (error == null && controls.overlayOpen) {
            delay(OVERLAY_AUTO_HIDE_MS)
            controls.overlayOpen = false
        }
    }

    var cursorVisible by remember { mutableStateOf(true) }
    var pointerMoves by remember { mutableIntStateOf(0) }
    LaunchedEffect(pointerMoves) {
        delay(CURSOR_HIDE_MS)
        cursorVisible = false
    }
    DisposableEffect(Unit) { onDispose { cursorVisible = true } }
    val cursorIcon = if (!cursorVisible && hiddenCursor != null) hiddenCursor else PointerIcon.Default

    Box(
        Modifier.fillMaxSize()
            .pointerHoverIcon(cursorIcon)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                val action = keyActionFor(event.key) ?: return@onKeyEvent false
                val wanted = if (action.repeats) KeyEventType.KeyDown else KeyEventType.KeyUp
                if (event.type != wanted) return@onKeyEvent false
                val result = controls.apply(action, isFullscreen, onToggleFullscreen != null)
                when (result.effect) {
                    Effect.ToggleFullscreen -> onToggleFullscreen?.invoke()
                    Effect.Disconnect -> onStop()
                    Effect.None -> Unit
                }
                result.consumed
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Move || e.type == PointerEventType.Press) {
                            cursorVisible = true
                            pointerMoves++
                        }
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures { controls.overlayOpen = !controls.overlayOpen } },
    ) {
        AmbientScene(engine, Modifier.fillMaxSize())
        StatusLine(engine.status.link, Modifier.align(Alignment.TopCenter))
        ControlsHint(platform.hasKeyboard, Modifier.align(Alignment.BottomCenter))
        if (controls.overlayOpen) {
            StatusOverlay(
                status = engine.status,
                controls = controls,
                palette = palette,
                onPalette = onPalette,
                onDisconnect = onStop,
                onToggleFullscreen = onToggleFullscreen,
                hasKeyboard = platform.hasKeyboard,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

/** A small line while the link is not up; the field itself is desaturated by the scene. */
@Composable
private fun StatusLine(link: LinkState, modifier: Modifier = Modifier) {
    val text = when (link) {
        LinkState.Connecting -> "Connecting"
        LinkState.WarmingUp -> "Listening"
        LinkState.Lost -> "Link lost, retrying"
        else -> return
    }
    Text(
        text,
        modifier = modifier.padding(top = ScreenGutter),
        style = MaterialTheme.typography.bodyMedium,
        color = LocalAmbientPalette.current.text.copy(alpha = STATUS_ALPHA),
    )
}

/** "Tap or H for controls", shown for the first [HINT_SHOW_MS] and then faded out. */
@Composable
private fun ControlsHint(hasKeyboard: Boolean, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(HINT_SHOW_MS)
        show = false
    }
    val alpha by animateFloatAsState(if (show) HINT_ALPHA else 0f, tween(HINT_FADE_MS))
    if (alpha > 0f) {
        Text(
            if (hasKeyboard) "Tap or press H for controls" else "Tap for controls",
            modifier = modifier.padding(bottom = ScreenGutter + 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalAmbientPalette.current.text.copy(alpha = alpha),
        )
    }
}
