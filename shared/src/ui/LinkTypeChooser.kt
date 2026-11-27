@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The ways the connect screen can start a session; one pane shows at a time. */
internal enum class LinkKind(val label: String) {
    Network("Network"),
    Bluetooth("Bluetooth"),
    Serial("USB"),
    WebSocket("WebSocket"),
    Demo("Demo"),
}

/** The kinds this target can offer, real links first and the demo last: WebSocket and the demo everywhere, TCP, Bluetooth and USB serial only where the platform has them. */
internal fun availableKinds(supportsTcp: Boolean, bluetooth: Boolean, serial: Boolean = false): List<LinkKind> =
    listOfNotNull(
        LinkKind.Network.takeIf { supportsTcp },
        LinkKind.Bluetooth.takeIf { bluetooth },
        LinkKind.Serial.takeIf { serial },
        LinkKind.WebSocket,
        LinkKind.Demo,
    )

/**
 * The kind the connect screen opens on: the pending link's while its countdown runs, else the one last chosen, else the
 * first real link the platform offers (Network, or WebSocket on the web).
 */
internal fun initialKind(kinds: List<LinkKind>, pending: LinkKind?, lastChosen: LinkKind?): LinkKind =
    pending?.takeIf { it in kinds } ?: lastChosen?.takeIf { it in kinds } ?: kinds.first()

/** The kind whose pane shows a saved [link], or null when no pane edits it (the replay, a scanned radio). */
internal fun LinkChoice.kind(): LinkKind? = when (this) {
    LinkChoice.Demo -> LinkKind.Demo
    is LinkChoice.Tcp -> LinkKind.Network
    is LinkChoice.WebSocket -> LinkKind.WebSocket
    is LinkChoice.Ble -> LinkKind.Bluetooth
    is LinkChoice.Serial -> LinkKind.Serial
    LinkChoice.Replay -> null
}

private val ChooserHeight = 48.dp
private val ChooserPadding = PaddingValues(horizontal = 4.dp)

/**
 * A connected single-select group of icon toggle buttons, each named by its label for accessibility. The selected
 * one reports `selected` and carries [selectedFocus], so a D-pad or keyboard user starts on the current choice.
 */
@Composable
internal fun LinkTypeChooser(
    kinds: List<LinkKind>,
    selected: LinkKind,
    onSelect: (LinkKind) -> Unit,
    modifier: Modifier = Modifier,
    selectedFocus: FocusRequester? = null,
) {
    Row(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        kinds.forEachIndexed { index, kind ->
            val shapes = when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                kinds.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            val checked = kind == selected
            ToggleButton(
                checked = checked,
                onCheckedChange = { onSelect(kind) },
                modifier = Modifier.weight(1f).heightIn(min = ChooserHeight)
                    .then(if (checked && selectedFocus != null) Modifier.focusRequester(selectedFocus) else Modifier)
                    .semantics {
                        role = Role.RadioButton
                        this.selected = checked
                    },
                shapes = shapes,
                contentPadding = ChooserPadding,
            ) { Icon(LinkIcons.of(kind), contentDescription = kind.label) }
        }
    }
}
