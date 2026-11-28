package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private const val TAGLINE = "The mesh, on the ether."
private val GLYPH_SIZE = 56.dp
private val MediumColumnWidth = 560.dp
private val ShortHeight = 480.dp
private val PaneMaxWidth = 480.dp

/**
 * The connect screen's parts: the pending reconnect's [banner], the pane [heading], the link [chooser], the chosen
 * link's [fields] (given whether two fields fit side by side) and the [primary] Connect button where the pane has one.
 */
internal class ConnectSlots(
    val banner: (@Composable () -> Unit)?,
    val heading: @Composable () -> Unit,
    val chooser: @Composable () -> Unit,
    val fields: @Composable ColumnScope.(sideBySide: Boolean) -> Unit,
    val primary: (@Composable () -> Unit)?,
)

/** Two panes on an expanded or short window, the brand beside the form; else one column with the button at the foot. */
@Composable
internal fun ConnectLayout(slots: ConnectSlots, modifier: Modifier) {
    // The caller leaves the IME out of [modifier]'s insets: a keyboard that changed the pane choice would recreate the
    // focused field and close itself.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = widthClass(maxWidth)
        val twoPanes = width == WidthClass.Expanded || maxHeight < ShortHeight
        Box(Modifier.fillMaxSize().imePadding()) {
            if (twoPanes) TwoPanes(slots) else OnePane(slots, medium = width == WidthClass.Medium)
        }
    }
}

@Composable
private fun TwoPanes(slots: ConnectSlots) {
    // A landscape phone's keyboard leaves less height than a field and its label; the vertical gutter gives it back.
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Row(
        Modifier.fillMaxSize().padding(horizontal = ScreenGutter, vertical = if (keyboardUp) 0.dp else ScreenGutter),
        horizontalArrangement = Arrangement.spacedBy(ScreenGutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Brand()
            slots.banner?.invoke()
        }
        Surface(
            Modifier.weight(1f).widthIn(max = PaneMaxWidth),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                slots.heading()
                slots.chooser()
                KeptInView { slots.fields(this, true) }
                slots.primary?.invoke()
            }
        }
    }
}

/** One column; on a [medium] window the form is held to a readable width and its fields sit side by side. */
@Composable
private fun OnePane(slots: ConnectSlots, medium: Boolean) {
    val column = if (medium) Modifier.widthIn(max = MediumColumnWidth) else Modifier
    Column(
        Modifier.fillMaxSize().padding(ScreenGutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Brand()
            Column(column.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                slots.banner?.invoke()
                slots.heading()
                slots.chooser()
                KeptInView { slots.fields(this, medium) }
            }
        }
        val primary = slots.primary
        if (primary != null) Column(column.fillMaxWidth().padding(top = 12.dp)) { primary() }
    }
}

/**
 * The link's fields as one group that a scroll keeps in view while one of them has focus, re-asked as the keyboard
 * moves. A text field only brings its own text box into view, which leaves its label and the next field off screen.
 */
@Composable
private fun KeptInView(fields: @Composable ColumnScope.() -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, keyboard) { if (focused) requester.bringIntoView() }
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(requester).onFocusChanged { focused = it.hasFocus },
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = fields,
    )
}

@Composable
private fun Brand() {
    AetherGlyph(GLYPH_SIZE)
    Text("Aether", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text(TAGLINE, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        "It only listens. Nothing is sent to the mesh.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun PaneHeading(kind: LinkKind, direct: Boolean) {
    val text = when {
        kind == LinkKind.WebSocket && !direct -> "Connect to a node's WebSocket"
        else -> "Connect"
    }
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}
