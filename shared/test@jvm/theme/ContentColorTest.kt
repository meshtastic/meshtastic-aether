package org.meshtastic.aether.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class ContentColorTest {
    // Regression: Text with no explicit color took Material's default content color, black, and the title and
    // headings vanished on the dark field.
    @Test
    fun `text with no explicit color inside the theme gets the on-surface color`() = runComposeUiTest {
        var seen = Color.Unspecified
        var onSurface = Color.Unspecified
        setContent {
            AmbientTheme {
                seen = LocalContentColor.current
                onSurface = MaterialTheme.colorScheme.onSurface
            }
        }
        waitForIdle()
        assertEquals(onSurface, seen)
        assertNotEquals(Color.Black, seen)
    }

    @Test
    fun `every palette keeps the content color readable against its own background`() = runComposeUiTest {
        for (choice in PaletteChoice.entries) {
            var seen = Color.Unspecified
            setContent { AmbientTheme(choice) { seen = LocalContentColor.current } }
            waitForIdle()
            assertNotEquals(Color.Black, seen, choice.name)
        }
    }
}
