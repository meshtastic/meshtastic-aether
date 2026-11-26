package org.meshtastic.aether.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The smallest height of anything tappable, from the client design standards. */
internal val MinTouch = 44.dp

/** Widest column the connect screen and overlay use, so a projector's full width does not stretch the controls. */
internal val MaxContentWidth = 420.dp

internal val ScreenGutter = 20.dp

/** Window width at which the layout leaves the single-column compact form, in dp. */
internal val MediumBreakpoint = 600.dp

/** Window width at which the layout goes to two panes, in dp. */
internal val ExpandedBreakpoint = 840.dp

/** The Material window width classes: compact under 600 dp, medium 600 to 839, expanded 840 and up. */
internal enum class WidthClass { Compact, Medium, Expanded }

/** The [WidthClass] for a container [maxWidth]; the lower bound of each class belongs to that class. */
internal fun widthClass(maxWidth: Dp): WidthClass = when {
    maxWidth < MediumBreakpoint -> WidthClass.Compact
    maxWidth < ExpandedBreakpoint -> WidthClass.Medium
    else -> WidthClass.Expanded
}
