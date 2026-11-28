package org.meshtastic.aether.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.meshtastic.aether.AppRoot
import org.meshtastic.aether.LaunchOptions
import org.meshtastic.aether.midi.MidiChoice
import kotlin.test.Test
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class MidiSettingsUiTest {
    // The settings list must reach the MIDI topic through the navigation, a saved output that is not connected must
    // still show as the choice, and a new pick must be what the next session plays to.
    @Test
    fun `the midi topic shows the saved output and saves a new pick`() = runDesktopComposeUiTest(1280, 900) {
        val settings = Settings(MemoryStore()).apply { midi = MidiChoice(output = "Saved synth") }
        setContent { AppRoot(settings, FakePlatform(), LaunchOptions(ask = true)) }
        onNodeWithContentDescription("Settings").performClick()
        onNodeWithText("MIDI output").performClick()
        onNodeWithText("Not connected now; plays when it is.").performScrollTo()
        onNodeWithText("Output").performClick()
        waitForIdle()
        onNodeWithText("Off").performClick()
        waitForIdle()
        assertNull(settings.midi.output)
    }
}
