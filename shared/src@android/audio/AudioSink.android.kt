package org.meshtastic.ambient.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

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
            while (running) {
                pump.renderFloat(buf, PUMP_BLOCK_FRAMES)
                t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
        }, "ambient-audio").apply { isDaemon = true; start() }
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
