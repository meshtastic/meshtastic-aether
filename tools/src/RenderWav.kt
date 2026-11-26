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
    Silent(0, 1.0),
    Sparse(6, 1.0),
    Busy(150, 30.0),
}

sealed interface Command {
    data class Render(val density: Density, val hours: Double, val out: String, val seed: Long) : Command

    data class Analyze(val wav: String) : Command
}

const val USAGE = "usage: RenderWav <silent|sparse|busy> <hours> <outPath> [seed]  |  RenderWav analyze <wav>"

fun parseArgs(args: List<String>): Command {
    require(args.isNotEmpty()) { USAGE }
    if (args[0] == "analyze") {
        require(args.size == 2) { USAGE }
        return Command.Analyze(args[1])
    }
    require(args.size in 3..4) { USAGE }
    val density = Density.entries.firstOrNull { it.name.equals(args[0], ignoreCase = true) }
        ?: throw IllegalArgumentException("unknown density '${args[0]}'; $USAGE")
    val hours = args[1].toDoubleOrNull()
    require(hours != null && hours > 0.0 && hours.isFinite()) { "hours must be a positive number: '${args[1]}'" }
    val seed = if (args.size == 4) {
        args[3].toLongOrNull() ?: throw IllegalArgumentException("seed must be an integer: '${args[3]}'")
    } else {
        1L
    }
    return Command.Render(density, hours, args[2], seed)
}

private const val SAMPLE_RATE = 48_000
private const val BLOCK = 512
private const val LOOKAHEAD = 12_000L
private const val START_EPOCH_MS = 1_795_636_800_000L // 2026-11-25T20:00:00Z, a Wednesday evening

internal fun momentAt(epochMs: Long): LocalMoment {
    val days = Math.floorDiv(epochMs, 86_400_000L)
    val inDay = Math.floorMod(epochMs, 86_400_000L)
    return LocalMoment(
        dayOfWeek = Math.floorMod(days + 3, 7L).toInt() + 1,
        minuteOfDay = (inDay / 60_000L).toInt(),
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

fun render(cmd: Command.Render): RenderSummary {
    val frames = (cmd.hours * 3600.0 * SAMPLE_RATE).toLong()
    val events = generateEvents(cmd.density, cmd.seed, (cmd.hours * 3_600_000.0).toLong())
    val composer = Composer(cmd.seed, SAMPLE_RATE)
    val synth = Synth(SAMPLE_RATE)
    val header = wavBytes(FloatArray(0), SAMPLE_RATE)
    val buf = FloatArray(BLOCK * 2)
    var next = 0
    var notes = 0L
    var nonFinite = 0L
    var clipped = 0L
    RandomAccessFile(cmd.out, "rw").use { raf ->
        raf.setLength(0)
        raf.write(header)
        val chunk = FloatArray(SAMPLE_RATE * 2)
        var chunkFill = 0
        var done = 0L
        while (done < frames) {
            val n = minOf(BLOCK.toLong(), frames - done).toInt()
            val epoch = START_EPOCH_MS + done * 1000L / SAMPLE_RATE
            while (next < events.size && events[next].atMillis <= epoch) composer.onEvent(events[next++])
            val out = composer.advance(done, LOOKAHEAD, momentAt(epoch))
            notes += out.notes.size
            synth.schedule(out)
            synth.render(buf, n)
            for (i in 0 until n * 2) {
                val x = buf[i]
                if (x != x || x.isInfinite()) nonFinite++ else if (kotlin.math.abs(x) >= CLIP_LEVEL) clipped++
                chunk[chunkFill++] = x
                if (chunkFill == chunk.size) {
                    raf.write(wavBytes(chunk, SAMPLE_RATE), 44, chunk.size * 2)
                    chunkFill = 0
                }
            }
            done += n
        }
        if (chunkFill > 0) raf.write(wavBytes(chunk.copyOf(chunkFill), SAMPLE_RATE), 44, chunkFill * 2)
        val dataBytes = frames * 4
        raf.seek(4); raf.write(le32(36 + dataBytes))
        raf.seek(40); raf.write(le32(dataBytes))
    }
    return RenderSummary(frames, events.size, notes, nonFinite, clipped)
}

private fun le32(v: Long): ByteArray = ByteArray(4) { (v ushr (8 * it)).toByte() }

/** Reads a 16-bit stereo PCM WAV written by [wavBytes] and returns per-window stats. */
fun analyzeWav(path: String, windowSec: Int = 300): List<WindowStats> {
    BufferedInputStream(File(path).inputStream(), 1 shl 20).use { ins ->
        val head = ins.readNBytes(44)
        require(head.size == 44 && String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE") { "not a WAV: $path" }
        fun u16(o: Int) = (head[o].toInt() and 0xFF) or ((head[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
        require(u16(20) == 1 && u16(22) == 2 && u16(34) == 16) { "expected 16-bit stereo PCM" }
        val rate = u32(24).toInt()
        val an = WindowAnalyzer(rate, windowSec.toLong() * rate)
        val bytes = ByteArray(rate * 4)
        val floats = FloatArray(rate * 2)
        while (true) {
            val got = ins.readNBytes(bytes, 0, bytes.size) / 4
            if (got == 0) break
            for (i in 0 until got * 2) {
                val v = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
                floats[i] = v / 32767f
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
            val secs = (System.nanoTime() - t0) / 1e9
            println(
                "rendered ${cmd.density} ${s.frames / SAMPLE_RATE} s audio in ${"%.1f".format(secs)} s: " +
                    "events=${s.events} notes=${s.notes} nonFinite=${s.nonFinite} clipped=${s.clipped} -> ${cmd.out}",
            )
        }
        is Command.Analyze -> print(formatStats(analyzeWav(cmd.wav)))
    }
}
