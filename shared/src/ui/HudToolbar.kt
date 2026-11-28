@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.meshtastic.aether.theme.PaletteChoice

/** One toolbar on wide windows; on a narrow one the volume and look share a row above the actions. */
@Composable
internal fun HudToolbar(controls: Controls, host: HudHost, input: HudInput, stacked: Boolean) {
    val focusFirst = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusFirst.requestFocus() }
    val tooltips = input.hasKeyboard
    val colors = FloatingToolbarDefaults.standardFloatingToolbarColors()
    val volume: @Composable RowScope.() -> Unit = {
        MuteButton(controls, tooltips, Modifier.focusRequester(focusFirst))
        VolumeSlider(controls, input.isTelevision)
        LookMenu(controls, host.palette, host.onPalette, tooltips)
    }
    val actions: @Composable RowScope.() -> Unit = {
        HeadphonesButton(controls, tooltips)
        DriversButton(controls, tooltips)
        if (host.onToggleFullscreen != null) {
            HudButton("Fullscreen", HudIcons.Fullscreen, tooltips, host.onToggleFullscreen)
        }
        if (host.onEnterSpatial != null) {
            HudButton("Spatial view", HudIcons.Spatial, tooltips, host.onEnterSpatial)
        }
        UpdateButton(host.updater, controls) { onClick -> HudButton("Update", HudIcons.Update, tooltips, onClick) }
        DisconnectButton(controls, host.onDisconnect, tooltips)
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
internal fun HudTooltip(label: String, enabled: Boolean, content: @Composable () -> Unit) {
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
private fun HudButton(label: String, icon: ImageVector, tooltips: Boolean, onClick: () -> Unit) {
    HudTooltip(label, tooltips) {
        IconButton(onClick, Modifier.size(MinTouch).semantics { contentDescription = label }) { HudIcon(icon) }
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

/**
 * The look as one toolbar button that shows the current look's icon and opens a menu of the three, each with its icon
 * and a check on the selected one. A screen reader hears "Look" and the current look's name; the HUD does not hide
 * while the menu is open.
 */
@Composable
private fun LookMenu(
    controls: Controls,
    palette: PaletteChoice,
    onPalette: (PaletteChoice) -> Unit,
    tooltips: Boolean,
) {
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
    val colors = MaterialTheme.colorScheme
    HudTooltip("Disconnect", tooltips) {
        IconButton(
            onClick = { if (controls.disconnect.press(nowMillis())) onDisconnect() },
            modifier = Modifier.size(MinTouch).semantics {
                contentDescription = "Disconnect"
                if (armed) stateDescription = "Press again to confirm"
            },
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (armed) colors.errorContainer else Color.Unspecified,
                contentColor = if (armed) colors.onErrorContainer else colors.error,
            ),
        ) { HudIcon(HudIcons.Disconnect) }
    }
}
