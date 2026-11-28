package org.meshtastic.aether.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
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

    /** True while a system media control or lost audio focus holds the session silent; never saved. */
    var paused by mutableStateOf(false)
        private set

    private var disconnectAction: (() -> Unit)? = null
    private var muteAction: (() -> Unit)? = null
    private var stopAction: (() -> Unit)? = null
    private var pauseAction: ((Boolean) -> Unit)? = null

    internal fun register(
        disconnect: () -> Unit,
        toggleMute: () -> Unit,
        stop: () -> Unit = {},
        setPaused: (Boolean) -> Unit = {},
    ) {
        disconnectAction = disconnect
        muteAction = toggleMute
        stopAction = stop
        pauseAction = setPaused
        sessionRunning = true
    }

    internal fun unregister() {
        disconnectAction = null
        muteAction = null
        stopAction = null
        pauseAction = null
        paused = false
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

    /**
     * Ends the session at once, with no confirmation, for a system media control. It acts on the engine directly, so it
     * works while the host window is in the background and not recomposing.
     */
    fun stop() {
        val action = stopAction ?: return
        // The session's own unregister waits for a recomposition; the host learns the session ended now.
        unregister()
        action()
    }

    /** Silences or restores the session without touching the saved mute, for a system media control or audio focus. */
    fun pause(value: Boolean) {
        val action = pauseAction ?: return
        paused = value
        action(value)
    }
}

/**
 * Registers the running session's key actions with the host for as long as this is composed, and mirrors the mute
 * state.
 */
@Composable
internal fun HostCommands(
    commands: AppCommands?,
    muted: Boolean,
    perform: State<(KeyAction) -> Boolean>,
    stop: () -> Unit = {},
    setPaused: (Boolean) -> Unit = {},
) {
    val currentStop by rememberUpdatedState(stop)
    val currentSetPaused by rememberUpdatedState(setPaused)
    DisposableEffect(commands) {
        commands?.register(
            disconnect = { perform.value(KeyAction.Disconnect) },
            toggleMute = { perform.value(KeyAction.ToggleMute) },
            stop = { currentStop() },
            setPaused = { currentSetPaused(it) },
        )
        onDispose { commands?.unregister() }
    }
    LaunchedEffect(commands, muted) { commands?.muted = muted }
}
