package org.meshtastic.aether.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3ExpressiveApi::class)
class MotionSchemeTest {
    // Regression: MaterialExpressiveTheme defaults to the expressive scheme, whose spatial springs overshoot; the piece is calm.
    @Test
    fun `the theme uses the standard motion scheme not the expressive one`() = runComposeUiTest {
        var seen: FiniteAnimationSpec<Float>? = null
        setContent { AmbientTheme { seen = MaterialTheme.motionScheme.defaultSpatialSpec() } }
        waitForIdle()
        val standard = MotionScheme.standard().defaultSpatialSpec<Float>()
        val expressive = MotionScheme.expressive().defaultSpatialSpec<Float>()
        assertEquals(standard, seen)
        assertNotEquals(expressive, seen)
    }
}
