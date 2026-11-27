package org.meshtastic.aether.music

import org.meshtastic.aether.mesh.MeshEvent
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Hours of composing are too long for the Wasm runner's 30 s limit, so this lives in the JVM tests. */
class TextureTest {
    private val sr = 8_000
    private val t0 = 1_800_000_000_000L

    /** Every texture of [hours] of composing from 10:00 on a Wednesday, one call per 5 s, [feed] before each call. */
    private fun textures(seed: Long, hours: Int, feed: (Composer, Long) -> Unit = { _, _ -> }): List<Texture> {
        val c = malletComposer(seed, sr)
        val out = ArrayList<Texture>()
        val window = 5L * sr
        var frame = 0L
        repeat(hours * 720) {
            val ms = t0 + frame * 1000 / sr
            feed(c, ms)
            val minute = 600 + (frame / sr / 60).toInt()
            out += c.advance(frame, window, LocalMoment(3, minute, ms)).controls
                .filterIsInstance<ControlEvent.SetTexture>().map { it.texture }
            frame += window
        }
        return out
    }

    private fun Texture.formShimmer(): Float = layerLevels[Voice.Shimmer] ?: 0f

    // Regression: an unreported quantity read as the middle of its range, so a mesh with no wind, lux or pressure
    // sensor (almost every mesh) kept a hiss of at least 0.35, an octave shimmer of at least 0.25 and detune at 0.35.
    @Test
    fun `with no weather reports the texture holds no hiss floor shimmer floor or pressure detune`() {
        val all = textures(31L, 2)
        assertTrue(all.size > 100, "only ${all.size} textures")
        for (t in all) {
            assertTrue(t.detune == 0.2f, "detune ${t.detune} with no pressure reading")
            assertTrue(abs(t.shimmer - 0.5f * t.formShimmer()) < 1e-6, "shimmer ${t.shimmer} over the form's")
            assertTrue(t.noise <= 0.3f + 1e-6, "noise ${t.noise} with no wind reading")
        }
    }

    @Test
    fun `a node reporting wind and lux brings the hiss and shimmer back`() {
        // Readings alternate low and high so the node builds a range to be scaled against.
        val all = textures(31L, 1) { c, ms ->
            val high = (ms / 5000) % 2 == 0L
            c.onEvent(MeshEvent.Environment(5L, null, null, null, if (high) 12f else 1f, if (high) 2000f else 50f, ms))
        }
        assertTrue(all.any { it.shimmer - 0.5f * it.formShimmer() > 0.2f }, "lux never lifted the shimmer")
        assertTrue(all.any { it.noise > 0.3f }, "wind never lifted the hiss")
    }

    // Regression: the hiss was added at its texture level whatever the form, so the form's rests were never quiet.
    @Test
    fun `the hiss stops in a rest`() {
        val all = textures(31L, 4)
        val rests = all.filter { t -> t.layerLevels.values.all { it < 0.02f } }
        assertTrue(rests.isNotEmpty(), "no rest in four hours")
        // A rest fades the layers in and out, so the hiss follows them down to a trace rather than to exactly zero.
        assertTrue(rests.all { it.noise < 0.01f }, "hiss in a rest: ${rests.maxOf { it.noise }}")
    }
}
