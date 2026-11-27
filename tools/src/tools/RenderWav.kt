package org.meshtastic.aether.tools

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.mesh.SimulatedMeshSource
import org.meshtastic.aether.music.Composer
import org.meshtastic.aether.music.LocalMoment
import org.meshtastic.aether.synth.Synth
import org.meshtastic.aether.synth.wavBytes
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.system.exitProcess

enum class Density(val nodes: Int, val speed: Double) {
    Silent(nodes = 0, speed = 1.0),
    Sparse(nodes = 6, speed = 1.0),
    Busy(nodes = 150, speed = 30.0),
}

sealed interface Command {
    data class Render(val density: Density, val hours: Double, val out: String, val seed: Long) : Command

    data class Analyze(val wav: String) : Command
}

const val USAGE = "usage: RenderWav <silent|sparse|busy> <hours> <outPath> [seed]  |  RenderWav analyze <wav>"

/** A render takes density, hours and the output path; an optional seed follows them. */
private const val RENDER_ARGS = 3
private const val DEFAULT_SEED = 1L

fun parseArgs(args: List<String>): Command {
    require(args.isNotEmpty()) { USAGE }
    if (args[0] == "analyze") {
        require(args.size == 2) { USAGE }
        return Command.Analyze(args[1])
    }
    require(args.size in RENDER_ARGS..RENDER_ARGS + 1) { USAGE }
    val density = Density.entries.firstOrNull { it.name.equals(args[0], ignoreCase = true) }
        ?: throw IllegalArgumentException("unknown density '${args[0]}'; $USAGE")
    val hours = args[1].toDoubleOrNull()
    require(hours != null && hours > 0.0 && hours.isFinite()) { "hours must be a positive number: '${args[1]}'" }
    val seedArg = args.getOrNull(RENDER_ARGS)
    val seed = if (seedArg != null) {
        seedArg.toLongOrNull() ?: throw IllegalArgumentException("seed must be an integer: '$seedArg'")
    } else {
        DEFAULT_SEED
    }
    return Command.Render(density, hours, args[2], seed)
}

private const val SAMPLE_RATE = 48_000
private const val STEREO = 2
private const val BLOCK = 512
private const val LOOKAHEAD = 12_000L
private const val START_EPOCH_MS = 1_795_636_800_000L // 2026-11-25T20:00:00Z, a Wednesday evening

private const val MILLIS_PER_SECOND = 1000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_DAY = 86_400_000L
private const val SECONDS_PER_HOUR = 3600.0
private const val MILLIS_PER_HOUR = 3_600_000.0
private const val NANOS_PER_SECOND = 1e9
private const val DAYS_PER_WEEK = 7L

/** Day 0 of the epoch was a Thursday, three days past Monday in the Monday = 1 week. */
private const val EPOCH_DAYS_PAST_MONDAY = 3L

internal fun momentAt(epochMs: Long): LocalMoment {
    val days = Math.floorDiv(epochMs, MILLIS_PER_DAY)
    val inDay = Math.floorMod(epochMs, MILLIS_PER_DAY)
    return LocalMoment(
        dayOfWeek = Math.floorMod(days + EPOCH_DAYS_PAST_MONDAY, DAYS_PER_WEEK).toInt() + 1,
        minuteOfDay = (inDay / MILLIS_PER_MINUTE).toInt(),
        epochMillis = epochMs,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun generateEvents(density: Density, seed: Long, durationMs: Long): List<MeshEvent> {
    if (density.nodes == 0) return emptyList()
    val out = ArrayList<MeshEvent>()
    runTest(timeout = kotlin.time.Duration.INFINITE) {
        val src = SimulatedMeshSource(seed, density.nodes, density.speed) { START_EPOCH_MS + currentTime }
        val flow: Flow<MeshEvent> = src.events
        withTimeoutOrNull(durationMs) { flow.collect { out += it } }
    }
    return out
}

class RenderSummary(val frames: Long, val events: Int, val notes: Long, val nonFinite: Long, val clipped: Long)

// The canonical 44-byte RIFF/WAVE header that wavBytes writes: 16-bit little-endian stereo PCM.
private const val WAV_HEADER_BYTES = 44
private const val RIFF_SIZE_OFFSET = 4L
private const val WAVE_TAG_OFFSET = 8
private const val TAG_LENGTH = 4
private const val FORMAT_OFFSET = 20
private const val CHANNELS_OFFSET = 22
private const val SAMPLE_RATE_OFFSET = 24
private const val BITS_OFFSET = 34
private const val DATA_SIZE_OFFSET = 40L

/** The RIFF size field counts the header after its own first 8 bytes, plus the data. */
private const val RIFF_SIZE_HEADER_PART = 36
private const val PCM_FORMAT = 1
private const val BITS_PER_SAMPLE = 16
private const val BYTES_PER_SAMPLE = 2
private const val BYTES_PER_FRAME = BYTES_PER_SAMPLE * STEREO
private const val PCM16_FULL_SCALE = 32767f
private const val READ_BUFFER_BYTES = 1 shl 20
private const val DEFAULT_WINDOW_SECONDS = 300

/** Counts the samples a render must never produce: non-finite ones, and finite ones at or past [CLIP_LEVEL]. */
private class SampleHealth {
    var nonFinite = 0L
    var clipped = 0L

    fun check(x: Float) {
        if (x.isNaN() || x.isInfinite()) nonFinite++ else if (kotlin.math.abs(x) >= CLIP_LEVEL) clipped++
    }
}

/** Appends samples to the WAV data chunk, converting and writing one second of audio at a time. */
private class PcmChunkWriter(private val raf: RandomAccessFile) {
    private val chunk = FloatArray(SAMPLE_RATE * STEREO)
    private var fill = 0

    fun add(x: Float) {
        chunk[fill++] = x
        if (fill == chunk.size) {
            raf.write(wavBytes(chunk, SAMPLE_RATE), WAV_HEADER_BYTES, chunk.size * BYTES_PER_SAMPLE)
            fill = 0
        }
    }

    fun flush() {
        if (fill > 0) raf.write(wavBytes(chunk.copyOf(fill), SAMPLE_RATE), WAV_HEADER_BYTES, fill * BYTES_PER_SAMPLE)
    }
}

fun render(cmd: Command.Render): RenderSummary {
    val frames = (cmd.hours * SECONDS_PER_HOUR * SAMPLE_RATE).toLong()
    val events = generateEvents(cmd.density, cmd.seed, (cmd.hours * MILLIS_PER_HOUR).toLong())
    val composer = Composer(cmd.seed, SAMPLE_RATE)
    val synth = Synth(SAMPLE_RATE)
    val header = wavBytes(FloatArray(0), SAMPLE_RATE)
    val buf = FloatArray(BLOCK * STEREO)
    var next = 0
    var notes = 0L
    val health = SampleHealth()
    RandomAccessFile(cmd.out, "rw").use { raf ->
        raf.setLength(0)
        raf.write(header)
        val writer = PcmChunkWriter(raf)
        var done = 0L
        while (done < frames) {
            val n = minOf(BLOCK.toLong(), frames - done).toInt()
            val epoch = START_EPOCH_MS + done * MILLIS_PER_SECOND / SAMPLE_RATE
            while (next < events.size && events[next].atMillis <= epoch) composer.onEvent(events[next++])
            val out = composer.advance(done, LOOKAHEAD, momentAt(epoch))
            notes += out.notes.size
            synth.schedule(out)
            synth.render(buf, n)
            for (i in 0 until n * STEREO) {
                health.check(buf[i])
                writer.add(buf[i])
            }
            done += n
        }
        writer.flush()
        val dataBytes = frames * BYTES_PER_FRAME
        raf.seek(RIFF_SIZE_OFFSET)
        raf.write(le32(RIFF_SIZE_HEADER_PART + dataBytes))
        raf.seek(DATA_SIZE_OFFSET)
        raf.write(le32(dataBytes))
    }
    return RenderSummary(frames, events.size, notes, health.nonFinite, health.clipped)
}

private fun le32(v: Long): ByteArray = ByteArray(Int.SIZE_BYTES) { (v ushr (Byte.SIZE_BITS * it)).toByte() }

private fun ByteArray.u8(offset: Int): Int = this[offset].toUByte().toInt()

/** Reads a 16-bit stereo PCM WAV written by [wavBytes] and returns per-window stats. */
fun analyzeWav(path: String, windowSec: Int = DEFAULT_WINDOW_SECONDS): List<WindowStats> {
    BufferedInputStream(File(path).inputStream(), READ_BUFFER_BYTES).use { ins ->
        val head = ins.readNBytes(WAV_HEADER_BYTES)
        require(
            head.size == WAV_HEADER_BYTES &&
                String(head, 0, TAG_LENGTH) == "RIFF" &&
                String(head, WAVE_TAG_OFFSET, TAG_LENGTH) == "WAVE",
        ) { "not a WAV: $path" }
        fun u16(o: Int) = head.u8(o) or (head.u8(o + 1) shl Byte.SIZE_BITS)
        fun u32(o: Int) = u16(o).toLong() or (u16(o + 2).toLong() shl Short.SIZE_BITS)
        require(
            u16(FORMAT_OFFSET) == PCM_FORMAT && u16(CHANNELS_OFFSET) == STEREO && u16(BITS_OFFSET) == BITS_PER_SAMPLE,
        ) { "expected 16-bit stereo PCM" }
        val rate = u32(SAMPLE_RATE_OFFSET).toInt()
        val an = WindowAnalyzer(rate, windowSec.toLong() * rate)
        val bytes = ByteArray(rate * BYTES_PER_FRAME)
        val floats = FloatArray(rate * STEREO)
        while (true) {
            val got = ins.readNBytes(bytes, 0, bytes.size) / BYTES_PER_FRAME
            if (got == 0) break
            for (i in 0 until got * STEREO) {
                val lo = bytes.u8(BYTES_PER_SAMPLE * i)
                val hi = bytes[BYTES_PER_SAMPLE * i + 1].toInt() // signed: carries the sample's sign
                floats[i] = (lo or (hi shl Byte.SIZE_BITS)) / PCM16_FULL_SCALE
            }
            an.push(floats, got)
        }
        return an.finish()
    }
}

fun main(args: Array<String>) {
    val cmd = try {
        parseArgs(args.toList())
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
    }
    when (cmd) {
        is Command.Render -> {
            val t0 = System.nanoTime()
            val s = render(cmd)
            val secs = (System.nanoTime() - t0) / NANOS_PER_SECOND
            println(
                "rendered ${cmd.density} ${s.frames / SAMPLE_RATE} s audio in ${"%.1f".format(secs)} s: " +
                    "events=${s.events} notes=${s.notes} nonFinite=${s.nonFinite} clipped=${s.clipped} -> ${cmd.out}",
            )
        }
        is Command.Analyze -> print(formatStats(analyzeWav(cmd.wav)))
    }
}
