package org.meshtastic.aether.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What a host menu can ask of the running session. The session registers its actions while it runs and clears them when
 * it
 * ends, so a menu item calls exactly what the matching key calls, including the two-press disconnect.
 */
class AppCommands {
    /** True while a session is running; a host menu enables Disconnect and Mute only then. */
    var sessionRunning by mutableStateOf(false)
        internal set

    /** Whether the master volume is muted, for a menu item that reads Mute or Unmute. */
    var muted by mutableStateOf(false)
        internal set

    private var disconnectAction: (() -> Unit)? = null
    private var muteAction: (() -> Unit)? = null

    internal fun register(disconnect: () -> Unit, toggleMute: () -> Unit) {
        disconnectAction = disconnect
        muteAction = toggleMute
        sessionRunning = true
    }

    internal fun unregister() {
        disconnectAction = null
        muteAction = null
        sessionRunning = false
    }

    /** The D key's action: the first press asks for confirmation and the second within three seconds disconnects. */
    fun disconnect() {
        disconnectAction?.invoke()
    }

    /** The M key's action. */
    fun toggleMute() {
        muteAction?.invoke()
    }
}

/**
 * Registers the running session's key actions with the host for as long as this is composed, and mirrors the mute
 * state.
 */
@Composable
internal fun HostCommands(commands: AppCommands?, muted: Boolean, perform: State<(KeyAction) -> Boolean>) {
    DisposableEffect(commands) {
        commands?.register(
            disconnect = { perform.value(KeyAction.Disconnect) },
            toggleMute = { perform.value(KeyAction.ToggleMute) },
        )
        onDispose { commands?.unregister() }
    }
    LaunchedEffect(commands, muted) { commands?.muted = muted }
}
