@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.meshtastic.aether.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.meshtastic.aether.theme.PaletteChoice
import org.meshtastic.aether.update.Updater

private val OVERLAY_GLYPH_SIZE = 28.dp
private val DRIVERS_PANEL_WIDTH = 360.dp
private val TOOLBAR_CLEARANCE = 72.dp

/** The side Drivers panel slides in over a quarter of its height, half the travel of the bottom panels. */
private const val SIDE_PANEL_SLIDE_DIVISOR = 4

/** The shortcut line shown where a keyboard is the usual input. */
internal const val SHORTCUTS =
    "H HUD - F fullscreen - M mute - P headphones - Up and Down volume - D twice to disconnect - Esc close"

/** The line that appears after the first Disconnect press. */
internal const val DISCONNECT_HINT = "Press Disconnect again within 3 seconds"

/**
 * What the HUD needs from the session around it: the look in force and where a new pick goes, the Disconnect action,
 * the host's fullscreen switch (null where it has none), the updater (null where the build has none) and the way into
 * the host's headset or AR view (null where it has none).
 */
@Immutable
internal data class HudHost(
    val palette: PaletteChoice,
    val onPalette: (PaletteChoice) -> Unit,
    val onDisconnect: () -> Unit,
    val onToggleFullscreen: (() -> Unit)?,
    val updater: Updater? = null,
    val onEnterSpatial: (() -> Unit)? = null,
)

/**
 * How the viewer drives the HUD: a keyboard gets tooltips and the shortcut line, a television remote gets D-pad
 * handling on the volume slider.
 */
@Immutable
internal data class HudInput(val hasKeyboard: Boolean, val isTelevision: Boolean = false)

/**
 * The hidden HUD, filling its parent and drawing nothing while closed: a floating toolbar at bottom-center (mute,
 * volume, Look menu, headphones, Drivers, fullscreen, Disconnect), a small status surface at top-start, the Drivers
 * panel at the end on wide windows and above the toolbar on narrow ones, and the Disconnect hint. Opens and closes on
 * [Controls.overlayOpen]; not part of the scene.
 */
@Composable
internal fun StatusOverlay(
    status: EngineStatus,
    controls: Controls,
    host: HudHost,
    input: HudInput,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val open = controls.overlayOpen
    // Starts hidden so the first open animates in even when this composable is created already open.
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = open
    val armed = controls.disconnect.armed
    HudLifecycle(controls, armed)
    val enter = fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 2 }
    val exit = fadeOut(motion.slowEffectsSpec()) + slideOutVertically(motion.defaultSpatialSpec()) { it / 2 }

    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = widthClass(maxWidth) == WidthClass.Expanded
        val compact = widthClass(maxWidth) == WidthClass.Compact
        val halfHeight = maxHeight / 2

        AnimatedVisibility(
            visibleState = visible,
            modifier = Modifier.align(Alignment.TopStart),
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.slowEffectsSpec()),
        ) {
            StatusSurface(status, input)
        }

        AnimatedVisibility(
            visible = open && controls.driversOpen && wide,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn(motion.defaultEffectsSpec()) +
                slideInVertically(motion.defaultSpatialSpec()) { it / SIDE_PANEL_SLIDE_DIVISOR },
            exit = fadeOut(motion.slowEffectsSpec()),
        ) {
            DriversSurface(
                status,
                Modifier.holdsHud(controls)
                    .widthIn(max = DRIVERS_PANEL_WIDTH)
                    .fillMaxHeight()
                    .padding(bottom = TOOLBAR_CLEARANCE),
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = FloatingToolbarDefaults.ScreenOffset),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(visible = open && controls.driversOpen && !wide, enter = enter, exit = exit) {
                DriversSurface(status, Modifier.holdsHud(controls).heightIn(max = halfHeight))
            }
            if (host.updater != null) {
                AnimatedVisibility(visible = open && controls.updateOpen, enter = enter, exit = exit) {
                    UpdatePanel(host.updater, canBrowse = !input.isTelevision, Modifier.holdsHud(controls))
                }
            }
            DisconnectHint(armed)
            AnimatedVisibility(visibleState = visible, enter = enter, exit = exit) {
                HudToolbar(controls, host, input, stacked = compact)
            }
        }
    }
}

/** Disarms an armed Disconnect after its window, and drops the HUD's hold on the timer when the HUD goes away. */
@Composable
private fun HudLifecycle(controls: Controls, armed: Boolean) {
    LaunchedEffect(armed) {
        if (!armed) return@LaunchedEffect
        delay(DISCONNECT_CONFIRM_MILLIS)
        controls.disconnect.clear()
    }
    DisposableEffect(Unit) {
        onDispose {
            controls.disconnect.clear()
            controls.focusWithin = false
            controls.pressed = false
        }
    }
}

/** [DISCONNECT_HINT] in an error card while the first Disconnect press waits for the second. */
@Composable
private fun DisconnectHint(armed: Boolean) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = armed,
        enter = fadeIn(motion.defaultEffectsSpec()),
        exit = fadeOut(motion.slowEffectsSpec()),
    ) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
            Text(
                DISCONNECT_HINT,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** Link, nodes, key and section; its text is the same status the title screen words use. */
@Composable
private fun StatusSurface(status: EngineStatus, input: HudInput) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.padding(ScreenGutter / 2).widthIn(max = MaxContentWidth),
        color = colors.surfaceContainer,
        contentColor = colors.onSurface,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AetherGlyph(OVERLAY_GLYPH_SIZE)
                Text("Aether", style = MaterialTheme.typography.titleMedium)
            }
            Text("Link: ${linkStatusText(status.link)}", style = MaterialTheme.typography.bodyLarge)
            Text("Nodes: ${status.nodes}", style = MaterialTheme.typography.bodyLarge)
            Text("Key: ${status.key} ${status.mode.lowercase()}", style = MaterialTheme.typography.bodyLarge)
            Text("Section: ${status.section}", style = MaterialTheme.typography.bodyLarge)
            status.error?.let { Text("Error: $it", style = MaterialTheme.typography.bodyLarge, color = colors.error) }
            Text(
                when {
                    input.isTelevision -> "Back to hide"
                    input.hasKeyboard -> SHORTCUTS
                    else -> "Tap to hide"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DriversSurface(status: EngineStatus, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.padding(horizontal = ScreenGutter / 2).widthIn(max = MaxContentWidth),
        color = colors.surfaceContainer,
        contentColor = colors.onSurface,
        shape = MaterialTheme.shapes.large,
    ) {
        DriversPanel(
            status.params.describe(status.turningDirection),
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        )
    }
}

/**
 * Tracks whether a pointer is down or focus is inside, so the auto-hide timer waits while the viewer is
 * mid-gesture.
 */
internal fun Modifier.holdsHud(controls: Controls): Modifier = this
    .onFocusChanged { controls.focusWithin = it.hasFocus }
    .pointerInput(controls) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                controls.pressed = event.changes.any { it.pressed }
            }
        }
    }
