package org.meshtastic.aether.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Environment variable that names a WAV file the desktop app records its output to, for demo videos. */
const val RECORD_AUDIO_ENV = "AETHER_RECORD_AUDIO"

private const val QUEUE_BLOCKS = 256
private const val FLUSH_MILLIS = 1000L
private const val POLL_MILLIS = 100L
private const val CLOSE_TIMEOUT_MILLIS = 2000L

// The canonical 44-byte RIFF/WAVE header for 16-bit stereo PCM.
private const val HEADER_BYTES = 44
private const val RIFF_SIZE_HEADER_PART = 36 // the header bytes after the RIFF size field
private const val FMT_CHUNK_BYTES = 16
private const val PCM_FORMAT: Short = 1
private const val CHANNELS: Short = 2
private const val BYTES_PER_FRAME: Short = 4
private const val BITS_PER_SAMPLE: Short = 16

/**
 * A RIFF size is 32 bits; past this (about 3 hours at 48 kHz) the header stays at the limit and the samples keep
 * coming.
 */
private const val MAX_DATA_BYTES = Int.MAX_VALUE.toLong() - RIFF_SIZE_HEADER_PART

/**
 * Copies the 16-bit stereo blocks the sink plays into a WAV file on a writer thread, so the audio thread only
 * enqueues. A full queue drops the block. The header is rewritten every second, so a killed app leaves a playable
 * file, and [startMillis] is the wall clock at the first block, written to `<file>.start` to line the audio up
 * with a screen recording.
 */
class PcmRecorder(private val file: File, private val sampleRate: Int) {
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_BLOCKS)

    @Volatile private var open = true

    /** False once the writer has failed (an unwritable path); [offer] then stops copying. */
    @Volatile private var writing = true
    private val writer = Thread(::drain, "aether-record").apply { isDaemon = true }

    /** Epoch millis of the first block, or 0 before it. */
    @Volatile var startMillis = 0L
        private set

    fun start() = writer.start()

    /** Called from the audio thread with the block it is about to play. */
    fun offer(bytes: ByteArray, length: Int) {
        if (!writing) return
        if (startMillis == 0L) startMillis = System.currentTimeMillis()
        queue.offer(bytes.copyOf(length))
    }

    fun close() {
        open = false
        writer.join(CLOSE_TIMEOUT_MILLIS)
    }

    private fun drain() {
        try {
            write()
        } catch (e: IOException) {
            stopWriting(e)
        } catch (e: InterruptedException) {
            stopWriting(e)
        }
    }

    private fun stopWriting(cause: Exception) {
        writing = false
        System.err.println("aether: recording to $file stopped: $cause")
    }

    private fun write() {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            f.write(header(0))
            var data = 0L
            var flushedAt = System.currentTimeMillis()
            while (open || queue.isNotEmpty()) {
                val block = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
                if (block != null) data = append(f, block, data)
                val now = System.currentTimeMillis()
                if (now - flushedAt >= FLUSH_MILLIS || (!open && queue.isEmpty())) {
                    flushedAt = now
                    rewriteHeader(f, data)
                }
            }
        }
    }

    /** Appends [block] after [data] bytes and returns the new total; the first block also writes `<file>.start`. */
    private fun append(f: RandomAccessFile, block: ByteArray, data: Long): Long {
        f.write(block)
        val total = data + block.size
        if (total == block.size.toLong()) File(file.path + ".start").writeText(startMillis.toString())
        return total
    }

    private fun rewriteHeader(f: RandomAccessFile, data: Long) {
        f.seek(0)
        f.write(header(data))
        f.seek(HEADER_BYTES + data)
    }

    private fun header(dataBytes: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(HEADER_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val data = dataBytes.coerceAtMost(MAX_DATA_BYTES)
        b.put("RIFF".toByteArray()).putInt((RIFF_SIZE_HEADER_PART + data).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(FMT_CHUNK_BYTES).putShort(PCM_FORMAT).putShort(CHANNELS)
        b.putInt(sampleRate).putInt(sampleRate * BYTES_PER_FRAME)
        b.putShort(BYTES_PER_FRAME).putShort(BITS_PER_SAMPLE).put("data".toByteArray()).putInt(data.toInt())
        return b.array()
    }
}

/**
 * The process's one recorder, made on the first sink start when [RECORD_AUDIO_ENV] is set. It outlives sessions, so a
 * reconnect continues the same file instead of truncating it; its header is current to the second, so quitting is
 * safe.
 */
internal object ProcessRecorder {
    private var recorder: PcmRecorder? = null
    private var made = false

    @Synchronized
    fun get(sampleRate: Int): PcmRecorder? {
        if (!made) {
            made = true
            recorder = System.getenv(RECORD_AUDIO_ENV)
                ?.takeIf { it.isNotBlank() }
                ?.let { PcmRecorder(File(it), sampleRate).also(PcmRecorder::start) }
        }
        return recorder
    }
}
