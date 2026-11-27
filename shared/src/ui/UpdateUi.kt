package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.meshtastic.aether.update.InstallFailure
import org.meshtastic.aether.update.InstallMode
import org.meshtastic.aether.update.InstallState
import org.meshtastic.aether.update.PublishedBuild
import org.meshtastic.aether.update.UpdateState
import org.meshtastic.aether.update.UpdateTrack
import org.meshtastic.aether.update.Updater
import org.meshtastic.aether.update.createUpdater
import org.meshtastic.aether.update.nextCheckDelayMillis

private const val FIRST_CHECK_DELAY_MS = 10_000L
private val PANEL_WIDTH = 360.dp

/** The process's updater: null on iOS, the web, an app-store install and a local build. */
internal val appUpdater: Updater? by lazy { createUpdater() }

/**
 * Checks 10 s after launch, then daily (hourly after a failure or an empty channel) for as long as the app runs. A
 * change of [track] follows the new one and checks it at once.
 */
@Composable
internal fun UpdateChecks(updater: Updater?, track: UpdateTrack?) {
    if (updater == null) return
    val launched = remember { BooleanArray(1) }
    LaunchedEffect(updater, track) {
        track?.let(updater::follow)
        val first = !launched[0]
        launched[0] = true
        if (first) delay(FIRST_CHECK_DELAY_MS)
        while (true) {
            delay(nextCheckDelayMillis(updater.check()))
        }
    }
}

/** The newer build to offer, or null when there is none. */
internal fun offered(state: UpdateState): PublishedBuild? = (state as? UpdateState.Available)?.latest

internal fun actionLabel(mode: InstallMode): String = when (mode) {
    InstallMode.INSTALL -> "Install"
    InstallMode.INSTALL_AND_RESTART -> "Install and restart"
    InstallMode.OPEN_PAGE -> "Open download page"
}

internal fun failureText(failure: InstallFailure): String = when (failure) {
    InstallFailure.DOWNLOAD -> "The download did not finish."
    InstallFailure.CHECKSUM -> "The download did not match its checksum."
    InstallFailure.NOT_THIS_APP -> "The download is not an Aether package."
    InstallFailure.DIFFERENT_KEY -> "The update is signed with another key; install it from the download page."
    InstallFailure.NOT_NEWER -> "The download is not newer than this build."
    InstallFailure.INSTALLER -> "The update could not be installed."
}

/** What the update panel says about the install under way, or null while none runs. */
internal fun installText(state: InstallState, mode: InstallMode): String? = when (state) {
    InstallState.Idle -> null
    is InstallState.Downloading -> state.percent?.let { "Downloading $it%" } ?: "Downloading"
    InstallState.Installing -> if (mode == InstallMode.INSTALL_AND_RESTART) "Restarting" else "Confirm the install when Android asks"
    is InstallState.Failed -> failureText(state.failure) + (state.detail?.let { " ($it)" } ?: "")
}

/** The toolbar's update button: shown only while a newer build is published. */
@Composable
internal fun UpdateButton(updater: Updater?, controls: Controls, label: @Composable (onClick: () -> Unit) -> Unit) {
    val state by (updater ?: return).update.collectAsState()
    if (offered(state) == null) return
    label { controls.updateOpen = !controls.updateOpen }
}

/** The newer build, the platform's way to get it in, and the install's progress or failure. */
@Composable
internal fun UpdatePanel(updater: Updater, modifier: Modifier = Modifier) {
    val state by updater.update.collectAsState()
    val install by updater.install.collectAsState()
    val latest = offered(state) ?: return
    val mode = updater.modeFor(latest)
    Surface(modifier.widthIn(max = PANEL_WIDTH), color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Aether ${latest.version} is ready", style = MaterialTheme.typography.titleMedium)
            installText(install, mode)?.let {
                Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge)
            }
            val downloading = install as? InstallState.Downloading
            if (downloading != null) {
                val percent = downloading.percent
                if (percent != null) LinearProgressIndicator(progress = { percent / 100f }) else LinearProgressIndicator()
            }
            val busy = install is InstallState.Downloading || install == InstallState.Installing
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (install is InstallState.Failed && mode != InstallMode.OPEN_PAGE) {
                    Button(onClick = { updater.install(latest) }) { Text("Try again") }
                    TextButton(onClick = { updater.openPage(latest) }) { Text(actionLabel(InstallMode.OPEN_PAGE)) }
                } else {
                    Button(onClick = { updater.install(latest) }, enabled = !busy) { Text(actionLabel(mode)) }
                }
            }
        }
    }
}
