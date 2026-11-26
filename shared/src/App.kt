package org.meshtastic.aether

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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import org.meshtastic.aether.audio.createAudioSink
import org.meshtastic.aether.log.Log
import org.meshtastic.aether.mesh.DemoMeshSource
import org.meshtastic.aether.mesh.LinkState
import org.meshtastic.aether.mesh.MeshSource
import org.meshtastic.aether.theme.AmbientTheme
import org.meshtastic.aether.theme.LocalAmbientPalette
import org.meshtastic.aether.theme.PaletteChoice
import org.meshtastic.aether.ui.AmbientEngine
import org.meshtastic.aether.ui.ConnectScreen
import org.meshtastic.aether.ui.Controls
import org.meshtastic.aether.ui.Countdown
import org.meshtastic.aether.ui.Effect
import org.meshtastic.aether.ui.LinkChoice
import org.meshtastic.aether.ui.LiveLink
import org.meshtastic.aether.ui.LoopingReplaySource
import org.meshtastic.aether.ui.PendingConnect
import org.meshtastic.aether.ui.ScreenGutter
import org.meshtastic.aether.ui.Settings
import org.meshtastic.aether.ui.StatusOverlay
import org.meshtastic.aether.ui.controlsHint
import org.meshtastic.aether.ui.fadeIn
import org.meshtastic.aether.ui.overlayExpired
import org.meshtastic.aether.ui.resolveLook
import org.meshtastic.aether.ui.startFailureText
import org.meshtastic.aether.ui.statusLineText
import org.meshtastic.aether.ui.unreachableText
import org.meshtastic.aether.ui.keyActionFor
import org.meshtastic.aether.ui.nowMillis
import org.meshtastic.aether.visual.AmbientScene
import org.meshtastic.aether.mesh.DemoCapture

private const val AUTO_CONNECT_SECONDS = 5
private const val SECOND_MS = 1_000L
private const val IDLE_CHECK_MS = 1_000L
private const val FADE_STEP_MS = 33L
private const val SAVE_DEBOUNCE_MS = 500L
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
    val failureText: (Throwable) -> String = { unreachableText(null) },
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
        Session(link.source, link::connect, link::disconnect, failureText = { unreachableText("${choice.host}:${choice.port}") })
    }
    is LinkChoice.Ble -> {
        val link = LiveLink.ble(choice.radio) ?: error("Bluetooth is not available on this target")
        Log.i("app", "session ble ${choice.radio.name} ${choice.radio.address}")
        Session(link.source, link::connect, link::disconnect, failureText = { unreachableText(choice.radio.name ?: choice.radio.address) })
    }
    is LinkChoice.WebSocket -> {
        require(choice.url.startsWith("ws://") || choice.url.startsWith("wss://")) { "WebSocket address must start with ws:// or wss://" }
        val link = LiveLink.webSocket(choice.url)
        Log.i("app", "session websocket ${choice.url}")
        Session(link.source, link::connect, link::disconnect, failureText = { unreachableText(choice.url) })
    }
}

/**
 * The app: a tap-to-start screen where the browser needs a gesture, then the connect screen, then the scene.
 *
 * - [onToggleFullscreen] is the host's fullscreen switch, null where the target has none; [isFullscreen] is its state.
 * - [initialLink] skips the connect screen; [startupError] is a launch argument the host could not read, shown as a
 *   failure on the connect screen.
 * - [look] is a launch flag that overrides the saved look; with no flag and nothing saved a [launchedFullscreen] start uses
 *   the Projector look.
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
    look: PaletteChoice? = null,
    launchedFullscreen: Boolean = false,
) {
    val platform = remember { getPlatform() }
    val settings = remember { Settings(createKeyValueStore()) }
    var palette by remember { mutableStateOf(resolveLook(look, settings.savedPalette, launchedFullscreen)) }
    AmbientTheme(palette) {
        val startup = remember { initialLink?.let { runCatching { sessionFor(it) } } }
        var started by remember { mutableStateOf(!platform.needsTapToStart) }
        var session by remember { mutableStateOf(startup?.getOrNull()) }
        var failure by remember { mutableStateOf(startupError ?: startup?.exceptionOrNull()?.let(::startFailureText)) }
        val saved = remember { if (platform.autoConnects && initialLink == null && startupError == null) settings.link else null }
        var pending by remember { mutableStateOf(saved?.let { PendingConnect(it, Countdown(AUTO_CONNECT_SECONDS)) }) }

        fun start(choice: LinkChoice) {
            try {
                session = sessionFor(choice)
                failure = null
                pending = null
                settings.link = choice
            } catch (e: Exception) {
                Log.e("app", "could not start session", e)
                failure = startFailureText(e)
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
                    settings = settings,
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
    settings: Settings,
    platform: Platform,
    palette: PaletteChoice,
    onPalette: (PaletteChoice) -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    isFullscreen: Boolean,
    hiddenCursor: PointerIcon?,
    onStop: () -> Unit,
) {
    val engine = remember(session) {
        AmbientEngine(session.source, createAudioSink(), session.seed, platform.lookaheadMillis, pinFormFull = session.demo, failureText = session.failureText)
            .also { platform.resumeAudio() }
    }
    LaunchedEffect(engine) {
        try {
            engine.run(session.open)
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }
    val controls = remember(engine) { Controls(settings.volume).also { it.muted = settings.muted } }
    LaunchedEffect(controls.volume, controls.muted) {
        delay(SAVE_DEBOUNCE_MS)
        settings.volume = controls.volume
        settings.muted = controls.muted
    }
    var fade by remember(engine) { mutableFloatStateOf(0f) }
    LaunchedEffect(engine) {
        val start = nowMillis()
        while (fade < 1f) {
            delay(FADE_STEP_MS)
            fade = fadeIn(nowMillis() - start)
        }
    }
    LaunchedEffect(controls.effectiveVolume, fade) { engine.volume = controls.effectiveVolume * fade }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    var lastInput by remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(controls.overlayOpen) {
        if (!controls.overlayOpen) return@LaunchedEffect
        lastInput = nowMillis()
        while (!overlayExpired(nowMillis() - lastInput, controls.driversOpen)) delay(IDLE_CHECK_MS)
        controls.overlayOpen = false
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
            .onPreviewKeyEvent {
                lastInput = nowMillis()
                false
            }
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
                        if (e.type != PointerEventType.Enter && e.type != PointerEventType.Exit) lastInput = nowMillis()
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
        StatusLine(engine.status.link, engine.status.error, Modifier.align(Alignment.TopCenter))
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

/** A small line while the link is not up or failing to open; the field itself is desaturated by the scene. */
@Composable
private fun StatusLine(link: LinkState, error: String?, modifier: Modifier = Modifier) {
    val text = statusLineText(link, error) ?: return
    Text(
        text,
        modifier = modifier.padding(top = ScreenGutter),
        style = MaterialTheme.typography.bodyLarge,
        color = LocalAmbientPalette.current.text.copy(alpha = STATUS_ALPHA),
    )
}

/** "Click or press H for controls", shown for the first [HINT_SHOW_MS] and then faded out. */
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
            controlsHint(hasKeyboard),
            modifier = modifier.padding(bottom = ScreenGutter + 4.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = LocalAmbientPalette.current.text.copy(alpha = alpha),
        )
    }
}
