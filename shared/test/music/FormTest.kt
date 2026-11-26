package org.meshtastic.ambient.music

import kotlin.test.Test
import kotlin.test.assertTrue

class FormTest {
    private val hours4 = 4 * 3600

    @Test
    fun `every layer level stays between zero and one over a day`() {
        val form = Form(1L)
        for (t in 0 until 24 * 3600 step 30) {
            val levels = form.levels(t.toDouble())
            levels.forEach { (voice, v) -> assertTrue(v in 0f..1f, "$voice $v at $t") }
        }
    }

    @Test
    fun `some passage within four hours thins to a total level below a tenth`() {
        for (seed in 1L..10L) {
            val form = Form(seed)
            val quietest = (0 until hours4 step 30).minOf { Form.total(form.levels(it.toDouble())) }
            assertTrue(quietest < 0.1f, "seed $seed quietest $quietest")
        }
    }

    @Test
    fun `the piece is not thin for the whole four hours`() {
        for (seed in 1L..10L) {
            val form = Form(seed)
            val loudest = (0 until hours4 step 30).maxOf { Form.total(form.levels(it.toDouble())) }
            assertTrue(loudest > 1.0f, "seed $seed loudest $loudest")
        }
    }

    @Test
    fun `sections last between twenty and sixty minutes`() {
        for (seed in 1L..10L) Form(seed).sectionMinutes(24.0 * 3600).forEach {
            assertTrue(it in 20.0..60.0, "section of $it minutes")
        }
    }

    @Test
    fun `layers enter in order drone then pad then bell then shimmer as energy rises`() {
        val form = Form(4L)
        val ordered = (0 until 6600 step 20).map { form.levels(it.toDouble()) }
        for (l in ordered) {
            if (l.getValue(Voice.Shimmer) > 0f) assertTrue(l.getValue(Voice.Bell) > 0f)
            if (l.getValue(Voice.Bell) > 0f) assertTrue(l.getValue(Voice.Pad) > 0f)
            if (l.getValue(Voice.Pad) > 0f) assertTrue(l.getValue(Voice.Drone) > 0f)
        }
    }

    @Test
    fun `levels never jump between adjacent seconds so section changes crossfade`() {
        val form = Form(5L)
        var prev = Form.total(form.levels(0.0))
        for (t in 1 until 6 * 3600) {
            val cur = Form.total(form.levels(t.toDouble()))
            assertTrue(kotlin.math.abs(cur - prev) < 0.05f, "jump $prev to $cur at $t")
            prev = cur
        }
    }
}
