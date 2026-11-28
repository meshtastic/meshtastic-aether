package org.meshtastic.aether

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.meshtastic.aether.theme.AmbientTheme
import org.meshtastic.aether.theme.PaletteChoice
import org.meshtastic.aether.ui.Controls
import org.meshtastic.aether.ui.EngineStatus
import org.meshtastic.aether.ui.HudHost
import org.meshtastic.aether.ui.HudInput
import org.meshtastic.aether.ui.KeyAction
import org.meshtastic.aether.ui.StatusOverlay
import org.meshtastic.aether.update.Asset
import org.meshtastic.aether.update.InstallMode
import org.meshtastic.aether.update.InstallState
import org.meshtastic.aether.update.PublishedBuild
import org.meshtastic.aether.update.UpdateState
import org.meshtastic.aether.update.UpdateTrack
import org.meshtastic.aether.update.Updater
import kotlin.test.Test
import kotlin.test.assertEquals

private val OFFERED = PublishedBuild(
    version = "0.1.0-SNAPSHOT-97f44b3",
    code = 50,
    tag = "snapshot",
    assets = mapOf("android" to Asset("aether-android.apk", "0".repeat(64))),
)

private class OfferingUpdater : Updater {
    override val update: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Available(OFFERED))
    override val install: StateFlow<InstallState> = MutableStateFlow(InstallState.Idle)

    override fun modeFor(latest: PublishedBuild) = InstallMode.INSTALL

    override fun follow(track: UpdateTrack) = Unit

    override suspend fun check(): UpdateState = update.value

    override fun install(latest: PublishedBuild) = Unit

    override fun openPage(latest: PublishedBuild) = Unit
}

@OptIn(ExperimentalTestApi::class)
class HudKeysUiTest {
    // Regression: the scene took Up and Down as volume from any focused HUD control, so a remote or keyboard could
    // never move up from the toolbar to the update panel's Install button.
    @Test
    fun `Up from the toolbar reaches the update panel and Up with nothing above still raises the volume`() =
        runComposeUiTest {
            val controls = Controls().also {
                it.overlayOpen = true
                it.updateOpen = true
                it.volume = 0.5f
            }
            setContent {
                val input = remember { SceneInput() }
                val perform = rememberUpdatedState<(KeyAction) -> Boolean> { action ->
                    controls.apply(action, fullscreen = false, canFullscreen = false).consumed
                }
                AmbientTheme {
                    val focus = LocalFocusManager.current
                    Box(Modifier.size(1000.dp, 700.dp).focusable().sceneKeys(controls, input, perform, focus)) {
                        StatusOverlay(
                            status = EngineStatus(),
                            controls = controls,
                            host = HudHost(PaletteChoice.Standard, {}, {}, onToggleFullscreen = {}, OfferingUpdater()),
                            input = HudInput(hasKeyboard = true, isTelevision = true),
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithContentDescription("Mute").requestFocus()
            onNodeWithContentDescription("Mute").performKeyInput { pressKey(Key.DirectionUp) }
            waitForIdle()
            onNode(hasText("Install") and hasClickAction()).assertIsFocused()
            assertEquals(0.5f, controls.volume)

            onNode(hasText("Install") and hasClickAction()).performKeyInput { pressKey(Key.DirectionUp) }
            waitForIdle()
            assertEquals(0.55f, controls.volume, 0.001f)
        }
}
