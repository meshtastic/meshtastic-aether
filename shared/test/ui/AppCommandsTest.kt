package org.meshtastic.aether.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppCommandsTest {
    // Regression: a menu item enabled with no session running would call into nothing, or into a stale session.
    @Test
    fun `before a session registers the commands do nothing and report no session`() {
        val commands = AppCommands()
        commands.disconnect()
        commands.toggleMute()
        assertFalse(commands.sessionRunning)
    }

    @Test
    fun `a registered session receives each command once and is marked running`() {
        val commands = AppCommands()
        val calls = mutableListOf<String>()
        commands.register(disconnect = { calls += "disconnect" }, toggleMute = { calls += "mute" })
        assertTrue(commands.sessionRunning)
        commands.toggleMute()
        commands.disconnect()
        assertEquals(listOf("mute", "disconnect"), calls)
    }

    // Regression: after a session ends the host menu kept calling the old session's actions.
    @Test
    fun `after unregister the commands do nothing again and no session is reported`() {
        val commands = AppCommands()
        var calls = 0
        commands.register(disconnect = { calls++ }, toggleMute = { calls++ })
        commands.unregister()
        commands.disconnect()
        commands.toggleMute()
        assertEquals(0, calls)
        assertFalse(commands.sessionRunning)
    }

    // Regression: a stop from the notification while the activity is in the background left the session reported as
    // running until the next recomposition so the playback service never stopped.
    @Test
    fun `a stop runs the session's stop and reports no session before any recomposition`() {
        val commands = AppCommands()
        var runningWhenStopped: Boolean? = null
        commands.register(disconnect = {}, toggleMute = {}, stop = { runningWhenStopped = commands.sessionRunning })
        commands.stop()
        assertEquals(false, runningWhenStopped)
        assertFalse(commands.sessionRunning)
    }

    // Regression: a pause left over from one session made the next one start silent.
    @Test
    fun `a pause is passed to the session and cleared when the session ends`() {
        val commands = AppCommands()
        val seen = mutableListOf<Boolean>()
        commands.register(disconnect = {}, toggleMute = {}, setPaused = { seen += it })
        commands.pause(true)
        assertTrue(commands.paused)
        commands.unregister()
        assertFalse(commands.paused)
        commands.pause(true)
        assertFalse(commands.paused)
        assertEquals(listOf(true), seen)
    }
}
