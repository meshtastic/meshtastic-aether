package org.meshtastic.ambient

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.reload.DevelopmentEntryPoint
import org.meshtastic.ambient.audio.createAudioSink
import org.meshtastic.ambient.mesh.MeshSource
import org.meshtastic.ambient.mesh.SimulatedMeshSource
import org.meshtastic.ambient.ui.AmbientEngine
import org.meshtastic.ambient.ui.ConnectScreen
import org.meshtastic.ambient.ui.DemoCapture
import org.meshtastic.ambient.ui.LiveLink
import org.meshtastic.ambient.ui.LinkChoice
import org.meshtastic.ambient.ui.LoopingReplaySource
import org.meshtastic.ambient.ui.StatusOverlay
import org.meshtastic.ambient.ui.nowMillis
import org.meshtastic.ambient.visual.AmbientScene

/** One running session: where events come from, how to open the link, and how to close it. */
private class Session(
    val source: MeshSource,
    val open: suspend () -> Unit = {},
    val close: suspend () -> Unit = {},
    val seed: Long = nowMillis(),
)

private fun sessionFor(choice: LinkChoice): Session = when (choice) {
    LinkChoice.Simulated -> {
        val seed = nowMillis()
        Session(SimulatedMeshSource(seed, nowMillis = ::nowMillis), seed = seed)
    }
    LinkChoice.Replay -> Session(LoopingReplaySource(DemoCapture::lines, speed = 1.0, nowMillis = ::nowMillis))
    is LinkChoice.Tcp -> LiveLink.tcp(choice.host, choice.port)?.let { Session(it.source, it::connect, it::disconnect) }
        ?: error("TCP is not available on this target")
    is LinkChoice.WebSocket -> LiveLink.webSocket(choice.url).let { Session(it.source, it::connect, it::disconnect) }
}

/**
 * The app: a tap-to-start screen where the browser needs a gesture, then the connect screen, then the scene.
 * [onToggleFullscreen] is the host's fullscreen switch, null where the target has none.
 */
@Composable
@Preview
@DevelopmentEntryPoint
fun App(onToggleFullscreen: (() -> Unit)? = null) {
    val platform = remember { getPlatform() }
    MaterialTheme(colorScheme = darkColorScheme()) {
        var started by remember { mutableStateOf(!platform.needsTapToStart) }
        var session by remember { mutableStateOf<Session?>(null) }
        var failure by remember { mutableStateOf<String?>(null) }
        Box(Modifier.fillMaxSize().background(Color.Black).safeContentPadding()) {
            val current = session
            when {
                !started -> Box(
                    Modifier.fillMaxSize().clickable {
                        platform.resumeAudio()
                        started = true
                    },
                    contentAlignment = Alignment.Center,
                ) { Text("Tap to start", color = Color.White) }

                current == null -> {
                    ConnectScreen({ choice ->
                        try {
                            session = sessionFor(choice)
                            failure = null
                        } catch (e: Exception) {
                            failure = e.message
                        }
                    })
                    failure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.BottomCenter)) }
                }

                else -> Running(current, platform, onToggleFullscreen) { session = null }
            }
        }
    }
}

@Composable
private fun Running(session: Session, platform: Platform, onToggleFullscreen: (() -> Unit)?, onStop: () -> Unit) {
    val engine = remember(session) {
        AmbientEngine(session.source, createAudioSink(), session.seed, platform.lookaheadMillis).also { platform.resumeAudio() }
    }
    LaunchedEffect(engine) {
        try {
            engine.run(session.open)
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }
    var overlay by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(overlay, engine.status.error) {
        if (overlay && engine.status.error == null) {
            delay(15_000)
            overlay = false
        }
    }
    LaunchedEffect(engine.status.error) { if (engine.status.error != null) overlay = true }
    Box(
        Modifier.fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent {
                if (it.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (it.key) {
                    Key.H -> overlay = !overlay
                    Key.F -> onToggleFullscreen?.invoke() ?: return@onKeyEvent false
                    else -> return@onKeyEvent false
                }
                true
            }
            .pointerInput(Unit) { detectTapGestures { overlay = !overlay } },
    ) {
        AmbientScene(engine, Modifier.fillMaxSize())
        if (overlay) {
            StatusOverlay(engine, onStop, onToggleFullscreen, Modifier.align(Alignment.TopStart))
        }
    }
}
