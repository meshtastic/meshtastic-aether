@file:OptIn(ExperimentalComposeUiApi::class)

package org.meshtastic.aether

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.delay
import org.meshtastic.aether.ui.Controls
import org.meshtastic.aether.ui.KeyAction
import org.meshtastic.aether.ui.keyActionFor
import org.meshtastic.aether.ui.nowMillis
import org.meshtastic.aether.ui.overlayExpired

private const val IDLE_CHECK_MS = 1_000L
private const val CURSOR_HIDE_MS = 3_000L

/** When the viewer last gave input, and whether the pointer shows; the HUD and cursor timers read them. */
@Stable
internal class SceneInput {
    var lastInput by mutableLongStateOf(nowMillis())
    var cursorVisible by mutableStateOf(true)
    var pointerMoves by mutableIntStateOf(0)
}

/** Closes the HUD after it sits idle, never while a control is held or a screen reader is on. */
@Composable
internal fun HudAutoHide(controls: Controls, input: SceneInput, screenReaderActive: Boolean) {
    val screenReader by rememberUpdatedState(screenReaderActive)
    LaunchedEffect(controls.overlayOpen) {
        if (!controls.overlayOpen) return@LaunchedEffect
        input.lastInput = nowMillis()
        while (true) {
            val held = hudHeld(controls.held, screenReader)
            if (held) input.lastInput = nowMillis()
            if (overlayExpired(nowMillis() - input.lastInput, controls.driversOpen, held)) break
            delay(IDLE_CHECK_MS)
        }
        controls.overlayOpen = false
    }
}

/** Hides the pointer [CURSOR_HIDE_MS] after it last moved, and shows it again when the scene goes. */
@Composable
internal fun CursorAutoHide(input: SceneInput) {
    LaunchedEffect(input.pointerMoves) {
        delay(CURSOR_HIDE_MS)
        input.cursorVisible = false
    }
    DisposableEffect(Unit) { onDispose { input.cursorVisible = true } }
}

/**
 * Keys on the scene: any key counts as input, Back closes the HUD, and the rest go through [perform]. With the HUD open,
 * Up and Down first move [focus] to a control in that direction, so the update panel above the toolbar is reachable;
 * with none there they change the volume as usual.
 */
internal fun Modifier.sceneKeys(
    controls: Controls,
    input: SceneInput,
    perform: State<(KeyAction) -> Boolean>,
    focus: FocusManager,
): Modifier = this
    .onPreviewKeyEvent { event ->
        input.lastInput = nowMillis()
        if (event.key != Key.Back) return@onPreviewKeyEvent false
        // Down is held back while the HUD is up so only the release acts; with the HUD closed Back is the system's.
        if (event.type == KeyEventType.KeyUp) controls.back() else controls.overlayOpen
    }
    .onKeyEvent { event ->
        val action = keyActionFor(event.key) ?: return@onKeyEvent false
        val wanted = if (action.repeats) KeyEventType.KeyDown else KeyEventType.KeyUp
        if (event.type != wanted) return@onKeyEvent false
        val direction = when (action) {
            KeyAction.VolumeUp -> FocusDirection.Up
            KeyAction.VolumeDown -> FocusDirection.Down
            else -> null
        }
        if (controls.overlayOpen && direction != null && focus.moveFocus(direction)) return@onKeyEvent true
        perform.value(action)
    }

/** Pointer activity on the scene: movement counts as input and shows the cursor, and a tap toggles the HUD. */
internal fun Modifier.scenePointer(controls: Controls, input: SceneInput): Modifier = this
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type != PointerEventType.Enter && e.type != PointerEventType.Exit) input.lastInput = nowMillis()
                if (e.type == PointerEventType.Move || e.type == PointerEventType.Press) {
                    input.cursorVisible = true
                    input.pointerMoves++
                }
            }
        }
    }
    .pointerInput(Unit) { detectTapGestures { controls.overlayOpen = !controls.overlayOpen } }

/**
 * The scene as a screen reader sees it: a button whose click shows or hides the HUD, the same as a tap, and whose
 * state is the link [status], announced politely when it changes. It goes on the scene drawing, not on the HUD's
 * parent, so the HUD's controls stay separate nodes.
 */
internal fun Modifier.sceneSemantics(controls: Controls, status: String?): Modifier = semantics {
    role = Role.Button
    contentDescription = SCENE_LABEL
    if (status != null) stateDescription = status
    liveRegion = LiveRegionMode.Polite
    onClick(label = if (controls.overlayOpen) "Hide controls" else "Show controls") {
        controls.overlayOpen = !controls.overlayOpen
        true
    }
}

internal const val SCENE_LABEL = "Mesh scene"

/**
 * Whether a HUD control is in use: the auto-hide timer waits while one is focused or pressed, and while a screen
 * reader is on (it needs time to read the controls). The common API has no screen-reader query, so the host reports it.
 */
internal fun hudHeld(controlInUse: Boolean, screenReader: Boolean): Boolean = controlInUse || screenReader
