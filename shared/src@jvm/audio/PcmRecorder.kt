package org.meshtastic.aether.audio

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Environment variable that names a WAV file the desktop app records its output to, for demo videos. */
const val RECORD_AUDIO_ENV = "AETHER_RECORD_AUDIO"

private const val HEADER_BYTES = 44
private const val QUEUE_BLOCKS = 256
private const val FLUSH_MILLIS = 1000L

/**
 * Copies the 16-bit stereo blocks the sink plays into a WAV file on a writer thread, so the audio thread only
 * enqueues. A full queue drops the block. The header is rewritten every second, so a killed app leaves a playable
 * file, and [startMillis] is the wall clock at the first block, written to `<file>.start` to line the audio up
 * with a screen recording.
 */
class PcmRecorder(private val file: File, private val sampleRate: Int) {
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_BLOCKS)

    @Volatile private var open = true
    private val writer = Thread(::drain, "aether-record").apply { isDaemon = true }

    /** Epoch millis of the first block, or 0 before it. */
    @Volatile var startMillis = 0L
        private set

    fun start() = writer.start()

    /** Called from the audio thread with the block it is about to play. */
    fun offer(bytes: ByteArray, length: Int) {
        if (startMillis == 0L) startMillis = System.currentTimeMillis()
        queue.offer(bytes.copyOf(length))
    }

    fun close() {
        open = false
        writer.join(2000)
    }

    private fun drain() {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            f.write(header(0))
            var data = 0L
            var flushedAt = System.currentTimeMillis()
            while (open || queue.isNotEmpty()) {
                val block = queue.poll(100, TimeUnit.MILLISECONDS)
                if (block != null) {
                    f.write(block)
                    data += block.size
                    if (data == block.size.toLong()) File(file.path + ".start").writeText(startMillis.toString())
                }
                val now = System.currentTimeMillis()
                if (now - flushedAt >= FLUSH_MILLIS || (!open && queue.isEmpty())) {
                    flushedAt = now
                    f.seek(0)
                    f.write(header(data))
                    f.seek(HEADER_BYTES + data)
                }
            }
        }
    }

    private fun header(dataBytes: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(HEADER_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sampleRate).putInt(sampleRate * 4)
        b.putShort(4).putShort(16).put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }
}

internal fun recorderFromEnvironment(sampleRate: Int): PcmRecorder? =
    System.getenv(RECORD_AUDIO_ENV)?.takeIf { it.isNotBlank() }?.let { PcmRecorder(File(it), sampleRate).also(PcmRecorder::start) }
