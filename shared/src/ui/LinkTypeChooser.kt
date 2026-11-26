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
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The ways the connect screen can start a session; one pane shows at a time. */
internal enum class LinkKind(val label: String) {
    Demo("Demo"),
    Network("Network"),
    Bluetooth("Bluetooth"),
    WebSocket("WebSocket"),
}

/** The kinds this target can offer: the demo and WebSocket everywhere, TCP and Bluetooth only where the platform has them. */
internal fun availableKinds(supportsTcp: Boolean, bluetooth: Boolean): List<LinkKind> =
    listOfNotNull(
        LinkKind.Demo,
        LinkKind.Network.takeIf { supportsTcp },
        LinkKind.Bluetooth.takeIf { bluetooth },
        LinkKind.WebSocket,
    )

/** The kind whose pane shows a saved [link], or null when no pane edits it (the replay, a scanned radio). */
internal fun LinkChoice.kind(): LinkKind? = when (this) {
    LinkChoice.Demo -> LinkKind.Demo
    is LinkChoice.Tcp -> LinkKind.Network
    is LinkChoice.WebSocket -> LinkKind.WebSocket
    is LinkChoice.Ble -> LinkKind.Bluetooth
    LinkChoice.Replay -> null
}

private val ChooserHeight = 48.dp
private val ChooserPadding = PaddingValues(horizontal = 4.dp)

/** A connected single-select group of toggle buttons; the selected one reports `selected` to accessibility. */
@Composable
internal fun LinkTypeChooser(kinds: List<LinkKind>, selected: LinkKind, onSelect: (LinkKind) -> Unit, modifier: Modifier = Modifier) {
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
                modifier = Modifier.weight(1f).heightIn(min = ChooserHeight).semantics {
                    role = Role.RadioButton
                    this.selected = checked
                },
                shapes = shapes,
                contentPadding = ChooserPadding,
            ) { Text(kind.label, maxLines = 1, overflow = TextOverflow.Clip) }
        }
    }
}
