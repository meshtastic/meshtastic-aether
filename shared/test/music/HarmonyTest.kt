package org.meshtastic.aether.music

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarmonyTest {
    private val diatonic = Mode.entries.filter { it.size == 7 }

    /** [n] + 1 chords as the composer plays them; the placeholder before the first [Harmony.next] never sounds. */
    private fun run(mode: Mode, seed: Long, n: Int): List<Chord> {
        val h = Harmony(seed, mode)
        return List(n + 1) { h.next(mode) }
    }

    /** [ratio] folded into one octave, 1 <= r < 2. */
    private fun fold(ratio: Double): Double {
        var r = ratio
        while (r >= 2.0) r /= 2.0
        while (r < 1.0) r *= 2.0
        return r
    }

    @Test
    fun `the first chord is the tonic in every scale`() {
        // The placeholder was root 0 and the first change excluded the previous root, so a session never opened home.
        for (mode in Mode.entries) for (seed in 1L..5L) assertEquals(0, Harmony(seed, mode).next(mode).rootStep)
    }

    @Test
    fun `every diatonic chord has a pure fifth over its root`() {
        // A fixed 5-limit frame on the key gives ii, v and bVII a 40/27 fifth, a syntonic comma (21.5 cents) flat.
        for (mode in diatonic) for (seed in 1L..3L) run(mode, seed, 200).forEach { chord ->
            val root = chord.ratio(chord.roleSemitone(0))
            val fifth = chord.ratio(chord.roleSemitone(2))
            assertEquals(1.5, fold(fifth / root), 1e-9, "${mode.name} root step ${chord.rootStep}")
        }
    }

    @Test
    fun `no two chord voices sit a syntonic comma off a simple interval`() {
        // 40/27 and 27/20 are a comma off the fifth and fourth, 32/27 and 27/16 off the minor third and major sixth.
        // A pentatonic chord can be three stacked fourths, whose ends a pure tuning must leave at 32/27 or else beat a
        // fourth, so pentatonics are held to pure fifths and fourths only.
        val wolves = listOf(40.0 / 27, 27.0 / 20, 32.0 / 27, 27.0 / 16)
        for (mode in Mode.entries) for (seed in 1L..3L) run(mode, seed, 200).forEach { chord ->
            val banned = if (mode.size == 7) wolves else wolves.take(2)
            val r = chord.voiceSemitones.map { chord.ratio(it) }
            for (i in r.indices) for (j in i + 1 until r.size) {
                val interval = fold(maxOf(r[i], r[j]) / minOf(r[i], r[j]))
                assertTrue(
                    banned.none { abs(interval - it) < 1e-6 },
                    "${mode.name} root step ${chord.rootStep} has a wolf $interval",
                )
            }
        }
    }

    @Test
    fun `the tonic and fifth of the key never leave the key frame`() {
        // The hour chime and dawn swells sound the key's tonic and fifth on the key frame, so the bed must keep them.
        for (mode in Mode.entries) run(mode, 4L, 200).forEach { chord ->
            assertEquals(1.0, chord.ratio(0), 1e-12)
            assertEquals(1.5, chord.ratio(7), 1e-12)
        }
    }

    @Test
    fun `no node role sounds a minor ninth over its chord root`() {
        // On the chord of each mode's Phrygian degree the ninth is a semitone over the root; about one node in five
        // plays role 4.
        for (mode in Mode.entries) for (root in 0 until mode.size) {
            val chord = Chord(mode, root, List(4) { root + 2 * it })
            val ninth = (chord.roleSemitone(4) - chord.roleSemitone(0)).mod(12)
            assertTrue(ninth != 1, "${mode.name} root step $root")
        }
    }

    @Test
    fun `the tonic chord returns every few changes`() {
        // Without a pull home the tonic was one candidate in five or six and the key went unconfirmed for long spans.
        // Ten changes is about one 20-60 minute section at 2-8 minutes a chord.
        for (mode in diatonic) for (seed in 1L..5L) {
            var longest = 0
            var run = 0
            run(mode, seed, 300).forEach { chord ->
                run = if (chord.rootStep == 0) 0 else run + 1
                longest = maxOf(longest, run)
            }
            assertTrue(longest <= 10, "${mode.name} seed $seed went $longest chords without the tonic")
        }
    }

    @Test
    fun `consecutive chords hold at least one common tone at the same pitch`() {
        for (mode in Mode.entries) for (seed in 1L..5L) {
            run(mode, seed, 200).zipWithNext().forEach { (a, b) ->
                val held = a.voiceSemitones.zip(b.voiceSemitones).count { (x, y) -> x == y }
                assertTrue(held >= 1, "${mode.name} seed $seed held none")
            }
        }
    }

    @Test
    fun `every voice either holds or moves exactly one scale step`() {
        for (mode in Mode.entries) for (seed in 1L..5L) run(mode, seed, 300).zipWithNext().forEach { (a, b) ->
            a.voicing.zip(b.voicing).forEach { (x, y) -> assertTrue(abs(x - y) <= 1, "${mode.name} seed $seed moved $x to $y") }
        }
    }

    @Test
    fun `every chord tone lies in the mode`() {
        for (mode in Mode.entries) run(mode, 7L, 200).forEach { chord ->
            chord.voiceSemitones.forEach { s -> assertTrue(s.mod(12) in mode.semitones, "${mode.name} has no $s") }
        }
    }

    @Test
    fun `chords follow a mode change by taking their tones from the new mode`() {
        val h = Harmony(3L, Mode.Lydian)
        val modes = listOf(Mode.Aeolian, Mode.Mixolydian, Mode.Dorian, Mode.Ionian)
        repeat(40) { i ->
            val mode = modes[i % modes.size]
            h.next(mode).voiceSemitones.forEach { assertTrue(it.mod(12) in mode.semitones) }
        }
    }

    @Test
    fun `chord roots always carry a perfect fifth so no diminished triad sounds`() {
        for (mode in diatonic) run(mode, 11L, 300).forEach { chord ->
            val root = chord.roleSemitone(0)
            assertEquals(7, (chord.roleSemitone(2) - root).mod(12), "${mode.name} root step ${chord.rootStep}")
        }
    }

    @Test
    fun `the progression keeps moving to a new root each time`() {
        run(Mode.Ionian, 2L, 200).zipWithNext().forEach { (a, b) -> assertTrue(a.rootStep != b.rootStep) }
    }

    @Test
    fun `voicing stays within three octaves over a long progression in every mode`() {
        for (mode in Mode.entries) for (seed in 1L..4L) run(mode, seed, 1500).forEach { chord ->
            chord.voiceSemitones.forEach { assertTrue(it in -5..40, "${mode.name} seed $seed drifted to $it") }
        }
    }

    // Regression: the seven note wrap in the voice-leading search found no root in a five note scale and the progression stalled or threw.
    @Test
    fun `a pentatonic progression keeps every voice within one step and every tone in the scale`() {
        for (mode in listOf(Mode.MajorPentatonic, Mode.MinorPentatonic)) for (seed in 1L..5L) {
            run(mode, seed, 300).zipWithNext().forEach { (a, b) ->
                assertTrue(a.rootStep != b.rootStep)
                a.voicing.zip(b.voicing).forEach { (x, y) -> assertTrue(abs(x - y) <= 1, "${mode.name} seed $seed moved $x to $y") }
                b.voiceSemitones.forEach { assertTrue(it.mod(12) in mode.semitones, "${mode.name} has no $it") }
            }
        }
    }

    // Regression: a switch between a seven and a five note scale carried the old step numbers over and threw the voicing an octave or more away.
    @Test
    fun `switching between a mode and a pentatonic keeps the voicing within three octaves`() {
        val h = Harmony(9L, Mode.Dorian)
        val sequence = listOf(Mode.Dorian, Mode.MinorPentatonic, Mode.Dorian, Mode.Aeolian, Mode.MinorPentatonic, Mode.Ionian, Mode.MajorPentatonic)
        repeat(700) { i ->
            val mode = sequence[(i / 5) % sequence.size]
            val chord = h.next(mode)
            chord.voiceSemitones.forEach { assertTrue(it in -5..40, "step $i ${mode.name} drifted to $it") }
            chord.voiceSemitones.forEach { assertTrue(it.mod(12) in mode.semitones) }
        }
    }
}
