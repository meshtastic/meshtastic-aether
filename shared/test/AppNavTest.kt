package org.meshtastic.aether

import kotlinx.coroutines.flow.emptyFlow
import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.mesh.MeshSource
import org.meshtastic.aether.ui.SettingsTopic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun session() = Session(object : MeshSource {
    override val events = emptyFlow<MeshEvent>()
}, seed = 1L)

class AppNavTest {
    // A link that connects while settings is open must show the scene, and Back from the scene must not land in
    // settings or the connect screen behind it, where the scene's own Back would never run.
    @Test
    fun `a session starting from settings leaves the scene alone on the stack and its end returns to connect`() {
        val stack = mutableListOf<Screen>(Screen.Connect, Screen.SettingsHome, Screen.SettingsDetail(SettingsTopic.Midi))
        val s = session()
        stack.follow(s)
        assertEquals(listOf<Screen>(Screen.Playing(s)), stack)
        stack.follow(s)
        assertEquals(listOf<Screen>(Screen.Playing(s)), stack)
        stack.follow(null)
        assertEquals(listOf<Screen>(Screen.Connect), stack)
    }

    // No session must not pull a person out of settings or the connect screen.
    @Test
    fun `no session leaves the connect screen and settings where they are`() {
        val stack = mutableListOf<Screen>(Screen.Connect, Screen.SettingsHome)
        stack.follow(null)
        assertEquals(listOf<Screen>(Screen.Connect, Screen.SettingsHome), stack)
    }

    // On a wide window the list's Back is the only one showing; popping just the topic beside it would leave Settings
    // open and take a second press to leave.
    @Test
    fun `closing settings leaves the connect screen whether or not a topic is open`() {
        val open = mutableListOf(Screen.Connect, Screen.SettingsHome, Screen.SettingsDetail(SettingsTopic.About))
        open.closeSettings()
        assertEquals(listOf<Screen>(Screen.Connect), open)
        val list = mutableListOf<Screen>(Screen.Connect, Screen.SettingsHome)
        list.closeSettings()
        assertEquals(listOf<Screen>(Screen.Connect), list)
    }

    // On a wide window each topic picked beside the list would otherwise stack, and Back would walk through them all
    // before leaving settings.
    @Test
    fun `picking a second topic replaces the first so one Back returns to the list`() {
        val stack = mutableListOf<Screen>(Screen.Connect, Screen.SettingsHome)
        assertNull(stack.shownTopic())
        stack.showTopic(SettingsTopic.Updates)
        stack.showTopic(SettingsTopic.Midi)
        assertEquals(SettingsTopic.Midi, stack.shownTopic())
        assertEquals(listOf(Screen.Connect, Screen.SettingsHome, Screen.SettingsDetail(SettingsTopic.Midi)), stack)
        stack.removeAt(stack.lastIndex)
        assertEquals(listOf<Screen>(Screen.Connect, Screen.SettingsHome), stack)
    }
}
