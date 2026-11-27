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
    // One picture per 1/fps of audio, none during the warm-up, the scene moving between pictures and the music still
    // playing at the end: a frozen frame means the offscreen scene never saw the clock advance, and silence means the
    // engine's virtual time stood still.
    @Test
    fun `a render gives one moving picture per frame period after the warm-up and audio for all of it`() {
        val spec = VideoSpec(seconds = 1.0, fps = 10, width = 64, height = 43, warmupSeconds = 0.5)
        var audioFrames = 0L
        var lateEnergy = 0.0
        val pictures = ArrayList<ByteArray>()
        val clock = renderFrames(spec, onAudio = { block, n ->
            if (audioFrames >= 48_000) for (i in 0 until n * 2) lateEnergy += block[i] * block[i]
            audioFrames += n
        }) { bgra, index ->
            assertEquals(pictures.size, index)
            pictures += bgra.copyOf()
        }
        assertEquals(10, pictures.size)
        assertEquals((1.5 * 48_000).toLong(), audioFrames)
        assertTrue(pictures.all { it.size == 64 * 43 * 4 })
        assertTrue(pictures.zipWithNext().count { (a, b) -> !a.contentEquals(b) } >= 8, "the scene stood still")
        val rms = kotlin.math.sqrt(lateEnergy / (0.5 * 48_000 * 2))
        assertTrue(rms > 1e-3, "the last half second is silent: rms $rms")
        // The composer and the mesh run on virtual time; it must follow the audio to within one frame period.
        assertEquals(clock.audioFrames, audioFrames)
        val drift = kotlin.math.abs(clock.virtualMillis - audioFrames * 1000 / 48_000)
        assertTrue(drift <= 100, "virtual time ${clock.virtualMillis} ms")
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
        )) {
            assertFailsWith<IllegalArgumentException>("$bad") { parseArgs(bad) }
        }
    }
}
