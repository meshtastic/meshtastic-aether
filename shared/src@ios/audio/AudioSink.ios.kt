@file:OptIn(ExperimentalForeignApi::class)

package org.meshtastic.ambient.audio

// UNVERIFIED: written against the AVFoundation Kotlin/Native bindings; not built on Linux.

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlin.concurrent.Volatile
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSThread
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

private const val BUFFERS_IN_FLIGHT = 6L

actual fun createAudioSink(): AudioSink = IosAudioSink()

class IosAudioSink : AudioSink {
    private val engine = AVAudioEngine()
    private val player = AVAudioPlayerNode()

    override val sampleRate: Int = engine.outputNode.outputFormatForBus(0u).sampleRate.toInt().takeIf { it > 0 } ?: 48_000

    @Volatile private var running = false

    override fun start(renderer: BlockRenderer) {
        if (running) return
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, null)
        session.setActive(true, null)
        val format = AVAudioFormat(
            commonFormat = platform.AVFAudio.AVAudioPCMFormatFloat32,
            sampleRate = sampleRate.toDouble(),
            channels = 2u,
            interleaved = false,
        )
        engine.attachNode(player)
        engine.connect(player, engine.mainMixerNode, format)
        engine.startAndReturnError(null)
        player.play()
        running = true
        val pump = BlockPump(renderer)
        val interleaved = FloatArray(PUMP_BLOCK_FRAMES * 2)
        val slots = dispatch_semaphore_create(BUFFERS_IN_FLIGHT)
        NSThread.detachNewThreadWithBlock {
            while (running) {
                pump.renderFloat(interleaved, PUMP_BLOCK_FRAMES)
                val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = PUMP_BLOCK_FRAMES.toUInt())
                buffer.frameLength = PUMP_BLOCK_FRAMES.toUInt()
                val channels = buffer.floatChannelData!!
                val left = channels[0]!!
                val right = channels[1]!!
                for (i in 0 until PUMP_BLOCK_FRAMES) {
                    left[i] = interleaved[i * 2]
                    right[i] = interleaved[i * 2 + 1]
                }
                dispatch_semaphore_wait(slots, DISPATCH_TIME_FOREVER)
                if (!running) break
                player.scheduleBuffer(buffer, completionHandler = { dispatch_semaphore_signal(slots) })
            }
        }
    }

    override fun stop() {
        running = false
        player.stop()
        engine.stop()
        engine.detachNode(player)
    }

    override fun playbackFrame(): Long {
        val nodeTime = player.lastRenderTime ?: return 0L
        val playerTime = player.playerTimeForNodeTime(nodeTime) ?: return 0L
        return playerTime.sampleTime.coerceAtLeast(0L)
    }
}
