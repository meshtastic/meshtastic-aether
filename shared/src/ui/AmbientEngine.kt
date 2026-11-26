@file:OptIn(ExperimentalAtomicApi::class)

package org.meshtastic.aether.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.meshtastic.aether.audio.AudioSink
import org.meshtastic.aether.audio.BlockRenderer
import org.meshtastic.aether.log.Log
import org.meshtastic.aether.log.Tally
import org.meshtastic.aether.mesh.LinkState
import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.mesh.MeshSource
import org.meshtastic.aether.music.Composer
import org.meshtastic.aether.music.ControlEvent
import org.meshtastic.aether.music.Day
import org.meshtastic.aether.music.NoteEvent
import org.meshtastic.aether.music.Texture
import org.meshtastic.aether.mood.ParamValues
import org.meshtastic.aether.synth.Synth
import org.meshtastic.aether.visual.SceneModel
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

/** What the hidden overlay shows. */
data class EngineStatus(
    val link: LinkState = LinkState.Connecting,
    /** Orbs on screen: the nodes heard so far this session. */
    val nodes: Int = 0,
    /** Root of the sounding key, for example `D`. */
    val key: String = "",
    /** Mode name of the sounding key, for example `Dorian`. */
    val mode: String = "",
    /** The form section and how full it is, for example `2 - gathering`. */
    val section: String = "",
    /** The last link or source failure, until it is dismissed or a retry succeeds. */
    val error: String? = null,
    /** The mesh-driven parameters in force; the overlay's Drivers panel lists them. */
    val params: ParamValues = ParamValues.NEUTRAL,
    /** The way the cloud is actually turning: -1, 0 or +1; null before the first frame. The Drivers panel shows it next to the target. */
    val turningDirection: Int? = null,
)

/** Whether the radio link is up: everything but `Connecting` and `Lost` shows the field in full color. */
internal fun isLinked(state: LinkState): Boolean = state != LinkState.Connecting && state != LinkState.Lost

/** What one composer tick hands the main thread: the notes to show and the state to display. */
private class TickResult(
    val notes: List<NoteEvent>,
    val texture: Texture?,
    val minuteOfDay: Int,
    val key: String,
    val mode: String,
    val section: String,
    val params: ParamValues,
    val batteryMoved: Int,
)

/**
 * MeshSource -> Composer -> Synth -> AudioSink on one clock: the count of frames the synth has rendered. A dedicated
 * coroutine on [composerContext] owns the composer: events reach it through a channel, and each tick it fills
 * `[rendered, rendered + lookahead)`, hands the notes to the synth and the same notes back to the main thread
 * through a second channel. The scene and the status are touched only by the coroutine that calls [run]. A packet's
 * ripple fires when the sink's playback frame reaches the frame its note was scheduled for. [pinFormFull] holds the
 * long form at its full level (demo mode). [failureText] turns a failed link open into the line the person sees; the raw exception goes
 * to the log only. The engine starts silent: the app raises [volume].
 */
class AmbientEngine(
    private val source: MeshSource,
    private val sink: AudioSink,
    seed: Long,
    lookaheadMillis: Int,
    private val tickMillis: Long = 100,
    private val now: () -> Long = ::nowMillis,
    pinFormFull: Boolean = false,
    private val composerContext: CoroutineContext = Dispatchers.Default,
    private val failureText: (Throwable) -> String = { unreachableText(null) },
) {
    private val sampleRate = sink.sampleRate
    private val lookaheadFrames = lookaheadMillis.toLong() * sampleRate / 1000
    private val tickFrames = tickMillis * sampleRate / 1000
    private val composer = Composer(seed, sampleRate, pinFormFull)
    private val synth = Synth(sampleRate)
    private val rendered = AtomicLong(0L)
    private val gainBits = AtomicInt(0f.toRawBits())
    private var smoothedGain = 0f
    private val inbound = Channel<MeshEvent>(Channel.UNLIMITED)
    private val outbound = Channel<TickResult>(Channel.UNLIMITED)
    private val link = MutableStateFlow(LinkState.Connecting)
    private val tally = Tally()
    private var batteryMoved = 0
    private val coverage = CoverageReporter(write = { Log.i(TAG, it) }, extra = { " batt_moved=$batteryMoved" })
    private var notesThisMinute = 0
    private var nextSummaryMillis = Long.MIN_VALUE
    private var lastLogged = ""

    /** Drawn by [org.meshtastic.aether.visual.AmbientScene]; only the [run] coroutine and the frame loop touch it. */
    val scene = SceneModel(sampleRate).also { it.linked = false }

    var status by mutableStateOf(EngineStatus())
        private set

    /** The last texture the composer set; the scene's background drift follows its detune and noise. */
    var texture: Texture? = null
        private set

    /** Master volume 0..1, applied after the synth's limiter with a short glide; silent until set. */
    var volume: Float
        get() = Float.fromBits(gainBits.load())
        set(v) = gainBits.store(v.coerceIn(0f, 1f).toRawBits())

    /** Headphone mix: dry voices placed to the side of the head, eased in over a fraction of a second; stereo when false. */
    var headphones: Boolean
        get() = synth.headphones
        set(v) {
            synth.headphones = v
        }

    private val renderer = BlockRenderer { out, frames ->
        synth.render(out, frames)
        val target = Float.fromBits(gainBits.load())
        var g = smoothedGain
        for (i in 0 until frames) {
            g += (target - g) * GAIN_GLIDE
            out[2 * i] *= g
            out[2 * i + 1] *= g
        }
        smoothedGain = g
        rendered.addAndFetch(frames.toLong())
    }

    /** The sink's playback frame: the clock the scene is drawn on. */
    fun playbackFrame(): Long = sink.playbackFrame()

    /** [playbackFrame] in seconds. */
    fun playbackSeconds(): Double = playbackFrame().toDouble() / sampleRate

    /**
     * Runs until cancelled; [afterStart] opens a live link once the sink and source collector are up. A failure is
     * shown and retried with backoff, and a lost link is re-opened the same way.
     */
    suspend fun run(afterStart: suspend () -> Unit = {}): Nothing = coroutineScope {
        Log.i(TAG, "start sampleRate=$sampleRate lookaheadMs=${lookaheadFrames * 1000 / sampleRate}")
        val firstTick = CompletableDeferred<Unit>()
        launch(composerContext) { composerLoop(firstTick) }
        firstTick.await()
        sink.start(renderer)
        try {
            launch { for (r in outbound) apply(r) }
            launch {
                try {
                    source.events.collect { onEvent(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "mesh source failed", e)
                    status = status.copy(error = SOURCE_FAILED)
                }
            }
            launch { keepLinkOpen(afterStart) }
            awaitCancellation()
        } finally {
            sink.stop()
            Log.i(TAG, "stop")
        }
    }

    private suspend fun keepLinkOpen(open: suspend () -> Unit) {
        val backoff = Backoff()
        while (true) {
            try {
                open()
                status = status.copy(error = null)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "link open failed", e)
                status = status.copy(error = failureText(e))
                delay(backoff.next())
            }
        }
        link.collectLatest { state ->
            when (state) {
                LinkState.Connected -> backoff.reset()
                LinkState.Lost -> while (true) {
                    delay(backoff.next())
                    Log.i(TAG, "link lost, reopening")
                    try {
                        open()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.w(TAG, "reopen failed", e)
                    }
                }
                else -> Unit
            }
        }
    }

    /** Main thread: the scene sees the event now, the composer on its next tick. */
    private fun onEvent(e: MeshEvent) {
        scene.onEvent(e, if (e is MeshEvent.PacketHeard) dueFrameFor() else 0L)
        inbound.trySend(e)
        if (e is MeshEvent.Link) {
            if (e.state != status.link) Log.i(TAG, "link ${status.link} -> ${e.state}")
            status = status.copy(link = e.state)
            scene.linked = isLinked(e.state)
            link.value = e.state
        }
    }

    /** The composer takes a packet on its next tick and quantizes it to a quarter-second grid; so does this estimate. */
    private fun dueFrameFor(): Long {
        val earliest = rendered.load() + tickFrames
        val grid = sampleRate / 4L
        return -((-earliest).floorDiv(grid)) * grid
    }

    /** Main thread: shows what the composer scheduled. */
    private fun apply(r: TickResult) {
        for (n in r.notes) scene.onNote(n, n.atFrame)
        r.texture?.let {
            texture = it
            scene.onTexture(it)
        }
        scene.minuteOfDay = r.minuteOfDay
        scene.onParams(r.params)
        batteryMoved = r.batteryMoved
        status = status.copy(
            nodes = scene.orbs.size, key = r.key, mode = r.mode, section = r.section, params = r.params,
            turningDirection = scene.rotation.direction,
        )
    }

    private suspend fun composerLoop(firstTick: CompletableDeferred<Unit>) {
        tick()
        firstTick.complete(Unit)
        while (true) {
            delay(tickMillis)
            tick()
        }
    }

    /** Composer coroutine only. */
    private fun tick() {
        while (true) {
            val e = inbound.tryReceive().getOrNull() ?: break
            composer.onEvent(e)
            coverage.onEvent(e, now())
            if (e is MeshEvent.PacketHeard) tally.add(e.kind)
        }
        val ms = now()
        coverage.tick(ms)
        val moment = localMoment(ms)
        val renderedNow = rendered.load()
        val out = composer.advance(renderedNow, lookaheadFrames, moment)
        synth.schedule(out)
        notesThisMinute += out.notes.size
        var texture: Texture? = null
        for (c in out.controls) if (c is ControlEvent.SetTexture) texture = c.texture
        val day = Day.at(moment)
        val key = NOTE_NAMES[day.rootSemitones.roundToInt().mod(12)]
        val section = composer.sectionAt(renderedNow.toDouble() / sampleRate)
        outbound.trySend(TickResult(out.notes, texture, moment.minuteOfDay, key, day.mode.name, section, composer.params(), composer.moodSnapshot().batteryMovedNodes))
        logSummary(ms, key, day.mode.name, section)
    }

    /** One line a minute: what was heard, how much was scheduled, and the key, mode and section in force. */
    private fun logSummary(ms: Long, key: String, mode: String, section: String) {
        if (nextSummaryMillis == Long.MIN_VALUE) nextSummaryMillis = ms + SUMMARY_MILLIS
        if (ms < nextSummaryMillis) return
        nextSummaryMillis = ms + SUMMARY_MILLIS
        Log.i(
            TAG,
            "minute heard=${tally.total} (${tally.describe()}) notes=$notesThisMinute nodes=${scene.orbs.size} " +
                "link=${link.value} $key $mode section=$section volume=$volume${scene.frameStats.takeReport()?.let { " " + it.format() } ?: ""}",
        )
        tally.reset()
        notesThisMinute = 0
        val shape = "$key $mode"
        if (shape != lastLogged) {
            if (lastLogged.isNotEmpty()) Log.i(TAG, "key/mode $lastLogged -> $shape")
            lastLogged = shape
        }
    }

    private companion object {
        const val GAIN_GLIDE = 0.002f
        const val SUMMARY_MILLIS = 60_000L
        const val TAG = "engine"
        const val SOURCE_FAILED = "The mesh source stopped"
        val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    }
}
