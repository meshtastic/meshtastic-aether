package org.meshtastic.aether

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
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
import org.meshtastic.aether.ui.HostCommands
import org.meshtastic.aether.ui.KeyAction
import org.meshtastic.aether.ui.AppCommands
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
import org.meshtastic.aether.ui.autoConnectLink
import org.meshtastic.aether.ui.statusLineShiftSteps
import org.meshtastic.aether.ui.statusLineText
import org.meshtastic.aether.ui.statusLineVisible
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
private const val STATUS_TICK_MS = 1_000L
private val StatusShift = 12.dp
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
    ask: Boolean = false,
    screenReaderActive: Boolean = false,
    commands: AppCommands? = null,
) {
    val platform = remember { getPlatform() }
    val settings = remember { Settings(createKeyValueStore()) }
    AppRoot(
        settings, platform, onToggleFullscreen, initialLink, startupError, isFullscreen, hiddenCursor, look, launchedFullscreen,
        ask, screenReaderActive, commands,
    )
}

/** [App] with its settings and platform supplied, so a test can run it on a fake store. */
@Composable
internal fun AppRoot(
    settings: Settings,
    platform: Platform,
    onToggleFullscreen: (() -> Unit)? = null,
    launchLink: LinkChoice? = null,
    startupError: String? = null,
    isFullscreen: Boolean = false,
    hiddenCursor: PointerIcon? = null,
    look: PaletteChoice? = null,
    launchedFullscreen: Boolean = false,
    ask: Boolean = false,
    screenReaderActive: Boolean = false,
    commands: AppCommands? = null,
) {
    val initialLink = if (ask) null else launchLink
    var palette by remember { mutableStateOf(resolveLook(look, settings.savedPalette, launchedFullscreen)) }
    AmbientTheme(palette) {
        val startup = remember { initialLink?.let { runCatching { sessionFor(it) } } }
        var started by remember { mutableStateOf(!platform.needsTapToStart) }
        var session by remember { mutableStateOf(startup?.getOrNull()) }
        var failure by remember { mutableStateOf(startupError ?: startup?.exceptionOrNull()?.let(::startFailureText)) }
        val saved = remember { autoConnectLink(settings.link, ask, platform.autoConnects, initialLink != null, startupError != null) }
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
        Box(Modifier.fillMaxSize().background(colors.backgroundBottom)) {
            val current = session
            when {
                !started -> Box(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
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
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                        .then(if (platform.isTelevision) Modifier.padding(horizontal = TV_MARGIN, vertical = TV_MARGIN / 2) else Modifier),
                    focusPrimary = platform.isTelevision,
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
                    screenReaderActive = screenReaderActive,
                    commands = commands,
                    onStop = {
                        session = null
                        pending = null
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
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
    screenReaderActive: Boolean,
    commands: AppCommands?,
    onStop: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
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
    val controls = remember(engine) { Controls(settings.volume, settings.headphones).also { it.muted = settings.muted } }
    LaunchedEffect(controls.headphones, engine) {
        engine.headphones = controls.headphones
        settings.headphones = controls.headphones
    }
    LaunchedEffect(controls.volume, controls.muted) {
        delay(SAVE_DEBOUNCE_MS)
        settings.volume = controls.volume
        settings.muted = controls.muted
    }
    // One action path for the keys, the host menu and Back, so every route gets the two-press disconnect and the haptics.
    val perform = rememberUpdatedState<(KeyAction) -> Boolean> { action ->
        val result = controls.apply(action, isFullscreen, onToggleFullscreen != null)
        when (result.effect) {
            Effect.ToggleFullscreen -> onToggleFullscreen?.invoke()
            Effect.Disconnect -> {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onStop()
            }
            Effect.None -> Unit
        }
        result.consumed
    }
    HostCommands(commands, controls.muted, perform)
    // Back closes the HUD, then returns to the connect screen. Only where the system has a Back control: on desktop the
    // handler can be driven by Escape, which already means close the HUD or leave full screen.
    BackHandler(enabled = platform.hasSystemBack) { if (controls.overlayOpen) controls.overlayOpen = false else onStop() }
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
    val screenReader by rememberUpdatedState(screenReaderActive)
    LaunchedEffect(controls.overlayOpen) {
        if (!controls.overlayOpen) return@LaunchedEffect
        lastInput = nowMillis()
        while (true) {
            val held = hudHeld(controls.held, screenReader)
            if (held) lastInput = nowMillis()
            if (overlayExpired(nowMillis() - lastInput, controls.driversOpen, held)) break
            delay(IDLE_CHECK_MS)
        }
        controls.overlayOpen = false
    }

    var muteSeen by remember(controls) { mutableStateOf(controls.muted) }
    LaunchedEffect(controls.muted) {
        if (controls.muted == muteSeen) return@LaunchedEffect
        muteSeen = controls.muted
        haptics.performHapticFeedback(if (controls.muted) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
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
            .onPreviewKeyEvent { event ->
                lastInput = nowMillis()
                if (event.key != Key.Back) return@onPreviewKeyEvent false
                // Down is held back while the HUD is up so only the release acts; with the HUD closed Back is the system's.
                if (event.type == KeyEventType.KeyUp) controls.back() else controls.overlayOpen
            }
            .onKeyEvent { event ->
                val action = keyActionFor(event.key) ?: return@onKeyEvent false
                val wanted = if (action.repeats) KeyEventType.KeyDown else KeyEventType.KeyUp
                if (event.type != wanted) return@onKeyEvent false
                perform.value(action)
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
        ControlsHint(platform.hasKeyboard, platform.isTelevision, Modifier.align(Alignment.BottomCenter))
        if (controls.overlayOpen) {
            StatusOverlay(
                status = engine.status,
                controls = controls,
                palette = palette,
                onPalette = onPalette,
                onDisconnect = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onStop()
                },
                onToggleFullscreen = onToggleFullscreen,
                hasKeyboard = platform.hasKeyboard,
                modifier = Modifier.align(Alignment.TopStart),
                isTelevision = platform.isTelevision,
            )
        }
    }
}

/**
 * Whether a HUD control is in use: the auto-hide timer waits while one is focused or pressed, and while a screen
 * reader is on (it needs time to read the controls). The common API has no screen-reader query, so the host reports it.
 */
internal fun hudHeld(controlInUse: Boolean, screenReader: Boolean): Boolean = controlInUse || screenReader

/**
 * A small line while the link is not up or failing to open, shown for 10 s after the text changes and then for 10 s
 * each minute, a step lower each time so it never sits on the same pixels; the field itself is desaturated by the scene.
 */
@Composable
private fun StatusLine(link: LinkState, error: String?, modifier: Modifier = Modifier) {
    val text = statusLineText(link, error)
    var changedAt by remember(text) { mutableLongStateOf(nowMillis()) }
    var now by remember(text) { mutableLongStateOf(changedAt) }
    LaunchedEffect(text) {
        if (text == null) return@LaunchedEffect
        while (true) {
            delay(STATUS_TICK_MS)
            now = nowMillis()
        }
    }
    val elapsed = now - changedAt
    val visible = text != null && statusLineVisible(elapsed)
    val alpha by animateFloatAsState(if (visible) STATUS_ALPHA else 0f, MaterialTheme.motionScheme.slowEffectsSpec())
    val shown = remember { StringHolder() }
    if (text != null) shown.text = text
    if (alpha > 0f) {
        Text(
            shown.text,
            modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(top = ScreenGutter + StatusShift * statusLineShiftSteps(elapsed)),
            style = MaterialTheme.typography.bodyLarge,
            color = LocalAmbientPalette.current.text.copy(alpha = alpha),
        )
    }
}

private class StringHolder(var text: String = "")

/** Extra inset on a television, where the picture edge can be cropped by overscan. */
private val TV_MARGIN = 48.dp

/** "Click or press H for controls", shown for the first [HINT_SHOW_MS] and then faded out. */
@Composable
private fun ControlsHint(hasKeyboard: Boolean, isTelevision: Boolean, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(HINT_SHOW_MS)
        show = false
    }
    val alpha by animateFloatAsState(if (show) HINT_ALPHA else 0f, MaterialTheme.motionScheme.slowEffectsSpec())
    if (alpha > 0f) {
        Text(
            controlsHint(hasKeyboard, isTelevision),
            modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = ScreenGutter + 4.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = LocalAmbientPalette.current.text.copy(alpha = alpha),
        )
    }
}
