package org.meshtastic.aether.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.meshtastic.aether.AppRoot
import org.meshtastic.aether.LaunchOptions
import org.meshtastic.aether.midi.MidiChoice
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SettingsSemanticsUiTest {
    private fun role(r: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, r)

    // What a screen reader announces: topic rows that navigate must not read as radio buttons, the play-along row
    // must read as the switch it shows, and the pane titles and section labels must be headings to jump between.
    @Test
    fun `settings rows read as buttons and a switch under headings`() = runDesktopComposeUiTest(1280, 900) {
        val settings = Settings(MemoryStore()).apply { midi = MidiChoice(output = "Saved synth") }
        setContent { AppRoot(settings, FakePlatform(), LaunchOptions(ask = true)) }
        onNodeWithContentDescription("Settings").performClick()
        val midiRow = onNode(hasText("MIDI output") and hasClickAction())
        midiRow.performClick()
        midiRow.assert(role(Role.Button)).assert(isSelected())
        onNode(hasText("Updates") and hasClickAction()).assert(role(Role.Button))
        onNode(hasText("Play the built-in synth too")).assert(role(Role.Switch))
        onNode(hasText("Settings") and isHeading()).assertExists()
        onNode(hasText("MIDI output") and isHeading()).assertExists()
        onNode(hasText("Release") and isHeading()).assertExists()
    }
}
