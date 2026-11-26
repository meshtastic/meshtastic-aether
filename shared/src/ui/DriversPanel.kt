package org.meshtastic.ambient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.meshtastic.ambient.mood.ParamInfo

/** The live value with its unit; a parameter with no unit shows the value alone. */
internal fun paramValueText(p: ParamInfo): String = if (p.unit.isEmpty()) p.value else "${p.value} ${p.unit}"

/** What moves the parameter, or `no data` while it holds its neutral value. */
internal fun paramDriverText(p: ParamInfo): String = p.driver ?: "no data"

/** One row per mesh-driven parameter: name, live value and unit, and the signal driving it. Scrolls when the window is short. */
@Composable
internal fun DriversPanel(rows: List<ParamInfo>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.heightIn(max = DRIVERS_MAX_HEIGHT).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (p in rows) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(p.name, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                    Text(paramValueText(p), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                }
                Text(
                    paramDriverText(p),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (p.driver == null) colors.onSurfaceVariant else colors.primary,
                )
            }
        }
    }
}

private val DRIVERS_MAX_HEIGHT = 240.dp
