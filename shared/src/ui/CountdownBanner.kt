@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.meshtastic.aether.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private const val ONE_SECOND_MS = 1_000

/**
 * The [pending] automatic connect: its line over a determinate wavy bar that fills as the countdown runs, then Connect
 * now ([onNow], absent while the fields describe no link) and Cancel. The first second's line is a polite live region,
 * so a screen reader hears it once and not on every tick.
 */
@Composable
internal fun CountdownBanner(pending: PendingConnect, onNow: (() -> Unit)?, modifier: Modifier = Modifier) {
    val seconds = pending.countdown.secondsLeft
    val total = pending.totalSeconds.coerceAtLeast(1)
    val target = ((total - seconds + 1).coerceAtMost(total)).toFloat() / total
    val progress by animateFloatAsState(target, tween(ONE_SECOND_MS, easing = LinearEasing))
    val text = pending.text(seconds)
    val announce = seconds == pending.totalSeconds
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics {
                    contentDescription = pending.spoken(seconds)
                    if (announce) liveRegion = LiveRegionMode.Polite
                },
            )
            // The line above says the time left; the bar's per-second progress would only repeat it.
            LinearWavyProgressIndicator({ progress }, Modifier.fillMaxWidth().clearAndSetSemantics {})
            // Spaced as a standard button group; not `ButtonGroup`, which composes an unplaced, unnamed overflow button.
            Row(horizontalArrangement = ButtonGroupDefaults.HorizontalArrangement) {
                if (onNow != null) {
                    FilledTonalButton(onNow, ButtonDefaults.shapes(), Modifier.heightIn(min = MinTouch)) {
                        Text("Connect now")
                    }
                }
                TextButton(pending.countdown::cancel, ButtonDefaults.shapes(), Modifier.heightIn(min = MinTouch)) {
                    Text("Cancel")
                }
            }
        }
    }
}
