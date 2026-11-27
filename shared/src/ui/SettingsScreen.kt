@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.meshtastic.aether.update.BuildInfo
import org.meshtastic.aether.update.UpdateState
import org.meshtastic.aether.update.UpdateTrack
import org.meshtastic.aether.update.Updater

private val SettingsWidth = 640.dp

/** What each track follows, in the words the settings list shows. */
internal fun trackLabel(track: UpdateTrack): String = when (track) {
    UpdateTrack.Release -> "Release"
    UpdateTrack.Snapshot -> "Snapshot"
}

internal fun trackDetail(track: UpdateTrack): String = when (track) {
    UpdateTrack.Release -> "Tagged versions, tested before they go out"
    UpdateTrack.Snapshot -> "Every build of main that passes its checks, newest first"
}

/** What the check row says about [state] for the build that is running. */
internal fun checkStatus(state: UpdateState): String = when (state) {
    UpdateState.Idle -> "Not checked yet"
    UpdateState.Checking -> "Checking"
    is UpdateState.UpToDate -> "Up to date with ${state.latest.version}"
    is UpdateState.Available -> "Aether ${state.latest.version} is ready"
    UpdateState.NothingPublished -> "Nothing is published on this track yet"
    UpdateState.Failed -> "Could not reach the update server"
}

/**
 * Settings: which builds the app follows for updates, and a check on demand. [updater] is null where updates come another
 * way (an app store, iOS, the web) or on a local build, and the screen then says so instead of offering a choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    updater: Updater?,
    track: UpdateTrack?,
    onTrack: (UpdateTrack) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Column(modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        LargeFlexibleTopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onBack, Modifier.semantics { contentDescription = "Back" }) { HudIcon(HudIcons.Back) }
            },
            scrollBehavior = scrollBehavior,
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = SettingsWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                SectionHeading("Updates")
                if (updater == null || track == null) {
                    Text(
                        "This copy of Aether gets its updates another way.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    UpdateSettings(updater, track, onTrack)
                }
                SectionHeading("About")
                AboutSection()
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun UpdateSettings(updater: Updater, track: UpdateTrack, onTrack: (UpdateTrack) -> Unit) {
    val state by updater.update.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        val tracks = UpdateTrack.entries
        tracks.forEachIndexed { index, choice ->
            val selected = choice == track
            SegmentedListItem(
                selected = selected,
                onClick = { onTrack(choice) },
                shapes = ListItemDefaults.segmentedShapes(index, tracks.size + 1),
                modifier = Modifier.semantics { role = Role.RadioButton },
                leadingContent = { RadioButton(selected = selected, onClick = null) },
                supportingContent = { Text(trackDetail(choice)) },
                content = { Text(trackLabel(choice)) },
            )
        }
        SegmentedListItem(
            selected = false,
            onClick = { scope.launch { updater.check() } },
            shapes = ListItemDefaults.segmentedShapes(tracks.size, tracks.size + 1),
            enabled = state != UpdateState.Checking,
            trailingContent = { if (state == UpdateState.Checking) LoadingIndicator(Modifier.padding(4.dp)) },
            supportingContent = { Text(checkStatus(state), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
            content = { Text("Check for updates") },
        )
    }
    Text(
        "Switching from Snapshot to Release never downgrades: the next release that is newer than this build is offered.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val SOURCE_URL = "https://github.com/meshtastic/meshtastic-aether"
private const val NOTICE_URL = "$SOURCE_URL/blob/main/NOTICE"
private const val WEBSITE_URL = "https://meshtastic.org"
private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
private const val PRIVACY_URL = "https://meshtastic.org/docs/legal/privacy/"
private const val TRADEMARK_URL = "https://meshtastic.org/docs/legal/licensing-and-trademark/"
private const val CONDUCT_URL = "https://meshtastic.org/docs/legal/conduct/"

internal const val COPYRIGHT_NOTICE = "Meshtastic® Copyright Meshtastic LLC. Meshtastic® is a registered trademark of Meshtastic LLC. Aether is a Meshtastic project."
internal const val LICENSE_NOTICE = "Free software under the GNU General Public License v3 or later, with no warranty. You may redistribute it under the same license."

/** The project's links, then who owns the name and under what license the app is shared. */
@Composable
private fun AboutSection() {
    val uriHandler = LocalUriHandler.current
    val links = listOf(
        "Version" to ("Aether ${BuildInfo.VERSION}" to null),
        "Source code" to ("github.com/meshtastic/meshtastic-aether" to SOURCE_URL),
        "Meshtastic website" to ("meshtastic.org" to WEBSITE_URL),
        "License" to ("GNU General Public License v3" to LICENSE_URL),
        "Acknowledgements" to ("Ported code and its notices" to NOTICE_URL),
        "Privacy policy" to ("meshtastic.org/docs/legal/privacy" to PRIVACY_URL),
        "Licensing and trademark" to ("meshtastic.org/docs/legal/licensing-and-trademark" to TRADEMARK_URL),
        "Code of conduct" to ("meshtastic.org/docs/legal/conduct" to CONDUCT_URL),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        links.forEachIndexed { index, (title, detail) ->
            val url = detail.second
            SegmentedListItem(
                selected = false,
                onClick = { url?.let(uriHandler::openUri) },
                shapes = ListItemDefaults.segmentedShapes(index, links.size),
                enabled = url != null,
                supportingContent = { Text(detail.first) },
                content = { Text(title) },
            )
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (notice in listOf(COPYRIGHT_NOTICE, LICENSE_NOTICE)) {
            Text(
                notice,
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
