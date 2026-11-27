package org.meshtastic.aether.tools

import org.meshtastic.aether.audio.BlockRenderer
import org.meshtastic.aether.theme.PaletteChoice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineSinkTest {
    // The scene and the composer read the sink's clock, so a picture lines up with its audio only if the clock is
    // exactly the frames pulled.
    @Test
    fun `the offline clock is the frames pulled and nothing plays before start`() {
        val sink = OfflineSink(48_000)
        val out = FloatArray(200) { 9f }
        assertFalse(sink.pull(out, 100))
        assertEquals(0L, sink.playbackFrame())
        assertTrue(out.all { it == 9f })
        sink.start(BlockRenderer { buf, frames -> for (i in 0 until frames * 2) buf[i] = 0.5f })
        assertTrue(sink.pull(out, 100))
        assertTrue(sink.pull(out, 60))
        assertEquals(160L, sink.playbackFrame())
        assertEquals(0.5f, out[0])
        sink.stop()
        assertFalse(sink.pull(out, 100))
        assertEquals(160L, sink.playbackFrame())
    }
}

class RenderFramesTest {
    private class Run(val audio: FloatArray, val pictures: List<ByteArray>, val shownAt: List<Long>, val clock: RenderClock)

    private fun render(spec: VideoSpec): Run {
        val audio = ArrayList<Float>()
        val pictures = ArrayList<ByteArray>()
        val shownAt = ArrayList<Long>()
        val clock = renderFrames(spec, onAudio = { block, n -> for (i in 0 until n * 2) audio += block[i] }) { bgra, index, at ->
            assertEquals(pictures.size, index)
            pictures += bgra.copyOf()
            shownAt += at
        }
        return Run(audio.toFloatArray(), pictures, shownAt, clock)
    }

    private val spec = VideoSpec(seconds = 1.0, fps = 30, width = 64, height = 43, warmupSeconds = 0.5)

    // Picture k must show audio frame (warm-up + k) times the frames per picture, before that picture's block plays:
    // drawn after the pull, every ripple led its note by one picture. The engine's virtual clock must sit on the audio
    // clock exactly, including the 33.3 ms picture period at 30 fps.
    @Test
    fun `each picture shows its own audio frame and the virtual clock sits on the audio clock`() {
        val run = render(spec)
        val perPicture = 48_000 / 30
        assertEquals(30, run.pictures.size)
        assertEquals(List(30) { (15L + it) * perPicture }, run.shownAt)
        assertEquals(45L * perPicture, run.clock.audioFrames)
        assertEquals(45L * perPicture * 2, run.audio.size.toLong())
        // The last block plays after the last picture, so the clock stands on that picture's frame.
        assertEquals(run.shownAt.last() * 1000 / 48_000, run.clock.virtualMillis)
        assertTrue(run.pictures.all { it.size == 64 * 43 * 4 })
        assertTrue(run.pictures.zipWithNext().count { (a, b) -> !a.contentEquals(b) } >= 25, "the scene stood still")
    }

    // A render runs only on its virtual clock and seed: any real clock or dispatcher left in the engine makes two runs
    // of one spec differ.
    @Test
    fun `two renders of one spec are identical in sound and picture`() {
        val a = render(spec)
        val b = render(spec)
        assertContentEquals(a.audio, b.audio)
        assertEquals(a.pictures.size, b.pictures.size)
        a.pictures.indices.forEach { assertContentEquals(a.pictures[it], b.pictures[it], "picture $it") }
    }
}

class WavStreamTest {
    @Test
    fun `a WAV written in blocks with a partial last block equals the WAV of all the samples at once`() {
        val first = FloatArray(8) { it / 10f - 0.4f }
        val second = FloatArray(8) { 0.3f - it / 20f }
        val file = kotlin.io.path.createTempFile("wavstream", ".wav").toFile()
        try {
            WavStream(file, 48_000).use {
                it.write(first, 4)
                it.write(second, 3)
            }
            assertContentEquals(org.meshtastic.aether.synth.wavBytes(first + second.copyOf(6), 48_000), file.readBytes())
        } finally {
            file.delete()
        }
    }
}

class VideoArgsTest {
    @Test
    fun `the video command takes seconds and an output then optional flags`() {
        val v = parseArgs(listOf("video", "90", "out.mp4")) as Command.Video
        assertEquals(90.0, v.spec.seconds)
        assertEquals("out.mp4", v.out)
        assertEquals(VideoSpec(90.0), v.spec)
        val w = parseArgs(
            listOf("video", "--nodes", "40", "12", "--size", "640x400", "o.mp4", "--look", "oled", "--fps", "24",
                "--warmup", "0", "--at", "2026-11-29T23:00:00Z"),
        ) as Command.Video
        assertEquals(40, w.spec.nodes)
        assertContentEquals(intArrayOf(640, 400), intArrayOf(w.spec.width, w.spec.height))
        assertEquals(PaletteChoice.OledNight, w.spec.look)
        assertEquals(24, w.spec.fps)
        assertEquals(0.0, w.spec.warmupSeconds)
        assertEquals(1_795_636_800_000L + 4 * 86_400_000L + 3 * 3_600_000L, w.spec.startEpochMs)
    }

    @Test
    fun `bad video arguments are rejected`() {
        for (bad in listOf(
            listOf("video", "90"),
            listOf("video", "0", "o.mp4"),
            listOf("video", "90", "o.mp4", "--fps", "7"),
            listOf("video", "90", "o.mp4", "--size", "640"),
            listOf("video", "90", "o.mp4", "--look", "neon"),
            listOf("video", "90", "o.mp4", "--loud", "1"),
            listOf("video", "90", "o.mp4", "--nodes"),
            listOf("video", "90", "o.mp4", "--speed", "0"),
            listOf("video", "90", "o.mp4", "--fps", "480"),
        )) {
            assertFailsWith<IllegalArgumentException>("$bad") { parseArgs(bad) }
        }
    }
}
