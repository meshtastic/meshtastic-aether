package org.meshtastic.aether

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.meshtastic.aether.ui.Controls
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SceneSemanticsUiTest {
    // Regression: the scene only had a raw tap gesture, so a screen reader found no action on it and could not open the
    // HUD that holds Mute and Disconnect while the audio plays.
    @Test
    fun `a screen reader click on the scene opens the HUD and a second closes it`() = runComposeUiTest {
        val controls = Controls()
        setContent { Box(Modifier.size(200.dp).sceneSemantics(controls, status = null)) }

        val scene = onNodeWithContentDescription(SCENE_LABEL)
        scene.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        scene.performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(controls.overlayOpen)
        scene.performSemanticsAction(SemanticsActions.OnClick)
        assertFalse(controls.overlayOpen)
    }

    // Regression: "Link lost - retrying" and open errors only faded in and out as plain text on the scene, so a screen
    // reader never heard that the link had dropped.
    @Test
    fun `the scene announces the link status politely as its state`() = runComposeUiTest {
        var status by mutableStateOf<String?>(null)
        setContent { Box(Modifier.size(200.dp).sceneSemantics(Controls(), status)) }

        status = "Link lost - retrying"
        val scene = onNodeWithContentDescription(SCENE_LABEL)
        scene.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Link lost - retrying"))
        scene.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }
}
