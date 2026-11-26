@file:OptIn(ExperimentalAtomicApi::class)

package org.meshtastic.ambient.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.meshtastic.ambient.audio.AudioSink
import org.meshtastic.ambient.audio.BlockRenderer
import org.meshtastic.ambient.mesh.LinkState
import org.meshtastic.ambient.mesh.MeshEvent
import org.meshtastic.ambient.mesh.MeshSource
import org.meshtastic.ambient.music.Composer
import org.meshtastic.ambient.music.ControlEvent
import org.meshtastic.ambient.music.Day
import org.meshtastic.ambient.music.Form
import org.meshtastic.ambient.music.Texture
import org.meshtastic.ambient.synth.Synth
import org.meshtastic.ambient.visual.SceneModel
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.roundToInt

/** What the hidden overlay shows. */
data class EngineStatus(
    val link: LinkState = LinkState.Connecting,
    val nodes: Int = 0,
    val key: String = "",
    val mode: String = "",
    val section: String = "",
    val error: String? = null,
)

/**
 * MeshSource -> Composer -> Synth -> AudioSink on one clock: the count of frames the synth has rendered. Each tick
 * the composer fills `[rendered, rendered + lookahead)`; the scene fires a packet's ripple when the sink's playback
 * frame reaches the frame that packet's note was scheduled for. Everything but the audio thread runs in the
 * coroutine that calls [run], so the composer and scene need no locks.
 */
class AmbientEngine(
    private val source: MeshSource,
    private val sink: AudioSink,
    seed: Long,
    lookaheadMillis: Int,
    private val tickMillis: Long = 100,
    private val now: () -> Long = ::nowMillis,
) {
    private val sampleRate = sink.sampleRate
    private val lookaheadFrames = lookaheadMillis.toLong() * sampleRate / 1000
    private val tickFrames = tickMillis * sampleRate / 1000
    private val composer = Composer(seed, sampleRate)
    private val synth = Synth(sampleRate)
    private val form = Form(seed)
    private val rendered = AtomicLong(0L)
    private val gainBits = AtomicInt(1f.toRawBits())
    private var smoothedGain = 1f

    val scene = SceneModel(sampleRate)

    var status by mutableStateOf(EngineStatus())
        private set

    /** The last texture the composer set; the scene's background drift follows its detune and noise. */
    var texture: Texture? = null
        private set

    /** Master volume 0..1, applied after the synth's limiter with a short glide. */
    var volume: Float
        get() = Float.fromBits(gainBits.load())
        set(v) = gainBits.store(v.coerceIn(0f, 1f).toRawBits())

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

    /** Runs until cancelled; [afterStart] runs once the sink and source collector are up (a live link connects there). */
    suspend fun run(afterStart: suspend () -> Unit = {}) = coroutineScope {
        tick()
        sink.start(renderer)
        try {
            launch {
                try {
                    source.events.collect { onEvent(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    status = status.copy(error = e.message ?: e::class.simpleName)
                }
            }
            launch {
                try {
                    afterStart()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    status = status.copy(error = e.message ?: e::class.simpleName)
                }
            }
            while (isActive) {
                delay(tickMillis)
                tick()
            }
        } finally {
            sink.stop()
        }
    }

    private fun onEvent(e: MeshEvent) {
        composer.onEvent(e)
        scene.onEvent(e, if (e is MeshEvent.PacketHeard) dueFrameFor() else 0L)
        if (e is MeshEvent.Link) status = status.copy(link = e.state)
    }

    /** The composer takes a packet on its next tick and quantizes it to a quarter-second grid; so does this estimate. */
    private fun dueFrameFor(): Long {
        val earliest = rendered.load() + tickFrames
        val grid = sampleRate / 4L
        return -((-earliest).floorDiv(grid)) * grid
    }

    private fun tick() {
        val ms = now()
        val moment = localMoment(ms)
        val renderedNow = rendered.load()
        val out = composer.advance(renderedNow, lookaheadFrames, moment)
        synth.schedule(out)
        for (c in out.controls) if (c is ControlEvent.SetTexture) texture = c.texture
        scene.minuteOfDay = moment.minuteOfDay
        val day = Day.at(moment)
        val tSec = renderedNow.toDouble() / sampleRate
        status = status.copy(
            nodes = scene.orbs.size,
            key = NOTE_NAMES[day.rootSemitones.roundToInt().mod(12)],
            mode = day.mode.name,
            section = sectionLabel(tSec),
        )
    }

    private fun sectionLabel(tSec: Double): String {
        var end = 0.0
        var index = 0
        for (minutes in form.sectionMinutes(tSec)) {
            end += minutes * 60.0
            if (tSec < end) break
            index++
        }
        val energy = form.energy(tSec)
        val word = when {
            energy < 0.2 -> "near silence"
            energy < 0.5 -> "gathering"
            energy < 0.8 -> "full"
            else -> "peak"
        }
        return "${index + 1} - $word"
    }

    private companion object {
        const val GAIN_GLIDE = 0.002f
        val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    }
}
