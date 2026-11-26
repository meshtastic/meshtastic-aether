package org.meshtastic.aether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.meshtastic.aether.theme.PaletteChoice
import kotlin.math.roundToInt

private const val OVERLAY_ALPHA = 0.88f
private const val PERCENT = 100
private val OVERLAY_GLYPH_SIZE = 28.dp

/** The shortcut line shown where a keyboard is the usual input. */
internal const val SHORTCUTS = "H overlay - F fullscreen - M mute - Up and Down volume - D disconnect - Esc close"

/** The hidden overlay: link, node count, key, mode and section, volume, palette, and the way out; the whole column scrolls. Not part of the scene. */
@OptIn(ExperimentalLayoutApi::class)
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
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.widthIn(max = MaxContentWidth).background(colors.surface.copy(alpha = OVERLAY_ALPHA))
            .verticalScroll(rememberScrollState()).padding(ScreenGutter),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AetherGlyph(OVERLAY_GLYPH_SIZE)
            Text("Meshtastic Aether", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        }
        Text("Link: ${linkStatusText(status.link)}", style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
        Text("Nodes: ${status.nodes}", style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
        Text("Key: ${status.key} ${status.mode.lowercase()}", style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
        Text("Section: ${status.section}", style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
        status.error?.let { Text("Error: $it", style = MaterialTheme.typography.bodyLarge, color = colors.error) }
        Text(if (controls.muted) "Volume (muted)" else "Volume", style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
        Slider(
            value = controls.volume,
            onValueChange = {
                controls.volume = it
                controls.muted = false
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).semantics {
                contentDescription = "Volume"
                stateDescription = "${(controls.volume * PERCENT).roundToInt()} percent" + if (controls.muted) ", muted" else ""
            },
        )
        Text("Look", style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in PaletteChoice.entries) {
                val modifier = Modifier.heightIn(min = MinTouch).semantics { contentDescription = "${choice.label} look" }
                if (choice == palette) {
                    Button({ onPalette(choice) }, modifier) { Text(choice.label, style = MaterialTheme.typography.labelLarge) }
                } else {
                    OutlinedButton({ onPalette(choice) }, modifier) { Text(choice.label, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onDisconnect, Modifier.heightIn(min = MinTouch)) { Text("Disconnect", style = MaterialTheme.typography.labelLarge) }
            OutlinedButton({ controls.muted = !controls.muted }, Modifier.heightIn(min = MinTouch)) {
                Text(if (controls.muted) "Unmute" else "Mute", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
                { controls.driversOpen = !controls.driversOpen },
                Modifier.heightIn(min = MinTouch).semantics { stateDescription = if (controls.driversOpen) "shown" else "hidden" },
            ) {
                Text(if (controls.driversOpen) "Hide drivers" else "Drivers", style = MaterialTheme.typography.labelLarge)
            }
            if (onToggleFullscreen != null) {
                OutlinedButton(onToggleFullscreen, Modifier.heightIn(min = MinTouch)) { Text("Fullscreen", style = MaterialTheme.typography.labelLarge) }
            }
        }
        if (controls.driversOpen) DriversPanel(status.params.describe(status.turningDirection))
        Text(
            if (hasKeyboard) SHORTCUTS else "Tap to hide",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
    }
}
