package org.meshtastic.aether.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process

private const val LOOKAHEAD_MS = 250

actual fun createAudioSink(): AudioSink = AndroidAudioSink()

class AndroidAudioSink : AudioSink {
    override val sampleRate: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48_000

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val counter = WrapSafeFrameCounter()

    @Synchronized
    override fun start(renderer: BlockRenderer) {
        if (running) return
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val wantBytes = sampleRate * LOOKAHEAD_MS / 1000 * 2 * 4
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBytes, wantBytes))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        running = true
        t.play()
        val pump = BlockPump(renderer)
        val buf = FloatArray(PUMP_BLOCK_FRAMES * 2)
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (running) {
                pump.renderFloat(buf, PUMP_BLOCK_FRAMES)
                var offset = 0
                while (running && offset < buf.size) {
                    // A negative result is an AudioTrack error (dead object, invalid operation): stop the pump.
                    offset = advanceWrite(offset, buf.size, t.write(buf, offset, buf.size - offset, AudioTrack.WRITE_BLOCKING))
                    if (offset == WRITE_FAILED) {
                        running = false
                        return@Thread
                    }
                }
            }
        }, "aether-audio").apply { isDaemon = true; start() }
    }

    @Synchronized
    override fun stop() {
        running = false
        val t = track ?: return
        t.pause()
        t.flush()
        thread?.join(1000)
        thread = null
        t.release()
        track = null
    }

    @Synchronized
    override fun playbackFrame(): Long = track?.let { counter.update(it.playbackHeadPosition) } ?: 0L
}
