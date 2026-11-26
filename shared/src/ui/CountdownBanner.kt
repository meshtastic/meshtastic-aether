@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The pending automatic connect: [text] over a determinate wavy bar at [progress] (0 to 1), then Connect now and Cancel.
 * [announce] marks the line as a polite live region, so a screen reader hears it once and not on every tick.
 */
@Composable
internal fun CountdownBanner(
    text: String,
    progress: () -> Float,
    onNow: (() -> Unit)?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    announce: Boolean = false,
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics {
                    contentDescription = text.replace(" s", " seconds")
                    if (announce) liveRegion = LiveRegionMode.Polite
                },
            )
            LinearWavyProgressIndicator(progress, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onNow != null) FilledTonalButton(onNow, Modifier.heightIn(min = MinTouch)) { Text("Connect now") }
                TextButton(onCancel, Modifier.heightIn(min = MinTouch)) { Text("Cancel") }
            }
        }
    }
}
