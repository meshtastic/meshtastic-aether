package org.meshtastic.aether.synth

import org.meshtastic.aether.music.ComposerOutput
import org.meshtastic.aether.music.Voice
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class SynthAllocationTest {
    // Regression: any allocation in render, such as boxing or a temporary array, adds GC pauses that the sink's lookahead must absorb.
    @Test
    fun `ten seconds of rendering with notes and texture changes allocates almost nothing`() {
        val rate = TEST_RATE
        val synth = Synth(rate)
        val list = ArrayList<org.meshtastic.aether.music.NoteEvent>()
        val voices = Voice.entries
        for (i in 0 until 60) list += note(voices[i % voices.size], 1000L + i * rate / 6, 110.0 * (1 + i % 6), 0.6f, 1.5, send = 0.5f)
        synth.schedule(ComposerOutput(list, emptyList()))
        for (i in 0 until 5) synth.schedule(controls(i * 2L * rate, texture(wet = 0.3f + 0.1f * i, size = 0.2f * i, noise = 0.2f)))
        val out = FloatArray(1024)
        synth.render(out, 512)
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(id)
        var frames = 0
        while (frames < 10 * rate) {
            synth.render(out, 512)
            frames += 512
        }
        val delta = bean.getThreadAllocatedBytes(id) - before
        assertTrue(delta < 16 * 1024, "allocated $delta bytes while rendering")
    }
}
