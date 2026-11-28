package org.meshtastic.aether

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
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
        setContent { Box(Modifier.size(200.dp).sceneSemantics(controls)) }

        val scene = onNodeWithContentDescription(SCENE_LABEL)
        scene.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        scene.performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(controls.overlayOpen)
        scene.performSemanticsAction(SemanticsActions.OnClick)
        assertFalse(controls.overlayOpen)
    }
}
