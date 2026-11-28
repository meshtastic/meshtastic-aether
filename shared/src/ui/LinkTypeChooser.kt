@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val ChooserHeight = 48.dp
private val ChooserInset = 4.dp
private val ChooserPadding = PaddingValues(horizontal = ChooserInset)

/** Room kept beside a label so it does not run into the segment's rounded ends. */
private val LabelSlack = 8.dp

/** Tags each visible chooser label, which a screen reader skips; tests find the labels by it. */
internal const val LINK_LABEL_TAG = "linkLabel"

/** Whether [count] connected segments sharing [width] each fit [content], an icon beside the longest label. */
internal fun segmentsFitLabels(width: Dp, count: Int, content: Dp): Boolean {
    val gaps = ButtonGroupDefaults.ConnectedSpaceBetween * (count - 1).coerceAtLeast(0)
    return (width - gaps) / count.coerceAtLeast(1) >= content
}

/** The width an icon and the longest of [labels] take in a segment, measured at the current font scale. */
@Composable
private fun labeledContentWidth(labels: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val longest = labels.maxOfOrNull { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width } ?: 0
    return ChooserInset * 2 + ToggleButtonDefaults.IconSize + ToggleButtonDefaults.IconSpacing + LabelSlack +
        with(density) { longest.toDp() }
}

/**
 * A connected single-select group of toggle buttons. Each shows its icon and label when every segment has room, and
 * only its icon otherwise, named by the label for a screen reader and in a tooltip. The selected one
 * reports `selected`; while [onStartFocus] is set it takes focus and then calls it, so a D-pad or keyboard user starts
 * on the current choice.
 */
@Composable
internal fun LinkTypeChooser(
    kinds: List<LinkKind>,
    selected: LinkKind,
    onSelect: (LinkKind) -> Unit,
    modifier: Modifier = Modifier,
    onStartFocus: (() -> Unit)? = null,
) {
    val selectedFocus = remember { FocusRequester() }
    if (onStartFocus != null) {
        LaunchedEffect(Unit) {
            selectedFocus.requestFocus()
            onStartFocus()
        }
    }
    val content = labeledContentWidth(kinds.map { it.label })
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val labeled = segmentsFitLabels(maxWidth, kinds.size, content)
        Row(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            kinds.forEachIndexed { index, kind ->
                val shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    kinds.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                }
                val checked = kind == selected
                ChooserTooltip(kind.label, !labeled, Modifier.weight(1f)) {
                    ToggleButton(
                        checked = checked,
                        onCheckedChange = { onSelect(kind) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = ChooserHeight)
                            .then(if (checked) Modifier.focusRequester(selectedFocus) else Modifier)
                            .semantics {
                                role = Role.RadioButton
                                this.selected = checked
                                contentDescription = kind.label
                            },
                        shapes = shapes,
                        // The default unchecked container is surfaceContainer, the same as the form's pane.
                        colors = ToggleButtonDefaults.toggleButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        contentPadding = ChooserPadding,
                    ) {
                        // The button carries the name, so a visible label is not read a second time.
                        Icon(LinkIcons.of(kind), contentDescription = null)
                        if (labeled) {
                            Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                            Text(
                                kind.label,
                                Modifier.clearAndSetSemantics { testTag = LINK_LABEL_TAG },
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Visible,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChooserTooltip(label: String, enabled: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    // The Box takes the row's weight: TooltipBox does not pass a weight through to its anchor.
    Box(modifier) {
        if (enabled) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(label, style = MaterialTheme.typography.bodyLarge) } },
                state = rememberTooltipState(),
                content = content,
            )
        } else {
            content()
        }
    }
}
