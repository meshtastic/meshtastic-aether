@file:OptIn(ExperimentalComposeUiApi::class)

package org.meshtastic.aether

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.meshtastic.aether.audio.createAudioSink
import org.meshtastic.aether.mesh.LinkState
import org.meshtastic.aether.theme.LocalAmbientPalette
import org.meshtastic.aether.ui.AmbientEngine
import org.meshtastic.aether.ui.Controls
import org.meshtastic.aether.ui.Effect
import org.meshtastic.aether.ui.EngineOptions
import org.meshtastic.aether.ui.EngineTiming
import org.meshtastic.aether.ui.HostCommands
import org.meshtastic.aether.ui.HudHost
import org.meshtastic.aether.ui.HudInput
import org.meshtastic.aether.ui.KeyAction
import org.meshtastic.aether.ui.ScreenGutter
import org.meshtastic.aether.ui.Settings
import org.meshtastic.aether.ui.StatusOverlay
import org.meshtastic.aether.ui.appUpdater
import org.meshtastic.aether.ui.controlsHint
import org.meshtastic.aether.ui.fadeIn
import org.meshtastic.aether.ui.nowMillis
import org.meshtastic.aether.ui.statusLineShiftSteps
import org.meshtastic.aether.midi.openLiveOutput
import org.meshtastic.aether.ui.statusLineText
import org.meshtastic.aether.ui.statusLineVisible
import org.meshtastic.aether.visual.AmbientScene
import org.meshtastic.aether.visual.SpatialSource

private const val FADE_STEP_MS = 33L
private const val SAVE_DEBOUNCE_MS = 500L
private const val HINT_SHOW_MS = 8_000L
private const val STATUS_TICK_MS = 1_000L
private val StatusShift = 12.dp
private const val STATUS_ALPHA = 0.6f
private const val HINT_ALPHA = 0.5f

/** A running [session]: the scene, its keys and pointer, and the HUD; [onStop] returns to the connect screen. */
@Composable
internal fun Running(
    session: Session,
    env: AppEnvironment,
    look: LookState,
    host: HostWindow,
    onStop: () -> Unit,
) {
    val platform = env.platform
    val haptics = LocalHapticFeedback.current
    val engine = rememberEngine(session, platform, env.midi)
    val palette = LocalAmbientPalette.current
    val controls = rememberControls(engine, env.settings)
    val disconnect = {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onStop()
    }
    // One action path for the keys, the host menu and Back, so every route gets the two-press disconnect and the
    // haptics.
    val perform = rememberUpdatedState<(KeyAction) -> Boolean> { action ->
        performAction(action, controls, host, disconnect)
    }
    HostCommands(host.commands, controls.muted, perform)
    SessionBack(platform.hasSystemBack, controls, onStop)
    MasterVolume(engine, controls)
    val focus = remember { FocusRequester() }
    // The HUD takes focus when it opens; when it closes the scene takes it back, so the next key (Select on a remote)
    // reaches it.
    LaunchedEffect(controls.overlayOpen) { if (!controls.overlayOpen) runCatching { focus.requestFocus() } }
    val input = remember { SceneInput() }
    HudAutoHide(controls, input, host.screenReaderActive)
    MuteHaptics(controls)
    CursorAutoHide(input)
    val cursorIcon = if (!input.cursorVisible && host.hiddenCursor != null) host.hiddenCursor else PointerIcon.Default

    Box(
        Modifier.fillMaxSize()
            .pointerHoverIcon(cursorIcon)
            .focusRequester(focus)
            .focusable()
            .sceneKeys(controls, input, perform)
            .scenePointer(controls, input),
    ) {
        AmbientScene(engine, Modifier.fillMaxSize())
        StatusLine(engine.status.link, engine.status.error, Modifier.align(Alignment.TopCenter))
        ControlsHint(platform.hasKeyboard, platform.isTelevision, Modifier.align(Alignment.BottomCenter))
        if (controls.overlayOpen) {
            StatusOverlay(
                status = engine.status,
                controls = controls,
                host = HudHost(
                    look.palette,
                    look::choose,
                    disconnect,
                    host.onToggleFullscreen,
                    appUpdater,
                    host.onEnterSpatial?.let { enter -> { enter(SpatialSource(engine, palette)) } },
                ),
                input = HudInput(platform.hasKeyboard, platform.isTelevision),
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

/** The engine for [session], playing from the moment it exists; its link opens once it runs and closes after. */
@Composable
private fun rememberEngine(session: Session, platform: Platform, midiName: String?): AmbientEngine {
    val engine = remember(session) {
        val sink = createAudioSink()
        AmbientEngine(
            session.source,
            sink,
            session.seed,
            EngineTiming(platform.lookaheadMillis),
            EngineOptions(
                pinFormFull = session.demo,
                failureText = session.failureText,
                midi = midiName?.let { name -> { rate -> openLiveOutput(name, rate) } },
            ),
        ).also { platform.resumeAudio() }
    }
    LaunchedEffect(engine) {
        try {
            engine.run(session.open)
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }
    return engine
}

/** The viewer's controls, starting from the saved volume, mute and headphones and saving their changes. */
@Composable
private fun rememberControls(engine: AmbientEngine, settings: Settings): Controls {
    val controls = remember(engine) {
        Controls(settings.volume, settings.headphones).also { it.muted = settings.muted }
    }
    LaunchedEffect(controls.headphones, engine) {
        engine.headphones = controls.headphones
        settings.headphones = controls.headphones
    }
    LaunchedEffect(controls.volume, controls.muted) {
        delay(SAVE_DEBOUNCE_MS)
        settings.volume = controls.volume
        settings.muted = controls.muted
    }
    return controls
}

/** Applies [action] and carries out the effect it asks for; true when the action used the key. */
private fun performAction(action: KeyAction, controls: Controls, host: HostWindow, disconnect: () -> Unit): Boolean {
    val result = controls.apply(action, host.isFullscreen, host.onToggleFullscreen != null)
    when (result.effect) {
        Effect.ToggleFullscreen -> host.onToggleFullscreen?.invoke()
        Effect.Disconnect -> disconnect()
        Effect.None -> Unit
    }
    return result.consumed
}

/**
 * Back closes the HUD, then returns to the connect screen. Only where the system has a Back control: on desktop the
 * handler can be driven by Escape, which already means close the HUD or leave full screen.
 */
@Composable
private fun SessionBack(enabled: Boolean, controls: Controls, onStop: () -> Unit) {
    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        backState,
        isBackEnabled = enabled,
        onBackCompleted = { if (controls.overlayOpen) controls.overlayOpen = false else onStop() },
    )
}

/** The engine's volume: the viewer's volume, silent while muted, under the session's fade-in. */
@Composable
private fun MasterVolume(engine: AmbientEngine, controls: Controls) {
    var fade by remember(engine) { mutableFloatStateOf(0f) }
    LaunchedEffect(engine) {
        val start = nowMillis()
        while (fade < 1f) {
            delay(FADE_STEP_MS)
            fade = fadeIn(nowMillis() - start)
        }
    }
    LaunchedEffect(controls.effectiveVolume, fade) { engine.volume = controls.effectiveVolume * fade }
}

/** A haptic tick each time the mute changes, on whichever route changed it. */
@Composable
private fun MuteHaptics(controls: Controls) {
    val haptics = LocalHapticFeedback.current
    var muteSeen by remember(controls) { mutableStateOf(controls.muted) }
    LaunchedEffect(controls.muted) {
        if (controls.muted == muteSeen) return@LaunchedEffect
        muteSeen = controls.muted
        haptics.performHapticFeedback(if (controls.muted) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    }
}

/**
 * A small line while the link is not up or failing to open, shown for 10 s after the text changes and then for 10 s
 * each minute, a step lower each time so it never sits on the same pixels; the field itself is desaturated by the
 * scene.
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
            modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = ScreenGutter + StatusShift * statusLineShiftSteps(elapsed)),
            style = MaterialTheme.typography.bodyLarge,
            color = LocalAmbientPalette.current.text.copy(alpha = alpha),
        )
    }
}

private class StringHolder(var text: String = "")

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
