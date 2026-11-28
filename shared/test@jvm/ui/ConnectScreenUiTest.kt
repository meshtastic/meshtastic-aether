package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
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
        focusChooser: Boolean = false,
        lastKind: LinkKind? = null,
        onKind: (LinkKind) -> Unit = {},
        secureWebSocket: Boolean = false,
        serial: Boolean = false,
    ) = setContent {
        AmbientTheme {
            Box(Modifier.size(width.dp, height.dp)) {
                ConnectScreen(
                    chosen::add,
                    ConnectContext(pending, failure, LinkChoice.Tcp("node.local", 4403), lastKind),
                    target = ConnectTarget(
                        supportsTcp = true,
                        bluetooth = false,
                        serial = serial,
                        secureWebSocket = secureWebSocket,
                        focusChooser = focusChooser,
                    ),
                    onKind = onKind,
                )
            }
        }
    }

    // A D-pad or keyboard user starts on the link chooser, on the current choice, and moves to the fields and button from there.
    @Test
    fun `with the focus flag the selected link type starts focused and the connect button does not`() = runComposeUiTest {
        show(focusChooser = true)
        waitForIdle()
        onNode(hasContentDescription("Network") and hasClickAction() and isSelected()).assertIsFocused()
        onNode(hasClickAction() and hasText("Connect over TCP")).assertIsNotFocused()
    }

    @Test
    fun `without the focus flag nothing on the connect screen takes focus`() = runComposeUiTest {
        show()
        waitForIdle()
        onNode(hasContentDescription("Network") and hasClickAction() and isSelected()).assertIsNotFocused()
        onNode(hasClickAction() and hasText("Connect over TCP")).assertIsNotFocused()
    }

    @Test
    fun `the screen opens on Network and on the last chosen kind once there is one`() = runComposeUiTest {
        var shown by androidx.compose.runtime.mutableStateOf<LinkKind?>(null)
        setContent {
            AmbientTheme {
                Box(Modifier.size(800.dp, 600.dp)) {
                    androidx.compose.runtime.key(shown) {
                        ConnectScreen(
                            {},
                            ConnectContext(null, null, lastKind = shown),
                            target = ConnectTarget(supportsTcp = true, bluetooth = false),
                        )
                    }
                }
            }
        }
        waitForIdle()
        onNode(hasContentDescription("Network") and hasClickAction()).assertIsSelected()
        shown = LinkKind.Demo
        waitForIdle()
        onNode(hasContentDescription("Demo") and hasClickAction()).assertIsSelected()
    }

    @Test
    fun `choosing a link kind reports it so it can be remembered`() = runComposeUiTest {
        val kinds = mutableListOf<LinkKind>()
        show(onKind = { kinds += it })
        waitForIdle()
        onNode(hasContentDescription("Demo") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(listOf(LinkKind.Demo), kinds)
    }

    // An HTTPS page can only open wss://, and the native apps would refuse a stock node's self-signed certificate.
    @Test
    fun `the WebSocket pane prefills wss in the browser and ws in the native apps`() = runComposeUiTest {
        var secure by androidx.compose.runtime.mutableStateOf(true)
        setContent {
            AmbientTheme {
                Box(Modifier.size(800.dp, 600.dp)) {
                    androidx.compose.runtime.key(secure) {
                        ConnectScreen(
                            {},
                            ConnectContext(null, null, lastKind = LinkKind.WebSocket),
                            target = ConnectTarget(supportsTcp = true, bluetooth = false, secureWebSocket = secure),
                        )
                    }
                }
            }
        }
        waitForIdle()
        onNode(hasSetTextAction() and hasText("wss://meshtastic.local/api/v1/ws")).assertExists()
        secure = false
        waitForIdle()
        onNode(hasSetTextAction() and hasText("ws://meshtastic.local/api/v1/ws")).assertExists()
    }

    @Test
    fun `the USB pane lists likely radios first and picking one starts a serial link`() = runComposeUiTest {
        val chosen = mutableListOf<LinkChoice>()
        setContent {
            AmbientTheme {
                Box(Modifier.size(800.dp, 900.dp)) {
                    SerialSection({ chosen += LinkChoice.Serial(it.path, it.label) }, find = {
                        listOf(
                            org.meshtastic.aether.serial.SerialRadio("/dev/ttyUSB0", "CP2102 bridge", false),
                            org.meshtastic.aether.serial.SerialRadio("/dev/serial/by-id/usb-RAK-if00", "RAK4631", true),
                        )
                    })
                }
            }
        }
        onNode(hasText("Find USB radios") and hasClickAction()).performClick()
        waitForIdle()
        val rows = onAllNodes(hasClickAction() and (hasText("RAK4631") or hasText("CP2102 bridge"))).fetchSemanticsNodes()
        assertEquals(2, rows.size)
        assertTrue(rows[0].boundsInRoot.top < rows[1].boundsInRoot.top && rows[0].config.toString().contains("RAK4631"), "the likely radio is listed first")
        onNode(hasText("RAK4631") and hasClickAction()).performClick()
        assertEquals(listOf<LinkChoice>(LinkChoice.Serial("/dev/serial/by-id/usb-RAK-if00", "RAK4631")), chosen)
    }

    @Test
    fun `the USB pane says when no serial device is present`() = runComposeUiTest {
        setContent { AmbientTheme { SerialSection({}, find = { emptyList() }) } }
        onNode(hasText("Find USB radios") and hasClickAction()).performClick()
        waitForIdle()
        onNodeWithText(NO_SERIAL_PORTS).assertExists()
        onNode(hasText("Look again") and hasClickAction()).assertExists()
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

    // Regression: a field in error told a screen reader only Material's generic "Invalid input", while the real reason
    // was a separate supporting-text node.
    @Test
    fun `a blank host names its own error to a screen reader`() = runComposeUiTest {
        show()
        onNode(hasSetTextAction() and hasText("Node host")).performTextClearance()
        waitForIdle()
        onNode(hasSetTextAction() and SemanticsMatcher.expectValue(SemanticsProperties.Error, HOST_ERROR)).assertExists()
    }

    @Test
    fun `changing the link kind cancels the countdown`() = runComposeUiTest {
        val pending = PendingConnect(LinkChoice.Demo, Countdown(5))
        show(pending)
        onNodeWithContentDescription("WebSocket").performClick()
        assertTrue(pending.countdown.cancelled)
    }

    // Regression: Enter did not submit on desktop.
    @Test
    fun `Enter in the port field chooses the Tcp link`() = runComposeUiTest {
        val chosen = mutableListOf<LinkChoice>()
        show(chosen = chosen)
        onNodeWithContentDescription("Network").performClick()
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
        onNodeWithContentDescription("Network").assertIsSelected()
        onNodeWithContentDescription("Demo").assertIsNotSelected()
        onNodeWithContentDescription("Demo").performClick()
        onNodeWithContentDescription("Demo").assertIsSelected()
        onNodeWithContentDescription("Network").assertIsNotSelected()
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
            onNodeWithContentDescription(label).performClick()
            waitForIdle()
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNodes().forEach {
                assertEquals(0f, it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue(), "$label pane scrolls")
            }
        }
    }

    // Regression: the chooser clipped its labels mid-word at phone width; its buttons are icons, which must keep their names for a screen reader.
    @Test
    fun `at phone width every link type is an icon named by its label`() = runComposeUiTest {
        show(width = 360, height = 720, serial = true)
        for (label in listOf("Network", "USB", "WebSocket", "Demo")) {
            onNodeWithText(label).assertDoesNotExist()
            onNode(hasContentDescription(label) and hasClickAction()).assertWidthIsAtLeast(44.dp)
        }
        onAllNodesWithTag(LINK_LABEL_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    // Regression: the chooser stayed icon-only on a window wide enough for its labels, which M3 connected groups show.
    @Test
    fun `a wide chooser shows each label and names each button once`() = runComposeUiTest {
        show(width = 800, height = 600)
        onAllNodesWithTag(LINK_LABEL_TAG, useUnmergedTree = true).assertCountEquals(3)
        for (label in listOf("Network", "WebSocket", "Demo")) {
            val node = onNode(hasContentDescription(label) and hasClickAction()).fetchSemanticsNode()
            assertEquals(listOf(label), node.config[SemanticsProperties.ContentDescription], "$label is named once")
            assertFalse(node.config.contains(SemanticsProperties.Text), "$label is read twice")
        }
    }

    // Regression: the heading said only Connect, so with an icon-only chooser nothing on screen named the chosen link.
    @Test
    fun `the pane heading names the chosen link`() = runComposeUiTest {
        show(width = 360, height = 720)
        onNode(hasText("Connect over the network") and isHeading()).assertExists()
        onNode(hasContentDescription("Demo") and hasClickAction()).performClick()
        onNode(hasText("Play the demo") and isHeading()).assertExists()
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
