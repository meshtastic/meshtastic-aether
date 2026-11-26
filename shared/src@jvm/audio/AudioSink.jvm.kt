package org.meshtastic.aether.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

private const val LOOKAHEAD_MS = 250
private val PREFERRED_RATES = intArrayOf(48_000, 44_100, 96_000, 32_000, 22_050)

/** First candidate the device accepts, else [fallback]; the rate is asked of the mixer, never assumed. */
internal fun negotiateSampleRate(candidates: IntArray, fallback: Int, supports: (Int) -> Boolean): Int =
    candidates.firstOrNull(supports) ?: fallback

private fun format(rate: Int) = AudioFormat(rate.toFloat(), 16, 2, true, false)

actual fun createAudioSink(): AudioSink = JvmAudioSink()

class JvmAudioSink : AudioSink {
    override val sampleRate: Int = negotiateSampleRate(PREFERRED_RATES, 48_000) {
        AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, format(it)))
    }

    @Volatile private var line: SourceDataLine? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private var recorder: PcmRecorder? = null

    @Synchronized
    override fun start(renderer: BlockRenderer) {
        if (running) return
        val fmt = format(sampleRate)
        val l = AudioSystem.getSourceDataLine(fmt)
        l.open(fmt, sampleRate * LOOKAHEAD_MS / 1000 * fmt.frameSize)
        l.start()
        line = l
        running = true
        val pump = BlockPump(renderer)
        val rec = recorderFromEnvironment(sampleRate)
        recorder = rec
        val bytes = ByteArray(PUMP_BLOCK_FRAMES * fmt.frameSize)
        thread = Thread({
            while (running) {
                pump.renderPcm16Bytes(bytes, PUMP_BLOCK_FRAMES)
                rec?.offer(bytes, bytes.size)
                var offset = 0
                while (running && offset < bytes.size) {
                    offset = advanceWrite(offset, bytes.size, l.write(bytes, offset, bytes.size - offset))
                    if (offset == WRITE_FAILED) {
                        running = false
                        return@Thread
                    }
                }
            }
        }, "aether-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
    }

    @Synchronized
    override fun stop() {
        running = false
        val l = line ?: return
        l.stop()
        l.flush()
        thread?.join(1000)
        thread = null
        l.close()
        line = null
        recorder?.close()
        recorder = null
    }

    override fun playbackFrame(): Long = line?.longFramePosition ?: 0L
}
