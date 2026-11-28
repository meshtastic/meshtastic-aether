package org.meshtastic.aether.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.meshtastic.aether.theme.AmbientTheme
import org.meshtastic.aether.update.Asset
import org.meshtastic.aether.update.InstallFailure
import org.meshtastic.aether.update.InstallMode
import org.meshtastic.aether.update.InstallState
import org.meshtastic.aether.update.PublishedBuild
import org.meshtastic.aether.update.UpdateState
import org.meshtastic.aether.update.UpdateTrack
import org.meshtastic.aether.update.Updater
import kotlin.test.Test
import kotlin.test.assertEquals

private val LATEST = PublishedBuild(
    version = "0.1.0-SNAPSHOT-97f44b3",
    code = 50,
    tag = "snapshot",
    assets = mapOf("android" to Asset("aether-android.apk", "0".repeat(64))),
)

private class FakeUpdater(installState: InstallState = InstallState.Idle) : Updater {
    override val update: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Available(LATEST))
    override val install: StateFlow<InstallState> = MutableStateFlow(installState)
    val installed = mutableListOf<PublishedBuild>()

    override fun modeFor(latest: PublishedBuild) = InstallMode.INSTALL

    override fun follow(track: UpdateTrack) = Unit

    override suspend fun check(): UpdateState = update.value

    override fun install(latest: PublishedBuild) {
        installed += latest
    }

    override fun openPage(latest: PublishedBuild) = Unit
}

@OptIn(ExperimentalTestApi::class)
class SettingsUpdateUiTest {
    private fun androidx.compose.ui.test.ComposeUiTest.settings(updater: Updater, canBrowse: Boolean) = setContent {
        AmbientTheme {
            val updates = TrackedUpdates(updater, UpdateTrack.Snapshot, {}, canBrowse)
            SettingsTopicPane(SettingsTopic.Updates, SettingsContent(updates, midi = null), PaneChrome(onBack = {}))
        }
    }

    @Test
    fun `an offered update installs from Settings`() = runComposeUiTest {
        val updater = FakeUpdater()
        settings(updater, canBrowse = false)
        onNodeWithText("Install").performScrollTo().performClick()
        assertEquals(listOf(LATEST), updater.installed)
    }

    @Test
    fun `a TV is not offered the download page after a failed install`() = runComposeUiTest {
        settings(FakeUpdater(InstallState.Failed(InstallFailure.DOWNLOAD)), canBrowse = false)
        onNodeWithText("Try again").performScrollTo()
        onNodeWithText("Open download page").assertDoesNotExist()
    }

    @Test
    fun `elsewhere a failed install offers the download page`() = runComposeUiTest {
        settings(FakeUpdater(InstallState.Failed(InstallFailure.DOWNLOAD)), canBrowse = true)
        onNodeWithText("Open download page").performScrollTo()
    }
}
