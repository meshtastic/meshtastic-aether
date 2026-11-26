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
}
