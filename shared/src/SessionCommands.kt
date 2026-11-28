package org.meshtastic.aether

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import org.meshtastic.aether.ui.AmbientEngine
import org.meshtastic.aether.ui.AppCommands
import org.meshtastic.aether.ui.Controls
import org.meshtastic.aether.ui.HostCommands
import org.meshtastic.aether.ui.KeyAction

/**
 * The session's actions for the host. A host window in the background does not recompose, so a system media control's
 * [stop] and pause act on the engine directly.
 */
@Composable
internal fun SessionCommands(
    commands: AppCommands?,
    controls: Controls,
    perform: State<(KeyAction) -> Boolean>,
    engine: AmbientEngine,
    stop: () -> Unit,
) {
    HostCommands(
        commands,
        controls.muted,
        perform,
        stop = stop,
        setPaused = { engine.volume = if (it) 0f else controls.effectiveVolume },
    )
    UnpauseOnMute(commands, controls)
}

/** A mute or unmute from the app ends a pause the system or audio focus set, so the M key is never dead. */
@Composable
private fun UnpauseOnMute(commands: AppCommands?, controls: Controls) {
    var muteSeen by remember(controls) { mutableStateOf(controls.muted) }
    LaunchedEffect(controls.muted) {
        if (controls.muted == muteSeen) return@LaunchedEffect
        muteSeen = controls.muted
        if (commands?.paused == true) commands.pause(false)
    }
}

/** The job running the engine, so a stop can end the link and the audio without waiting for a recomposition. */
internal class EngineRun {
    var job: Job? = null
}
