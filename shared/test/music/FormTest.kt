package org.meshtastic.aether.music

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

    private fun visible(l: Map<Voice, Float>) = maxOf(l.getValue(Voice.Drone), l.getValue(Voice.Pad), l.getValue(Voice.Shimmer))

    @Test
    fun `some passage within four hours thins to near silence`() {
        for (seed in 1L..10L) {
            val form = Form(seed)
            val quietest = (0 until hours4 step 10).minOf { visible(form.levels(it.toDouble())) }
            assertTrue(quietest < 0.01f, "seed $seed quietest $quietest")
        }
    }

    @Test
    fun `over twenty four hours the visible level stays above the floor except in passages of at most four minutes`() {
        val floor = 0.1f
        val dayEnd = 24 * 3600
        for (seed in 1L..10L) {
            val form = Form(seed)
            var runStart = -1
            var longest = 0
            var passages = 0
            for (t in 0..dayEnd step 5) {
                val low = visible(form.levels(t.toDouble())) < floor
                if (low && runStart < 0) runStart = t
                if (!low && runStart >= 0) {
                    longest = maxOf(longest, t - runStart)
                    passages++
                    runStart = -1
                }
            }
            assertTrue(longest <= 240, "seed $seed has a quiet passage of $longest s")
            assertTrue(passages >= 10, "seed $seed has only $passages quiet passages in a day")
        }
    }

    @Test
    fun `a thin section still sounds a drone and a pad at the peak of the arc`() {
        for (seed in 1L..40L) {
            val form = Form(seed)
            val minutes = form.sectionMinutes(24.0 * 3600).size
            assertTrue(minutes > 0)
            for (t in 0 until 24 * 3600 step 60) {
                val energy = form.energy(t.toDouble())
                if (energy < 0.9) continue
                val l = form.levels(t.toDouble())
                assertTrue(l.getValue(Voice.Drone) > 0.5f, "seed $seed drone ${l.getValue(Voice.Drone)} at $t")
                assertTrue(l.getValue(Voice.Pad) > 0.2f, "seed $seed pad ${l.getValue(Voice.Pad)} at $t")
            }
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
