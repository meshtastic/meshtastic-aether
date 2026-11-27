package org.meshtastic.aether

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.meshtastic.aether.theme.AmbientTheme
import org.meshtastic.aether.theme.LocalAmbientPalette
import org.meshtastic.aether.theme.PaletteChoice
import org.meshtastic.aether.ui.AppCommands
import org.meshtastic.aether.ui.ConnectContext
import org.meshtastic.aether.ui.ConnectScreen
import org.meshtastic.aether.ui.ConnectTarget
import org.meshtastic.aether.ui.Countdown
import org.meshtastic.aether.ui.LinkChoice
import org.meshtastic.aether.ui.LinkKind
import org.meshtastic.aether.ui.PendingConnect
import org.meshtastic.aether.ui.Settings
import org.meshtastic.aether.ui.UpdateChecks
import org.meshtastic.aether.ui.appUpdater
import org.meshtastic.aether.ui.autoConnectLink
import org.meshtastic.aether.ui.resolveLook
import org.meshtastic.aether.ui.startFailureText

private const val AUTO_CONNECT_SECONDS = 5
private const val SECOND_MS = 1_000L

/**
 * How the host launched the app. [link] skips the connect screen; [startupError] is a launch argument the host could
 * not read, shown as a failure on the connect screen. [look] is a launch flag that overrides the saved look; with no
 * flag and nothing saved a [launchedFullscreen] start uses the Projector look.
 */
@Immutable
data class LaunchOptions(
    val link: LinkChoice? = null,
    val startupError: String? = null,
    val look: PaletteChoice? = null,
    val launchedFullscreen: Boolean = false,
    val ask: Boolean = false,
)

/**
 * What the host window offers the running session. [onToggleFullscreen] is the host's fullscreen switch, null where the
 * target has none, and [isFullscreen] its state. [hiddenCursor] is a blank pointer icon the app shows after 3 s without
 * pointer movement, null where the host cannot hide the cursor. Then whether a screen reader is on, and the menu
 * commands.
 */
@Immutable
data class HostWindow(
    val onToggleFullscreen: (() -> Unit)? = null,
    val isFullscreen: Boolean = false,
    val hiddenCursor: PointerIcon? = null,
    val screenReaderActive: Boolean = false,
    val commands: AppCommands? = null,
)

/** The app's saved settings and the platform it runs on; a test supplies a fake store. */
internal class AppEnvironment(val settings: Settings, val platform: Platform)

/** The look in force; a pick is saved for the next launch. */
@Stable
internal class LookState(initial: PaletteChoice, private val settings: Settings) {
    var palette by mutableStateOf(initial)
        private set

    fun choose(choice: PaletteChoice) {
        palette = choice
        settings.palette = choice
    }
}

/** The app: a tap-to-start screen where the browser needs a gesture, then the connect screen, then the scene. */
@Composable
fun App(launch: LaunchOptions = LaunchOptions(), host: HostWindow = HostWindow()) {
    val platform = remember { getPlatform() }
    val settings = remember { Settings(createKeyValueStore()) }
    UpdateChecks(appUpdater)
    AppRoot(settings, platform, launch, host)
}

/** [App] with its settings and platform supplied, so a test can run it on a fake store. */
@Composable
internal fun AppRoot(
    settings: Settings,
    platform: Platform,
    launch: LaunchOptions = LaunchOptions(),
    host: HostWindow = HostWindow(),
) {
    val initialLink = if (launch.ask) null else launch.link
    val look = remember {
        LookState(resolveLook(launch.look, settings.savedPalette, launch.launchedFullscreen), settings)
    }
    AmbientTheme(look.palette) {
        val startup = remember { initialLink?.let { runCatching { sessionFor(it) } } }
        var started by remember { mutableStateOf(!platform.needsTapToStart) }
        val saved = remember {
            val launchLink = initialLink != null
            autoConnectLink(settings.link, launch.ask, platform.autoConnects, launchLink, launch.startupError != null)
        }
        val app = remember {
            val failure = launch.startupError ?: startup?.exceptionOrNull()?.let(::startFailureText)
            val pending = saved?.let { PendingConnect(it, Countdown(AUTO_CONNECT_SECONDS)) }
            AppSession(settings, startup?.getOrNull(), failure, pending)
        }

        LaunchedEffect(Unit) { if (startup?.isSuccess == true && initialLink != null) settings.link = initialLink }
        AutoConnect(app, started, saved)

        Box(Modifier.fillMaxSize().background(LocalAmbientPalette.current.backgroundBottom)) {
            val current = app.session
            when {
                !started -> TapToStart {
                    platform.resumeAudio()
                    started = true
                }
                current == null -> ConnectPane(app, settings, platform)
                else -> Running(current, remember { AppEnvironment(settings, platform) }, look, host, app::stop)
            }
        }
    }
}

/** Counts a saved link down once the app has started, then starts it, unless the countdown was cancelled. */
@Composable
private fun AutoConnect(app: AppSession, started: Boolean, saved: LinkChoice?) {
    LaunchedEffect(app.pending, started) {
        val p = app.pending ?: return@LaunchedEffect
        if (!started || saved == null) return@LaunchedEffect
        while (!p.countdown.cancelled) {
            delay(SECOND_MS)
            if (p.countdown.tick()) {
                app.start(saved)
                break
            }
        }
    }
}

/** The browser needs a gesture before it plays audio: one tap anywhere starts the app. */
@Composable
private fun TapToStart(onStart: () -> Unit) {
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .clickable(onClickLabel = "Start", role = Role.Button, onClick = onStart)
            .semantics { contentDescription = "Tap to start" },
        contentAlignment = Alignment.Center,
    ) {
        Text("Tap to start", style = MaterialTheme.typography.headlineSmall, color = LocalAmbientPalette.current.text)
    }
}

/** Extra inset on a television, where the picture edge can be cropped by overscan. */
private val TV_MARGIN = 48.dp

@Composable
private fun ConnectPane(app: AppSession, settings: Settings, platform: Platform) {
    val tvMargin = if (platform.isTelevision) {
        Modifier.padding(horizontal = TV_MARGIN, vertical = TV_MARGIN / 2)
    } else {
        Modifier
    }
    ConnectScreen(
        onChoose = app::start,
        context = ConnectContext(
            pending = app.pending,
            failure = app.failure,
            lastTcp = settings.link as? LinkChoice.Tcp,
            lastKind = LinkKind.entries.firstOrNull { it.name == settings.linkKind },
        ),
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).then(tvMargin),
        target = remember { ConnectTarget(focusChooser = platform.isTelevision || platform.hasKeyboard) },
        onKind = { settings.linkKind = it.name },
    )
}
