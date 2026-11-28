package org.meshtastic.aether.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.meshtastic.aether.AppRoot
import org.meshtastic.aether.LaunchOptions
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SettingsPanesUiTest {
    // On a wide window a topic sits beside the list: a second Back in the topic would only pop the topic, and an empty
    // pane with nothing chosen reads as broken.
    @Test
    fun `a wide window shows a topic beside the list with one Back`() = runDesktopComposeUiTest(1280, 800) {
        setContent { AppRoot(Settings(MemoryStore()), FakePlatform(), LaunchOptions(ask = true)) }
        onNodeWithContentDescription("Settings").performClick()
        onNodeWithText("Choose a topic").assertExists()
        onNodeWithText("About").performClick()
        onNodeWithText("Choose a topic").assertDoesNotExist()
        onNodeWithText("Updates").assertExists()
        assertEquals(1, onAllNodesWithContentDescription("Back").fetchSemanticsNodes().size)
    }

    // On a narrow window the topic covers the list and needs its own Back to return to it.
    @Test
    fun `a narrow window shows a topic over the list with its own Back`() = runDesktopComposeUiTest(480, 800) {
        setContent { AppRoot(Settings(MemoryStore()), FakePlatform(), LaunchOptions(ask = true)) }
        onNodeWithContentDescription("Settings").performClick()
        onNodeWithText("Choose a topic").assertDoesNotExist()
        onNodeWithText("About").performClick()
        onNodeWithText("Updates").assertDoesNotExist()
        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("Updates").assertExists()
    }
}
