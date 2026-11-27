package org.meshtastic.aether.music

import kotlin.test.Test
import kotlin.test.assertTrue

/** Nine thousand chords are too many for the Wasm runner's 30 s limit, so this soak lives in the JVM tests. */
class HarmonySoakTest {
    // Without a pull home the tonic was one candidate in five or six and the key went unconfirmed for long spans.
    // Ten changes is about one 20-60 minute section at 2-8 minutes a chord.
    @Test
    fun `the tonic chord returns every few changes`() {
        for (mode in Mode.entries.filter { it.size == 7 }) for (seed in 1L..5L) {
            val harmony = Harmony(seed, mode)
            var longest = 0
            var run = 0
            repeat(301) {
                run = if (harmony.next(mode).rootStep == 0) 0 else run + 1
                longest = maxOf(longest, run)
            }
            assertTrue(longest <= 10, "${mode.name} seed $seed went $longest chords without the tonic")
        }
    }
}
