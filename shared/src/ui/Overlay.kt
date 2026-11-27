@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.meshtastic.aether.ui

import org.meshtastic.aether.update.Updater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.meshtastic.aether.theme.PaletteChoice
import kotlin.math.roundToInt

private const val PERCENT = 100
private val OVERLAY_GLYPH_SIZE = 28.dp
private val SLIDER_WIDTH = 140.dp
private val DRIVERS_PANEL_WIDTH = 360.dp
private val TOOLBAR_CLEARANCE = 72.dp

/** The shortcut line shown where a keyboard is the usual input. */
internal const val SHORTCUTS = "H HUD - F fullscreen - M mute - P headphones - Up and Down volume - D twice to disconnect - Esc close"

/** The line that appears after the first Disconnect press. */
internal const val DISCONNECT_HINT = "Press Disconnect again within 3 seconds"

/**
 * The hidden HUD, filling its parent and drawing nothing while closed: a floating toolbar at bottom-center (mute,
 * volume, Look menu, headphones, Drivers, fullscreen, Disconnect), a small status surface at top-start, the Drivers panel at the end
 * on wide windows and above the toolbar on narrow ones, and the Disconnect hint. Opens and closes on
 * [Controls.overlayOpen]; not part of the scene.
 */
@Composable
internal fun StatusOverlay(
    status: EngineStatus,
    controls: Controls,
    palette: PaletteChoice,
    onPalette: (PaletteChoice) -> Unit,
    onDisconnect: () -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    hasKeyboard: Boolean,
    modifier: Modifier = Modifier,
    isTelevision: Boolean = false,
    updater: Updater? = null,
) {
    val motion = MaterialTheme.motionScheme
    val open = controls.overlayOpen
    // Starts hidden so the first open animates in even when this composable is created already open.
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = open
    val armed = controls.disconnect.armed
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
            StatusSurface(status, hasKeyboard, isTelevision)
        }

        AnimatedVisibility(
            visible = open && controls.driversOpen && wide,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 4 },
            exit = fadeOut(motion.slowEffectsSpec()),
        ) {
            DriversSurface(
                status,
                Modifier.holdsHud(controls).widthIn(max = DRIVERS_PANEL_WIDTH).fillMaxHeight().padding(bottom = TOOLBAR_CLEARANCE),
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
            if (updater != null) {
                AnimatedVisibility(visible = open && controls.updateOpen, enter = enter, exit = exit) {
                    UpdatePanel(updater, Modifier.holdsHud(controls))
                }
            }
            AnimatedVisibility(visible = armed, enter = fadeIn(motion.defaultEffectsSpec()), exit = fadeOut(motion.slowEffectsSpec())) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                    Text(
                        DISCONNECT_HINT,
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            AnimatedVisibility(visibleState = visible, enter = enter, exit = exit) {
                HudToolbar(
                    controls, palette, onPalette, onDisconnect, onToggleFullscreen, hasKeyboard,
                    stacked = compact, isTelevision = isTelevision, updater = updater,
                )
            }
        }
    }
}

/** Link, nodes, key and section; its text is the same status the title screen words use. */
@Composable
private fun StatusSurface(status: EngineStatus, hasKeyboard: Boolean, isTelevision: Boolean) {
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
                    isTelevision -> "Back to hide"
                    hasKeyboard -> SHORTCUTS
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
        DriversPanel(status.params.describe(status.turningDirection), Modifier.verticalScroll(rememberScrollState()).padding(16.dp))
    }
}

/** Tracks whether a pointer is down or focus is inside, so the auto-hide timer waits while the viewer is mid-gesture. */
private fun Modifier.holdsHud(controls: Controls): Modifier = this
    .onFocusChanged { controls.focusWithin = it.hasFocus }
    .pointerInput(controls) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                controls.pressed = event.changes.any { it.pressed }
            }
        }
    }

/** One toolbar on wide windows; on a narrow one the volume and look share a row above the actions. */
@Composable
private fun HudToolbar(
    controls: Controls,
    palette: PaletteChoice,
    onPalette: (PaletteChoice) -> Unit,
    onDisconnect: () -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    hasKeyboard: Boolean,
    stacked: Boolean,
    isTelevision: Boolean = false,
    updater: Updater? = null,
) {
    val focusFirst = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // The toolbar has just composed; focus can only fail when it is already gone again.
        runCatching { focusFirst.requestFocus() }
    }
    val colors = FloatingToolbarDefaults.standardFloatingToolbarColors()
    val volume: @Composable RowScope.() -> Unit = {
        MuteButton(controls, hasKeyboard, Modifier.focusRequester(focusFirst))
        VolumeSlider(controls, isTelevision)
        LookMenu(controls, palette, onPalette, hasKeyboard)
    }
    val actions: @Composable RowScope.() -> Unit = {
        HeadphonesButton(controls, hasKeyboard)
        DriversButton(controls, hasKeyboard)
        if (onToggleFullscreen != null) HudButton("Fullscreen", HudIcons.Fullscreen, hasKeyboard, onToggleFullscreen)
        UpdateButton(updater, controls) { onClick -> HudButton("Update", HudIcons.Update, hasKeyboard, onClick) }
        DisconnectButton(controls, onDisconnect, hasKeyboard)
    }
    Column(
        Modifier.holdsHud(controls).selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (stacked) {
            HorizontalFloatingToolbar(expanded = true, colors = colors, content = volume)
            HorizontalFloatingToolbar(expanded = true, colors = colors, content = actions)
        } else {
            HorizontalFloatingToolbar(expanded = true, colors = colors, content = {
                volume()
                actions()
            })
        }
    }
}

@Composable
private fun HudTooltip(label: String, enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label, style = MaterialTheme.typography.bodyLarge) } },
        state = rememberTooltipState(),
        content = content,
    )
}

@Composable
internal fun HudIcon(vector: ImageVector) = Icon(vector, contentDescription = null, modifier = Modifier.size(24.dp))

@Composable
private fun HudButton(label: String, icon: ImageVector, tooltips: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HudTooltip(label, tooltips) {
        IconButton(onClick, modifier.size(MinTouch).semantics { contentDescription = label }) { HudIcon(icon) }
    }
}

@Composable
private fun MuteButton(controls: Controls, tooltips: Boolean, modifier: Modifier) {
    HudTooltip("Mute", tooltips) {
        IconToggleButton(
            checked = controls.muted,
            onCheckedChange = { controls.muted = it },
            modifier = modifier.size(MinTouch).semantics {
                contentDescription = "Mute"
                stateDescription = if (controls.muted) "Muted" else "Sound on"
            },
        ) { HudIcon(if (controls.muted) HudIcons.VolumeOff else HudIcons.VolumeOn) }
    }
}

@Composable
private fun VolumeSlider(controls: Controls, isTelevision: Boolean) {
    val focus = LocalFocusManager.current
    Slider(
        value = controls.volume,
        onValueChange = {
            controls.volume = it
            controls.muted = false
        },
        modifier = Modifier.width(SLIDER_WIDTH).heightIn(min = MinTouch)
            .then(if (isTelevision) Modifier.onPreviewKeyEvent { televisionSliderKey(it, controls, focus) } else Modifier)
            .semantics {
                contentDescription = "Volume"
                stateDescription = "${(controls.volume * PERCENT).roundToInt()} percent" + if (controls.muted) ", muted" else ""
            },
    )
}

/**
 * On a television the volume slider must not hold the D-pad: Left and Right move focus along the toolbar and Up and Down
 * change the volume, the same as the Up and Down keys do from the scene. Everything else reaches the slider.
 */
private fun televisionSliderKey(event: KeyEvent, controls: Controls, focus: FocusManager): Boolean {
    val horizontal = when (event.key) {
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        else -> null
    }
    if (horizontal != null) {
        if (event.type == KeyEventType.KeyDown) focus.moveFocus(horizontal)
        return true
    }
    // The slider has no click, so Select would fall through to the scene and close the HUD.
    if (event.key == Key.DirectionCenter) return true
    val action = when (event.key) {
        Key.DirectionUp -> KeyAction.VolumeUp
        Key.DirectionDown -> KeyAction.VolumeDown
        else -> return false
    }
    if (event.type == KeyEventType.KeyDown) controls.apply(action, fullscreen = false, canFullscreen = false)
    return true
}

/**
 * The look as one toolbar button that shows the current look's icon and opens a menu of the three, each with its icon
 * and a check on the selected one. A screen reader hears "Look" and the current look's name; the HUD does not hide
 * while the menu is open.
 */
@Composable
private fun LookMenu(controls: Controls, palette: PaletteChoice, onPalette: (PaletteChoice) -> Unit, tooltips: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { controls.menuOpen = expanded }
    DisposableEffect(Unit) { onDispose { controls.menuOpen = false } }
    Box {
        HudTooltip("Look", tooltips && !expanded) {
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier.size(MinTouch).semantics {
                    contentDescription = "Look"
                    stateDescription = palette.label
                    role = Role.DropdownList
                },
            ) { HudIcon(HudIcons.look(palette)) }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (choice in PaletteChoice.entries) {
                val selected = choice == palette
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        onPalette(choice)
                        expanded = false
                    },
                    modifier = Modifier.semantics {
                        this.selected = selected
                        role = Role.RadioButton
                    },
                    leadingIcon = { HudIcon(HudIcons.look(choice)) },
                    trailingIcon = if (selected) {
                        { HudIcon(HudIcons.Check) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun DriversButton(controls: Controls, tooltips: Boolean) {
    HudTooltip("Drivers", tooltips) {
        IconToggleButton(
            checked = controls.driversOpen,
            onCheckedChange = { controls.driversOpen = it },
            modifier = Modifier.size(MinTouch).semantics {
                contentDescription = "Drivers"
                stateDescription = if (controls.driversOpen) "shown" else "hidden"
            },
        ) { HudIcon(HudIcons.Drivers) }
    }
}

@Composable
private fun HeadphonesButton(controls: Controls, tooltips: Boolean) {
    HudTooltip("Headphones", tooltips) {
        IconToggleButton(
            checked = controls.headphones,
            onCheckedChange = { controls.headphones = it },
            modifier = Modifier.size(MinTouch).semantics {
                contentDescription = "Headphones"
                stateDescription = if (controls.headphones) "Placed for headphones" else "Panned for speakers"
            },
        ) { HudIcon(HudIcons.Headphones) }
    }
}

/** The first press arms the confirmation, the second within 3 s disconnects; the key does the same. */
@Composable
private fun DisconnectButton(controls: Controls, onDisconnect: () -> Unit, tooltips: Boolean) {
    val armed = controls.disconnect.armed
    HudTooltip("Disconnect", tooltips) {
        IconButton(
            onClick = { if (controls.disconnect.press(nowMillis())) onDisconnect() },
            modifier = Modifier.size(MinTouch).semantics {
                contentDescription = "Disconnect"
                if (armed) stateDescription = "Press again to confirm"
            },
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (armed) MaterialTheme.colorScheme.errorContainer else Color.Unspecified,
                contentColor = if (armed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.error,
            ),
        ) { HudIcon(HudIcons.Disconnect) }
    }
}
