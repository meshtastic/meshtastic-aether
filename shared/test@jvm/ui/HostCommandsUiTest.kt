package org.meshtastic.aether.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HostCommandsUiTest {
    // Regression: a host menu could not reach the running session, or kept reaching it after the session left the screen.
    @Test
    fun `commands reach the session while it is composed and stop reaching it after`() = runComposeUiTest {
        val commands = AppCommands()
        val received = mutableListOf<KeyAction>()
        var session by mutableStateOf(true)
        setContent {
            if (session) {
                val perform = rememberUpdatedState<(KeyAction) -> Boolean> { received += it; true }
                HostCommands(commands, muted = false, perform = perform)
            }
        }
        waitForIdle()
        assertTrue(commands.sessionRunning)
        commands.toggleMute()
        commands.disconnect()
        assertEquals(listOf(KeyAction.ToggleMute, KeyAction.Disconnect), received)
        session = false
        waitForIdle()
        assertFalse(commands.sessionRunning)
        commands.disconnect()
        assertEquals(2, received.size)
    }

    // Regression: the menu item read Mute while the volume was muted, because the mute state never reached the host.
    @Test
    fun `the mute state is mirrored to the host and follows changes`() = runComposeUiTest {
        val commands = AppCommands()
        var muted by mutableStateOf(false)
        setContent {
            val perform = rememberUpdatedState<(KeyAction) -> Boolean> { true }
            HostCommands(commands, muted = muted, perform = perform)
        }
        waitForIdle()
        assertFalse(commands.muted)
        muted = true
        waitForIdle()
        assertTrue(commands.muted)
    }
}
