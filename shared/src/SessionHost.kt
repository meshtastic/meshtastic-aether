package org.meshtastic.aether

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.meshtastic.aether.audio.createAudioSink
import org.meshtastic.aether.midi.MidiChoice
import org.meshtastic.aether.midi.openLiveOutput
import org.meshtastic.aether.ui.AmbientEngine
import org.meshtastic.aether.ui.EngineOptions
import org.meshtastic.aether.ui.EngineTiming
import org.meshtastic.aether.ui.fadeIn
import org.meshtastic.aether.ui.nowMillis

private const val FADE_STEP_MS = 33L

/**
 * Runs the session outside any composition, in [scope]: its engine, its link, the master volume and its fade-in, and a
 * pause the system media controls or audio focus hold. A window that goes away and comes back shows the same session.
 * Android keeps one for the life of the process; the other platforms tie it to their window.
 */
@Stable
class SessionHost internal constructor(private val scope: CoroutineScope, private val now: () -> Long) {
    constructor(scope: CoroutineScope) : this(scope, ::nowMillis)

    internal var running: RunningSession? by mutableStateOf(null)
        private set

    /** True while a session runs. */
    val isRunning: Boolean get() = running != null

    /** True while the session is held silent; never saved, and cleared when the session ends. */
    var paused by mutableStateOf(false)
        private set

    private var volume = 0f
    private var fade = 0f

    /**
     * Plays [session] on [engine], fading in from silence, in place of any running session. It never passes through not
     * running, which would stop Android's foreground service under the new session.
     */
    internal fun start(session: Session, engine: AmbientEngine) {
        val previous = running
        paused = false
        fade = 0f
        val started = now()
        val job = scope.launch {
            launch {
                while (fade < 1f) {
                    delay(FADE_STEP_MS)
                    fade = fadeIn(now() - started)
                    apply()
                }
            }
            try {
                engine.run(session.open)
            } finally {
                withContext(NonCancellable) { session.close() }
            }
        }
        running = RunningSession(session, engine, job, this)
        previous?.job?.cancel()
        apply()
    }

    /** Ends the session: the link closes and the audio stops. */
    fun stop() {
        val current = running ?: return
        running = null
        paused = false
        current.job.cancel()
    }

    /** Silences the session or restores it, without touching the saved mute. */
    fun pause(value: Boolean) {
        if (running == null) return
        paused = value
        apply()
    }

    /** The viewer's volume, muted included; it sounds once the fade-in allows and no pause holds it. */
    internal fun setVolume(value: Float) {
        volume = value
        apply()
    }

    private fun apply() {
        running?.engine?.volume = if (paused) 0f else volume * fade
    }
}

/** A session [sessions] is running, with the engine that plays it. */
internal class RunningSession(val session: Session, val engine: AmbientEngine, val job: Job, val sessions: SessionHost)

/** The engine for [session], with [midi]'s output when one is chosen; its link opens once the host runs it. */
internal fun createEngine(session: Session, platform: Platform, midi: MidiChoice): AmbientEngine =
    AmbientEngine(
        session.source,
        createAudioSink(),
        session.seed,
        EngineTiming(platform.lookaheadMillis),
        EngineOptions(
            pinFormFull = session.demo,
            failureText = session.failureText,
            midi = midi.output?.let { name -> { rate -> openLiveOutput(name, rate, midi) } },
            midiMutesBuiltIn = !midi.playAlongside,
        ),
    ).also { platform.resumeAudio() }
