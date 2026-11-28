package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import org.meshtastic.aether.midi.MidiChoice
import kotlin.math.roundToInt

/** The rows of the output picker: Off, each output there is, and a saved output that is not connected now. */
internal fun outputRows(choice: MidiChoice, outputs: List<String>): List<String?> {
    val saved = choice.output
    return listOf<String?>(null) + outputs + listOfNotNull(saved?.takeIf { it !in outputs })
}

/** The label of the latency setting, signed: "+40 ms", "0 ms", "-20 ms". */
internal fun latencyLabel(millis: Int): String = if (millis > 0) "+$millis ms" else "$millis ms"

internal fun releaseLabel(seconds: Double): String = when (seconds) {
    MidiChoice.RELEASES.first() -> "Short, 0.5 s"
    MidiChoice.RELEASES.last() -> "Long, 8 s"
    else -> "Normal, 2 s"
}

/** The MIDI section's state: the saved [choice], the [outputs] there are now, and where a change goes. */
@Stable
internal class MidiSettingsState(
    val choice: MidiChoice,
    val outputs: List<String>,
    val onChoice: (MidiChoice) -> Unit,
)

/** Where the music also plays: a MIDI output, the built-in synth alongside it, and the timing for that synth. */
@Composable
internal fun MidiSettings(state: MidiSettingsState) {
    val choice = state.choice
    OutputPicker(state)
    if (choice.output == null) return
    ToggleRow(
        label = "Play the built-in synth too",
        detail = "Off: only the MIDI output plays while it is connected.",
        checked = choice.playAlongside,
        onChange = { state.onChoice(choice.copy(playAlongside = it)) },
    )
    ReleasePicker(state)
    LatencySlider(state)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputPicker(state: MidiSettingsState) {
    val rows = outputRows(state.choice, state.outputs)
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = state.choice.output ?: "Off",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Output") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            supportingText = outputDetail(state.choice.output, state.outputs)?.let { { Text(it) } },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            rows.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name ?: "Off") },
                    onClick = {
                        expanded = false
                        state.onChoice(state.choice.copy(output = name))
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
    if (state.outputs.isEmpty()) {
        Text(
            "No MIDI outputs found. A software synth needs a virtual port: the IAC Driver on macOS, a loopback " +
                "port on Windows, or snd-virmidi on Linux.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun outputDetail(name: String?, outputs: List<String>): String? = when {
    name == null -> "Only the built-in synth plays."
    name !in outputs -> "Not connected now; plays when it is."
    else -> null
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToggleRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onChange,
        shapes = ListItemDefaults.segmentedShapes(0, 1),
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        supportingContent = { Text(detail) },
        content = { Text(label) },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReleasePicker(state: MidiSettingsState) {
    val releases = MidiChoice.RELEASES
    Text("Release", style = MaterialTheme.typography.titleSmall)
    Column(
        Modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        releases.forEachIndexed { index, seconds ->
            val selected = seconds == state.choice.releaseSeconds
            SegmentedListItem(
                selected = selected,
                onClick = { state.onChoice(state.choice.copy(releaseSeconds = seconds)) },
                shapes = ListItemDefaults.segmentedShapes(index, releases.size),
                leadingContent = { RadioButton(selected = selected, onClick = null) },
                content = { Text(releaseLabel(seconds)) },
            )
        }
    }
    Text(
        "How long a channel rests before it is retuned. Match the longest release of the synth's sound.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LatencySlider(state: MidiSettingsState) {
    val millis = state.choice.latencyMillis
    Text("MIDI delay: ${latencyLabel(millis)}", style = MaterialTheme.typography.titleSmall)
    val steps = (MidiChoice.MAX_LATENCY - MidiChoice.MIN_LATENCY) / MidiChoice.LATENCY_STEP - 1
    Slider(
        value = millis.toFloat(),
        onValueChange = { v ->
            val snapped = (v / MidiChoice.LATENCY_STEP).roundToInt() * MidiChoice.LATENCY_STEP
            if (snapped != millis) state.onChoice(state.choice.copy(latencyMillis = snapped))
        },
        valueRange = MidiChoice.MIN_LATENCY.toFloat()..MidiChoice.MAX_LATENCY.toFloat(),
        steps = steps,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "MIDI delay"
            stateDescription = latencyLabel(millis)
        },
    )
    Text(
        "Raise it if the MIDI synth sounds before the built-in one; lower it if it sounds after.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
