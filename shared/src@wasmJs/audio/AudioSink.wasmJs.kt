@file:OptIn(ExperimentalWasmJsInterop::class)

package org.meshtastic.ambient.audio

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

private const val CHUNK_FRAMES = 4096
private const val AHEAD_SECONDS = 1.0
private const val TICK_MS = 100

external class AudioContext : JsAny {
    val currentTime: Double
    val sampleRate: Double
    val destination: JsAny
    fun createBuffer(channels: Int, length: Int, sampleRate: Double): AudioBuffer
    fun createBufferSource(): AudioBufferSourceNode
    fun resume(): JsAny
    fun close(): JsAny
}

external class AudioBuffer : JsAny {
    fun getChannelData(channel: Int): JsAny
}

external class AudioBufferSourceNode : JsAny {
    var buffer: AudioBuffer?
    fun connect(destination: JsAny): JsAny
    fun start(whenSeconds: Double)
}

@JsFun("() => new (globalThis.AudioContext || globalThis.webkitAudioContext)()")
private external fun newAudioContext(): AudioContext

@JsFun("(data, i, v) => { data[i] = v; }")
private external fun setSample(data: JsAny, i: Int, v: Float)

@JsFun("(f, ms) => setInterval(f, ms)")
private external fun setIntervalJs(f: () -> Unit, ms: Int): Int

@JsFun("(id) => clearInterval(id)")
private external fun clearIntervalJs(id: Int)

private var current: WebAudioSink? = null

/** Browsers keep the context suspended until a gesture; call this from a click or key handler. */
fun resumeAudioFromGesture() {
    current?.resumeFromGesture()
}

actual fun createAudioSink(): AudioSink = WebAudioSink().also { current = it }

class WebAudioSink : AudioSink {
    private val ctx: AudioContext = newAudioContext()
    override val sampleRate: Int = ctx.sampleRate.toInt()

    private var timer = -1
    private var pump: BlockPump? = null
    private val floats = FloatArray(CHUNK_FRAMES * 2)
    private var nextStart = 0.0
    private var originTime = 0.0

    fun resumeFromGesture() {
        ctx.resume()
    }

    override fun start(renderer: BlockRenderer) {
        if (timer >= 0) return
        pump = BlockPump(renderer)
        originTime = ctx.currentTime + 0.05
        nextStart = originTime
        timer = setIntervalJs({ fill() }, TICK_MS)
        fill()
    }

    private fun fill() {
        val p = pump ?: return
        while (nextStart < ctx.currentTime + AHEAD_SECONDS) {
            p.renderFloat(floats, CHUNK_FRAMES)
            val buf = ctx.createBuffer(2, CHUNK_FRAMES, sampleRate.toDouble())
            val left = buf.getChannelData(0)
            val right = buf.getChannelData(1)
            for (i in 0 until CHUNK_FRAMES) {
                setSample(left, i, floats[i * 2])
                setSample(right, i, floats[i * 2 + 1])
            }
            val src = ctx.createBufferSource()
            src.buffer = buf
            src.connect(ctx.destination)
            src.start(nextStart)
            nextStart += CHUNK_FRAMES.toDouble() / sampleRate
        }
    }

    override fun stop() {
        if (timer >= 0) clearIntervalJs(timer)
        timer = -1
        pump = null
        ctx.close()
        if (current === this) current = null
    }

    override fun playbackFrame(): Long {
        val played = ((ctx.currentTime - originTime) * sampleRate).toLong()
        return if (timer < 0 || played < 0) 0L else played
    }
}
