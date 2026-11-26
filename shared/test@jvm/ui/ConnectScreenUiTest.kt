package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.meshtastic.aether.theme.AmbientTheme

@OptIn(ExperimentalTestApi::class)
class ConnectScreenUiTest {
    private fun androidx.compose.ui.test.ComposeUiTest.show(
        pending: PendingConnect? = null,
        failure: String? = null,
        width: Int = 800,
        height: Int = 600,
        chosen: MutableList<LinkChoice> = mutableListOf(),
        focusPrimary: Boolean = false,
    ) = setContent {
        AmbientTheme {
            Box(Modifier.size(width.dp, height.dp)) {
                ConnectScreen(chosen::add, pending, failure, LinkChoice.Tcp("node.local", 4403), supportsTcp = true, bluetooth = false, focusPrimary = focusPrimary)
            }
        }
    }

    // A D-pad user has no pointer to land on the primary button, so a TV starts with it focused and Select connects.
    @Test
    fun `on a television the primary button starts focused and elsewhere nothing is`() = runComposeUiTest {
        show(focusPrimary = true)
        waitForIdle()
        onNode(hasClickAction() and hasText("Start the demo")).assertIsFocused()
    }

    @Test
    fun `without the television flag the primary button does not take focus`() = runComposeUiTest {
        show()
        waitForIdle()
        onNode(hasClickAction() and hasText("Start the demo")).assertIsNotFocused()
    }

    // Regression: editing the host left the countdown running and it connected to the saved link.
    @Test
    fun `typing in the host cancels the countdown`() = runComposeUiTest {
        val pending = PendingConnect(LinkChoice.Tcp("node.local", 4403), Countdown(5))
        show(pending)
        assertFalse(pending.countdown.cancelled)
        onNode(hasSetTextAction() and hasText("Node host")).performTextInput("x")
        waitForIdle()
        assertTrue(pending.countdown.cancelled)
        onNodeWithText("Connect now").assertDoesNotExist()
    }

    @Test
    fun `changing the link kind cancels the countdown`() = runComposeUiTest {
        val pending = PendingConnect(LinkChoice.Demo, Countdown(5))
        show(pending)
        onNodeWithText("WebSocket").performClick()
        assertTrue(pending.countdown.cancelled)
    }

    // Regression: Enter did not submit on desktop.
    @Test
    fun `Enter in the port field chooses the Tcp link`() = runComposeUiTest {
        val chosen = mutableListOf<LinkChoice>()
        show(chosen = chosen)
        onNodeWithText("Network").performClick()
        onNode(hasSetTextAction() and hasText("Port")).performClick()
        onNode(hasSetTextAction() and hasText("Port")).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf<LinkChoice>(LinkChoice.Tcp("node.local", 4403)), chosen)
    }

    // Regression: Connect now used the saved link instead of the fields on screen.
    @Test
    fun `Connect now connects to what is on screen`() = runComposeUiTest {
        val chosen = mutableListOf<LinkChoice>()
        show(PendingConnect(LinkChoice.Tcp("saved.local", 1), Countdown(5)), chosen = chosen)
        onNodeWithText("Connect now").performClick()
        assertEquals(listOf<LinkChoice>(LinkChoice.Tcp("node.local", 4403)), chosen)
    }

    @Test
    fun `the chooser exposes which kind is selected`() = runComposeUiTest {
        show()
        onNodeWithText("Demo").assertIsSelected()
        onNodeWithText("Network").assertIsNotSelected()
        onNodeWithText("Network").performClick()
        onNodeWithText("Network").assertIsSelected()
        onNodeWithText("Demo").assertIsNotSelected()
    }

    // Regression: the error was plain colored text that a screen reader never announced.
    @Test
    fun `the error is a polite live region`() = runComposeUiTest {
        show(failure = "Could not reach the node")
        onNode(hasText("Could not reach the node") and SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assertExists()
    }

    // Regression: the screen overflowed the default 800x600 window.
    @Test
    fun `no pane needs vertical scrolling at 800 by 600`() = runComposeUiTest {
        show(PendingConnect(LinkChoice.Demo, Countdown(5)), failure = "Could not reach the node")
        for (label in listOf("Demo", "Network", "WebSocket")) {
            onNodeWithText(label).performClick()
            waitForIdle()
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNodes().forEach {
                assertEquals(0f, it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue(), "$label pane scrolls")
            }
        }
    }

    // Regression: touch targets under 44 dp are hard to hit with gloves or on a phone.
    @Test
    fun `every clickable is at least 44 dp`() = runComposeUiTest {
        show(PendingConnect(LinkChoice.Demo, Countdown(5)))
        for (node in onAllNodes(hasClickAction()).fetchSemanticsNodes().indices) {
            onAllNodes(hasClickAction())[node].assertHeightIsAtLeast(44.dp).assertWidthIsAtLeast(44.dp)
        }
    }
}
