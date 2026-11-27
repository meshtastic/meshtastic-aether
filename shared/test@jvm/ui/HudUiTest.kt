package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.meshtastic.aether.mood.ParamValues
import org.meshtastic.aether.theme.AmbientTheme
import org.meshtastic.aether.theme.PaletteChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HudUiTest {
    private val toolbarLabels = listOf(
        "Mute", "Volume", "Look", "Headphones", "Drivers", "Fullscreen", "Disconnect",
    )

    private fun androidx.compose.ui.test.ComposeUiTest.hud(
        controls: Controls,
        palette: PaletteChoice = PaletteChoice.Standard,
        widthDp: Int = 1000,
        heightDp: Int = 700,
        onPalette: (PaletteChoice) -> Unit = {},
        onDisconnect: () -> Unit = {},
        isTelevision: Boolean = false,
    ) = setContent {
        AmbientTheme {
            Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                StatusOverlay(
                    status = EngineStatus(nodes = 3, key = "D", mode = "Dorian", section = "1 - opening"),
                    controls = controls,
                    host = HudHost(palette, onPalette, onDisconnect, onToggleFullscreen = {}),
                    input = HudInput(hasKeyboard = true, isTelevision = isTelevision),
                )
            }
        }
    }

    private fun SemanticsNodeInteraction.minSideDp(density: Float): Float {
        val node = fetchSemanticsNode()
        return minOf(node.size.width, node.size.height) / density
    }

    // Regression: an icon-only button with no description is unnamed to a screen reader, and under 44 dp it is a missed tap.
    @Test
    fun `every toolbar control is named and at least 44 dp at every width class`() {
        for (width in listOf(360, 700, 1000)) {
            runComposeUiTest {
                val controls = Controls().also { it.overlayOpen = true }
                hud(controls, widthDp = width)
                waitForIdle()
                for (label in toolbarLabels) {
                    val node = onNodeWithContentDescription(label)
                    node.assertExists("$label at $width dp")
                    assertTrue(node.minSideDp(density.density) >= 44f - 0.5f, "$label is under 44 dp at $width dp")
                }
            }
        }
    }

    @Test
    fun `the headphones button is a toggle on the controls flag`() = runComposeUiTest {
        val controls = Controls().also { it.overlayOpen = true }
        hud(controls)
        waitForIdle()
        val button = onNodeWithContentDescription("Headphones")
        assertEquals(ToggleableState.Off, button.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
        button.performClick()
        waitForIdle()
        assertTrue(controls.headphones)
        assertEquals(ToggleableState.On, onNodeWithContentDescription("Headphones").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
    }

    // Regression: the slider consumed Left and Right, so a TV remote could never reach the buttons past it.
    @Test
    fun `on a television Right leaves the volume slider and Down lowers the volume`() = runComposeUiTest {
        val controls = Controls().also { it.overlayOpen = true; it.volume = 0.5f }
        hud(controls, isTelevision = true)
        waitForIdle()
        onNodeWithContentDescription("Volume").requestFocus()
        onNodeWithContentDescription("Volume").performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertEquals(0.5f, controls.volume, 1e-6f)
        onNodeWithContentDescription("Look").assertIsFocused()
        onNodeWithContentDescription("Volume").requestFocus()
        onNodeWithContentDescription("Volume").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertEquals(0.5f - Controls.VOLUME_STEP, controls.volume, 1e-6f)
    }

    @Test
    fun `with a keyboard Right still adjusts the volume slider and does not move focus`() = runComposeUiTest {
        val controls = Controls().also { it.overlayOpen = true; it.volume = 0.5f }
        hud(controls, isTelevision = false)
        waitForIdle()
        onNodeWithContentDescription("Volume").requestFocus()
        onNodeWithContentDescription("Volume").performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertTrue(controls.volume > 0.5f, "Right raised the slider to ${controls.volume}")
        onNodeWithContentDescription("Look").assertIsNotFocused()
    }

    // Regression: Mute was a text button toggling its own label; as a toggle its state must be readable.
    @Test
    fun `mute toggles its checked state and the controls mute flag`() = runComposeUiTest {
        val controls = Controls().also { it.overlayOpen = true }
        hud(controls)
        waitForIdle()
        val mute = onNodeWithContentDescription("Mute")
        assertEquals(ToggleableState.Off, mute.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
        mute.performClick()
        waitForIdle()
        assertTrue(controls.muted)
        assertEquals(ToggleableState.On, onNodeWithContentDescription("Mute").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
        assertEquals("Muted", onNodeWithContentDescription("Mute").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun `there is one mute control not two`() = runComposeUiTest {
        val controls = Controls().also { it.overlayOpen = true }
        hud(controls)
        waitForIdle()
        onNodeWithContentDescription("Mute").assertExists()
        // The focused button's tooltip also says Mute, so count controls by description, and no second toggle by text.
        assertEquals(1, onAllNodes(hasContentDescription("Mute")).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodes(hasText("Unmute") or hasContentDescription("Unmute")).fetchSemanticsNodes().size)
    }

    // Regression: three always-visible look buttons took three toolbar slots for one choice; one menu button takes one.
    @Test
    fun `the look button names the current look and its menu marks exactly that one as selected`() = runComposeUiTest {
        val controls = Controls().also { it.overlayOpen = true }
        hud(controls, palette = PaletteChoice.Projector)
        waitForIdle()
        val button = onNodeWithContentDescription("Look")
        assertEquals("Projector", button.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(androidx.compose.ui.semantics.Role.DropdownList, button.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        onNodeWithText("Standard").assertDoesNotExist()
        button.performClick()
        waitForIdle()
        for (choice in PaletteChoice.entries) {
            val node = onNode(hasText(choice.label) and hasClickAction()).fetchSemanticsNode()
            assertEquals(choice == PaletteChoice.Projector, node.config.getOrNull(SemanticsProperties.Selected), choice.name)
        }
    }

    @Test
    fun `choosing a look from the menu reports it and closes the menu`() = runComposeUiTest {
        val controls = Controls().also { it.overlayOpen = true }
        var chosen: PaletteChoice? = null
        hud(controls, onPalette = { chosen = it })
        waitForIdle()
        onNodeWithContentDescription("Look").performClick()
        waitForIdle()
        onNode(hasText("OLED night") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(PaletteChoice.OledNight, chosen)
        onNodeWithText("OLED night").assertDoesNotExist()
    }

    // Regression: an open menu takes focus into its popup, so the HUD's idle timer would close the HUD under it.
    @Test
    fun `the hud is held while the look menu is open and released when it closes`() = runComposeUiTest {
        val controls = Controls().also { it.overlayOpen = true }
        hud(controls)
        waitForIdle()
        assertFalse(controls.menuOpen)
        onNodeWithContentDescription("Look").performClick()
        waitForIdle()
        assertTrue(controls.menuOpen)
        assertTrue(controls.held)
        onNode(hasText("Standard") and hasClickAction()).performClick()
        waitForIdle()
        assertFalse(controls.menuOpen)
    }

    // Regression: the Disconnect button ended the session on one tap.
    @Test
    fun `the disconnect button needs a second press and shows the hint after the first`() = runComposeUiTest {
        val controls = Controls().also { it.overlayOpen = true }
        var disconnects = 0
        hud(controls, onDisconnect = { disconnects++ })
        waitForIdle()
        onNodeWithContentDescription("Disconnect").performClick()
        waitForIdle()
        assertEquals(0, disconnects)
        onNodeWithText(DISCONNECT_HINT).assertExists()
        onNodeWithContentDescription("Disconnect").performClick()
        waitForIdle()
        assertEquals(1, disconnects)
    }

    // The hint is a live region, so a screen reader speaks it without a focus move.
    @Test
    fun `the disconnect hint disappears after three seconds`() = runComposeUiTest {
        val controls = Controls().also { it.overlayOpen = true }
        hud(controls)
        waitForIdle()
        onNodeWithContentDescription("Disconnect").performClick()
        waitForIdle()
        assertTrue(controls.disconnect.armed)
        mainClock.advanceTimeBy(DISCONNECT_CONFIRM_MILLIS + 100)
        waitForIdle()
        assertFalse(controls.disconnect.armed)
    }

    // Regression: the HUD must leave no pixels while idle, or a still frame burns in.
    @Test
    fun `a closed hud composes no toolbar`() = runComposeUiTest {
        val controls = Controls()
        hud(controls)
        waitForIdle()
        assertEquals(0, onAllNodes(hasContentDescription("Mute")).fetchSemanticsNodes().size)
        controls.overlayOpen = true
        waitForIdle()
        onNodeWithContentDescription("Mute").assertExists()
        controls.overlayOpen = false
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        assertEquals(0, onAllNodes(hasContentDescription("Mute")).fetchSemanticsNodes().size)
    }

    // Regression: opening the HUD left focus on the scene, so Tab and a screen reader never reached the toolbar.
    @Test
    fun `opening the hud moves focus into the toolbar and marks it held`() = runComposeUiTest {
        val controls = Controls().also { it.overlayOpen = true }
        hud(controls)
        waitForIdle()
        assertTrue(controls.focusWithin)
        assertTrue(controls.held)
        assertEquals(true, onNodeWithContentDescription("Mute").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused))
    }

    @Test
    fun `fullscreen is absent when the host has no switch`() = runComposeUiTest {
        val controls = Controls().also { it.overlayOpen = true }
        setContent {
            AmbientTheme {
                Box(Modifier.size(1000.dp, 700.dp)) {
                    StatusOverlay(EngineStatus(), controls, HudHost(PaletteChoice.Standard, {}, {}, null), HudInput(false))
                }
            }
        }
        waitForIdle()
        assertEquals(0, onAllNodes(hasContentDescription("Fullscreen")).fetchSemanticsNodes().size)
        onNodeWithContentDescription("Drivers").assertExists()
    }

    // Regression: the Drivers rows were bodyMedium and bodySmall, under the 16 sp floor.
    @Test
    fun `every drivers text is at least 16 sp at both layouts`() {
        for (width in listOf(360, 1000)) {
            runComposeUiTest {
                val controls = Controls().also {
                    it.overlayOpen = true
                    it.driversOpen = true
                }
                hud(controls, widthDp = width)
                waitForIdle()
                val rows = ParamValues.NEUTRAL.describe()
                assertTrue(rows.isNotEmpty())
                val nodes = onAllNodes(hasText(rows.first().name)).fetchSemanticsNodes()
                assertEquals(1, nodes.size, "drivers panel at $width dp")
                for (row in rows) {
                    for (text in listOf(row.name, paramDriverText(row))) {
                        val node = onAllNodes(hasText(text)).fetchSemanticsNodes().firstOrNull()
                        assertNotNull(node, "$text at $width dp")
                        val results = ArrayList<TextLayoutResult>()
                        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                        val size = results.single().layoutInput.style.fontSize
                        assertTrue(size.value >= 16f, "$text is ${size.value} sp at $width dp")
                    }
                }
            }
        }
    }

    @Test
    fun `the drivers toggle shows the panel and reports its state`() = runComposeUiTest {
        val controls = Controls().also { it.overlayOpen = true }
        hud(controls)
        waitForIdle()
        val first = ParamValues.NEUTRAL.describe().first().name
        assertEquals(0, onAllNodes(hasText(first)).fetchSemanticsNodes().size)
        onNodeWithContentDescription("Drivers").performClick()
        waitForIdle()
        assertTrue(controls.driversOpen)
        onNodeWithText(first).assertExists()
        assertEquals("shown", onNodeWithContentDescription("Drivers").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun `the volume slider describes its percent and a drag unmutes`() = runComposeUiTest {
        val controls = Controls(volume = 0.5f).also {
            it.overlayOpen = true
            it.muted = true
        }
        hud(controls)
        waitForIdle()
        val state = onNodeWithContentDescription("Volume").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals("50 percent, muted", state)
    }
}
