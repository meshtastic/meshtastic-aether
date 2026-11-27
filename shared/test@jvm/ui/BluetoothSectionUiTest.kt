package org.meshtastic.aether.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.meshtastic.aether.ble.BleChooserException
import org.meshtastic.aether.ble.BleRadio
import org.meshtastic.aether.theme.AmbientTheme

/** The browser path of the Bluetooth pane: no scan, one button that opens the platform's chooser. */
@OptIn(ExperimentalTestApi::class)
class BluetoothSectionUiTest {
    private val picked = mutableListOf<BleRadio>()

    private fun ComposeUiTest.show(chooser: suspend () -> BleRadio?) = setContent {
        AmbientTheme { BluetoothSection(picked::add, chooser = chooser) }
    }

    private fun ComposeUiTest.errors() = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))

    // Regression: a radio picked in the browser's chooser never reached the session, so the web app could not connect.
    @Test
    fun `the radio picked in the chooser is the one handed on`() = runComposeUiTest {
        val radio = BleRadio("Bluetooth radio", null, "id-1", Unit)
        show { radio }
        onNodeWithText("Choose a Bluetooth radio").performClick()
        waitForIdle()
        assertEquals(listOf(radio), picked)
        onNodeWithText("Scan for Bluetooth radios").assertDoesNotExist()
    }

    // Regression: closing the browser's chooser without a pick read as a failure.
    @Test
    fun `a dismissed chooser picks nothing and shows no error`() = runComposeUiTest {
        show { null }
        onNodeWithText("Choose a Bluetooth radio").performClick()
        waitForIdle()
        assertEquals(emptyList(), picked)
        errors().assertCountEquals(0)
        onNodeWithText("Choose a Bluetooth radio").assertIsEnabled()
    }

    // Regression: a chooser that throws, as on a page without Bluetooth access, left the pane silent and its button dead.
    @Test
    fun `a chooser that fails says why and can be opened again`() = runComposeUiTest {
        show { throw BleChooserException("Bluetooth adapter not available", IllegalStateException()) }
        onNodeWithText("Choose a Bluetooth radio").performClick()
        waitForIdle()
        assertEquals(emptyList(), picked)
        onNodeWithText("Bluetooth adapter not available").assertExists()
        onNodeWithText("Choose a Bluetooth radio").assertIsEnabled()
    }
}
