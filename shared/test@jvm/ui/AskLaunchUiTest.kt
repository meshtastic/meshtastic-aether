package org.meshtastic.aether.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import org.meshtastic.aether.AppRoot
import org.meshtastic.aether.Platform
import kotlin.test.Test

private class FakePlatform : Platform {
    override val name = "test"
    override val supportsTcp = true
    override val lookaheadMillis = 250
    override val needsTapToStart = false
    override val hasKeyboard = true
    override val autoConnects = true
    override val hasSystemBack = false

    override fun resumeAudio() = Unit
}

@OptIn(ExperimentalTestApi::class)
class AskLaunchUiTest {
    private fun settingsWithSavedLink() = Settings(MemoryStore()).also { it.link = LinkChoice.Tcp("node.local", 4403) }

    // Regression: a saved link started its countdown with no way to ask for the connect screen.
    @Test
    fun `ask shows the connect screen without a countdown though a link is saved`() = runComposeUiTest {
        setContent { AppRoot(settingsWithSavedLink(), FakePlatform(), ask = true) }
        onNodeWithText("Demo").assertExists()
        onNodeWithText("Connect now").assertDoesNotExist()
    }

    // Control: without the flag the same saved link counts down, so the test above is not passing by accident.
    @Test
    fun `without ask a saved link shows the countdown`() = runComposeUiTest {
        setContent { AppRoot(settingsWithSavedLink(), FakePlatform()) }
        onNodeWithText("Connect now").assertExists()
    }
}
