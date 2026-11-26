@file:OptIn(ExperimentalForeignApi::class)

package org.meshtastic.ambient.audio

// UNVERIFIED: written against the AVFoundation Kotlin/Native bindings; not built or run (no Apple toolchain on Linux).

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.Volatile
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.setActive
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSThread
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import kotlinx.cinterop.autoreleasepool

private const val BLOCK_FRAMES = 1024
private const val BUFFERS_IN_FLIGHT = 12
private const val POOL_SIZE = BUFFERS_IN_FLIGHT + 1
private const val WAIT_SECONDS = 0.005

actual fun createAudioSink(): AudioSink = IosAudioSink()

class IosAudioSink : AudioSink {
    private val engine = AVAudioEngine()
    private val player = AVAudioPlayerNode()

    override val sampleRate: Int = engine.outputNode.outputFormatForBus(0u).sampleRate.toInt().takeIf { it > 0 } ?: 48_000

    @Volatile private var running = false
    private val outstanding = AtomicInt(0)
    private val playedFrames = AtomicLong(0L)
    private val observers = ArrayList<NSObjectProtocol>()

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
        outstanding.value = 0
        playedFrames.value = 0L
        observeRestarts()

        val pump = BlockPump(renderer, BLOCK_FRAMES)
        val interleaved = FloatArray(BLOCK_FRAMES * 2)
        // Pooled so the loop allocates nothing; one more than the in-flight cap so a free buffer always exists.
        val pool = Array(POOL_SIZE) {
            AVAudioPCMBuffer(pCMFormat = format, frameCapacity = BLOCK_FRAMES.toUInt()).also { it.frameLength = BLOCK_FRAMES.toUInt() }
        }
        NSThread.detachNewThreadWithBlock {
            NSThread.setThreadPriority(1.0)
            var next = 0
            while (running) {
                if (outstanding.value >= BUFFERS_IN_FLIGHT) {
                    NSThread.sleepForTimeInterval(WAIT_SECONDS)
                    continue
                }
                autoreleasepool {
                    pump.renderFloat(interleaved, BLOCK_FRAMES)
                    val buffer = pool[next]
                    next = (next + 1) % POOL_SIZE
                    val channels = buffer.floatChannelData!!
                    val left = channels[0]!!
                    val right = channels[1]!!
                    for (i in 0 until BLOCK_FRAMES) {
                        left[i] = interleaved[i * 2]
                        right[i] = interleaved[i * 2 + 1]
                    }
                    outstanding.incrementAndGet()
                    player.scheduleBuffer(buffer, completionHandler = {
                        playedFrames.addAndGet(BLOCK_FRAMES.toLong())
                        outstanding.decrementAndGet()
                    })
                }
            }
        }
    }

    /** Interruptions (calls, Siri), route changes and engine reconfiguration stop the engine; bring it back. */
    private fun observeRestarts() {
        val center = NSNotificationCenter.defaultCenter
        for (name in listOf(
            AVAudioSessionInterruptionNotification,
            AVAudioSessionRouteChangeNotification,
            AVAudioEngineConfigurationChangeNotification,
        )) {
            observers.add(center.addObserverForName(name, null, null) { _ -> restartEngine() })
        }
    }

    private fun restartEngine() {
        if (!running) return
        AVAudioSession.sharedInstance().setActive(true, null)
        if (!engine.running) engine.startAndReturnError(null)
        if (!player.playing) player.play()
    }

    override fun stop() {
        running = false
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers.clear()
        player.stop()
        engine.stop()
        engine.detachNode(player)
    }

    /** Frames whose buffers have finished playing; a coarse 1024-frame clock, never ahead of the speaker. */
    override fun playbackFrame(): Long = playedFrames.value
}
