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
    Row(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
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
