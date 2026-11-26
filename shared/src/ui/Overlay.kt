package org.meshtastic.ambient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.meshtastic.ambient.mesh.LinkState

private fun linkText(s: LinkState): String = when (s) {
    LinkState.Connecting -> "connecting"
    LinkState.WarmingUp -> "warming up"
    LinkState.Connected -> "connected"
    LinkState.Simulated -> "simulated mesh"
    LinkState.Replaying -> "replaying a capture"
    LinkState.Lost -> "link lost"
}

/** The hidden overlay: link, node count, key, mode and section, volume, and the way out. Not part of the scene. */
@Composable
fun StatusOverlay(
    engine: AmbientEngine,
    onDisconnect: () -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val s = engine.status
    var volume by remember { mutableFloatStateOf(engine.volume) }
    Column(
        modifier.background(Color.Black.copy(alpha = 0.72f)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Link: ${linkText(s.link)}", color = Color.White)
        Text("Nodes: ${s.nodes}", color = Color.White)
        Text("Key: ${s.key} ${s.mode.lowercase()}", color = Color.White)
        Text("Section: ${s.section}", color = Color.White)
        s.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
        Text("Volume", color = Color.White)
        Slider(
            value = volume,
            onValueChange = {
                volume = it
                engine.volume = it
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onDisconnect) { Text("Disconnect") }
            if (onToggleFullscreen != null) OutlinedButton(onClick = onToggleFullscreen) { Text("Fullscreen (F)") }
        }
        Text("Tap or press H to hide", color = Color.White.copy(alpha = 0.6f))
    }
}
