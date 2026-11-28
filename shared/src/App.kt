package org.meshtastic.aether

import androidx.compose.runtime.mutableStateListOf
import org.meshtastic.aether.ui.PaneInput
import org.meshtastic.aether.ui.SettingsContent
import org.meshtastic.aether.midi.MidiChoice
import org.meshtastic.aether.midi.midiOutputNames
import org.meshtastic.aether.midi.midiSupported
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import org.meshtastic.aether.visual.SpatialSource
import org.meshtastic.aether.ui.AppCommands
import org.meshtastic.aether.ui.ConnectContext
import org.meshtastic.aether.ui.ConnectScreen
import org.meshtastic.aether.ui.ConnectTarget
import org.meshtastic.aether.ui.Countdown
import org.meshtastic.aether.ui.MidiSettingsState
import org.meshtastic.aether.ui.HudIcon
import org.meshtastic.aether.ui.HudIcons
import org.meshtastic.aether.ui.LinkChoice
import org.meshtastic.aether.ui.LinkKind
import org.meshtastic.aether.ui.MinTouch
import org.meshtastic.aether.ui.PendingConnect
import org.meshtastic.aether.ui.Settings
import org.meshtastic.aether.ui.TrackedUpdates
import org.meshtastic.aether.ui.UpdateChecks
import org.meshtastic.aether.ui.appUpdater
import org.meshtastic.aether.ui.autoConnectLink
import org.meshtastic.aether.ui.resolveLook
import org.meshtastic.aether.ui.startFailureText
import org.meshtastic.aether.update.BuildInfo
import org.meshtastic.aether.update.UpdateTrack

private const val AUTO_CONNECT_SECONDS = 5
private const val SECOND_MS = 1_000L

/**
 * How the host launched the app. [link] skips the connect screen; [startupError] is a launch argument the host could
 * not read, shown as a failure on the connect screen. [look] is a launch flag that overrides the saved look; with no
 * flag and nothing saved a [launchedFullscreen] start uses the Projector look. [midi] names a MIDI output the music
 * also plays to.
 */
@Immutable
data class LaunchOptions(
    val link: LinkChoice? = null,
    val startupError: String? = null,
    val look: PaletteChoice? = null,
    val launchedFullscreen: Boolean = false,
    val ask: Boolean = false,
    val midi: String? = null,
)

/**
 * What the host window offers the running session. [onToggleFullscreen] is the host's fullscreen switch, null where the
 * target has none, and [isFullscreen] its state. [hiddenCursor] is a blank pointer icon the app shows after 3 s without
 * pointer movement, null where the host cannot hide the cursor. Then whether a screen reader is on, and the menu
 * commands. [onEnterSpatial] opens the host's headset or AR view of the running session, null where it has none.
 * [sessions] runs the session where it must outlive the window; null runs it with the window.
 */
@Immutable
data class HostWindow(
    val onToggleFullscreen: (() -> Unit)? = null,
    val onEnterSpatial: ((SpatialSource) -> Unit)? = null,
    val isFullscreen: Boolean = false,
    val hiddenCursor: PointerIcon? = null,
    val screenReaderActive: Boolean = false,
    val commands: AppCommands? = null,
    val sessions: SessionHost? = null,
)

/** The app's saved settings and the platform it runs on; a test supplies a fake store. */
internal class AppEnvironment(val settings: Settings, val platform: Platform, val midi: String? = null) {
    /** The saved MIDI choices, with a `--midi` launch flag naming the output in place of the saved one. */
    fun midiChoice(): MidiChoice = settings.midi.let { saved -> if (midi != null) saved.copy(output = midi) else saved }
}

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
    AppRoot(settings, platform, launch, host)
}

/** How [platform] drives Settings: focus on Back for a remote or keyboard, tooltips where there is a keyboard. */
private fun paneInput(platform: Platform) =
    PaneInput(focusBack = platform.isTelevision || platform.hasKeyboard, tooltips = platform.hasKeyboard)

/** The safe drawing area a settings pane keeps inside. */
private val SafeArea: Modifier @Composable get() = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)

/** [App] with its settings and platform supplied, so a test can run it on a fake store. */
@Composable
internal fun AppRoot(
    settings: Settings,
    platform: Platform,
    launch: LaunchOptions = LaunchOptions(),
    host: HostWindow = HostWindow(),
) {
    val settingsState = rememberSettingsState(settings)
    val look = remember {
        LookState(resolveLook(launch.look, settings.savedPalette, launch.launchedFullscreen), settings)
    }
    AmbientTheme(look.palette) {
        val sessions = host.sessions ?: rememberWindowSessions()
        var started by remember { mutableStateOf(!platform.needsTapToStart) }
        val env = remember { AppEnvironment(settings, platform, launch.midi) }
        val opening = rememberOpening(settings, launch, env, sessions, started)
        val app = opening.app
        val saved = opening.saved
        val backStack = remember {
            // The launch link's session starts from an effect: open on its screen rather than flash the connect screen.
            val first = app.session ?: opening.launched
            mutableStateListOf(
                when {
                    !started -> Screen.TapToStart
                    first != null -> Screen.Playing(first)
                    else -> Screen.Connect
                },
            )
        }
        LaunchedEffect(app.session, started) { if (started) backStack.follow(app.session) }
        AutoConnect(app, started, saved)
        val content = rememberSettingsContent(settingsState, platform, Screen.SettingsHome in backStack)
        val directive = paneDirective()
        val input = paneInput(platform)
        val safeArea = SafeArea

        Box(Modifier.fillMaxSize().background(LocalAmbientPalette.current.backgroundBottom)) {
            AppNavDisplay(backStack, directive) {
                entry<Screen.TapToStart> {
                    TapToStart {
                        platform.resumeAudio()
                        backStack.reset(Screen.Connect)
                        started = true
                    }
                }
                entry<Screen.Connect> {
                    ConnectPane(app, settings, platform) {
                        app.pending?.countdown?.cancel()
                        backStack.add(Screen.SettingsHome)
                    }
                }
                settingsEntries(backStack, { content }, { showsTwoPanes(directive) }, input, safeArea)
                entry<Screen.Playing> { key ->
                    sessions.running?.takeIf { it.session === key.session }?.let { running ->
                        Running(running, env, look, host, app::stop)
                    }
                }
            }
        }
    }
}

/** What the settings panes edit: the update track and the MIDI choices, each saved as it changes. */
@Stable
private class SettingsState(private val settings: Settings) {
    var track by mutableStateOf(settings.updateTrack ?: UpdateTrack.of(BuildInfo.CHANNEL))
        private set
    var midi by mutableStateOf(settings.midi)
        private set

    fun chooseMidi(next: MidiChoice) {
        midi = next
        settings.midi = next
    }

    fun choose(next: UpdateTrack) {
        track = next
        settings.updateTrack = next
    }
}

/** The settings state, with the update checks on its track. */
@Composable
private fun rememberSettingsState(settings: Settings): SettingsState {
    val state = remember { SettingsState(settings) }
    UpdateChecks(appUpdater, state.track)
    return state
}

/** What the settings panes show now; the MIDI outputs are listed each time settings opens. */
@Composable
private fun rememberSettingsContent(state: SettingsState, platform: Platform, open: Boolean): SettingsContent {
    val updater = appUpdater
    val track = state.track
    val outputs = remember(open) { if (open && midiSupported()) midiOutputNames() else emptyList() }
    return SettingsContent(
        updates = if (updater != null && track != null) {
            TrackedUpdates(updater, track, state::choose, canBrowse = !platform.isTelevision)
        } else {
            null
        },
        midi = if (midiSupported()) MidiSettingsState(state.midi, outputs, state::chooseMidi) else null,
    )
}


/** The app's session state and the saved link it counts down to, or null when none. */
private class Opening(val app: AppSession, val saved: LinkChoice?, val launched: Session?)

/**
 * Starts the launch link, or picks the saved link to count down to. A window that comes back to a session [sessions]
 * already runs shows it and starts nothing, though its launch extras arrive again.
 */
@Composable
private fun rememberOpening(
    settings: Settings,
    launch: LaunchOptions,
    env: AppEnvironment,
    sessions: SessionHost,
    started: Boolean,
): Opening {
    val platform = env.platform
    val resumed = remember { sessions.isRunning }
    val initialLink = if (launch.ask || resumed) null else launch.link
    val startup = remember { initialLink?.let { runCatching { sessionFor(it) } } }
    val opening = remember {
        val launchError = launch.startupError.takeUnless { resumed }
        val saved = if (resumed) {
            null
        } else {
            autoConnectLink(settings.link, launch.ask, platform.autoConnects, initialLink != null, launchError != null)
        }
        val failure = launchError ?: startup?.exceptionOrNull()?.let(::startFailureText)
        val pending = saved?.let { PendingConnect(it, Countdown(AUTO_CONNECT_SECONDS)) }
        val app = AppSession(settings, sessions, { createEngine(it, platform, env.midiChoice()) }, failure, pending)
        Opening(app, saved, startup?.getOrNull())
    }
    // Started from an effect, not while composing, so an abandoned composition leaves no session running; and only
    // once the app has started, so a browser hears nothing before its tap.
    LaunchedEffect(started) {
        val launched = opening.launched?.takeIf { started } ?: return@LaunchedEffect
        if (!sessions.isRunning) opening.app.begin(launched)
        if (initialLink != null) settings.link = initialLink
    }
    return opening
}

/** A session host that lives as long as this window: closing the window ends its session. */
@Composable
private fun rememberWindowSessions(): SessionHost {
    val scope = rememberCoroutineScope()
    return remember { SessionHost(scope) }
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
private fun ConnectPane(app: AppSession, settings: Settings, platform: Platform, onSettings: () -> Unit) {
    val tvMargin = if (platform.isTelevision) {
        Modifier.padding(horizontal = TV_MARGIN, vertical = TV_MARGIN / 2)
    } else {
        Modifier
    }
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    Column(Modifier.windowInsetsPadding(insets).then(tvMargin).fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onSettings, Modifier.size(MinTouch).semantics { contentDescription = "Settings" }) {
                HudIcon(HudIcons.Settings)
            }
        }
        ConnectScreen(
            onChoose = app::start,
            context = ConnectContext(
                pending = app.pending,
                failure = app.failure,
                lastTcp = settings.link as? LinkChoice.Tcp,
                lastKind = LinkKind.entries.firstOrNull { it.name == settings.linkKind },
            ),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            target = remember { ConnectTarget(focusChooser = platform.isTelevision || platform.hasKeyboard) },
            onKind = { settings.linkKind = it.name },
        )
    }
}
