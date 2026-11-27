package org.meshtastic.aether.music

import org.meshtastic.aether.mesh.GeoPoint
import org.meshtastic.aether.mesh.LinkState
import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.mesh.PacketKind
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Register, motifs, call and response, day events, weather gestures and the mesh arc. */
class ExpressionTest {
    private val sr = 48_000
    private val t0 = 1_800_000_000_000L
    private val step = sr / 4L
    private val lookahead = sr / 2L
    private val wednesday = 3

    private fun moment(frame: Long, minute: Int = 600, day: Int = wednesday, epoch: Long = t0 + frame * 1000 / sr) =
        LocalMoment(day, minute, epoch)

    private fun key(day: Int = wednesday) = Day.at(LocalMoment(day, 600, 0L)).rootHz

    private fun heard(
        node: Long,
        atMs: Long = t0,
        kind: PacketKind = PacketKind.Position,
        hops: Int? = 0,
        relay: Int? = null,
    ) = MeshEvent.PacketHeard(node, kind, null, null, hops, false, atMs, null, relay, false)

    private fun Composer.know(node: Long, at: GeoPoint? = null, first: Boolean = false, atMs: Long = t0) =
        onEvent(MeshEvent.NodeSeen(node, null, at, first, atMs))

    private fun Composer.connected() = onEvent(MeshEvent.Link(LinkState.Connected, t0))

    private fun Composer.drive(fromFrame: Long, seconds: Int, minute: Int = 600, epochOffsetMs: Long = 0L): List<NoteEvent> {
        val out = ArrayList<NoteEvent>()
        var f = fromFrame
        val end = fromFrame + seconds.toLong() * sr
        while (f < end) {
            out += advance(f, lookahead, moment(f, minute, epoch = t0 + epochOffsetMs + f * 1000 / sr)).notes
            f += step
        }
        return out
    }

    /** True when [hz] sits a whole number of octaves from the just ratio of [semitone] above [keyHz]. */
    /** [hz] is [semitone]'s just ratio over the key, or a syntonic comma either side (a chord's tuning), in some octave. */
    private fun isJust(hz: Double, keyHz: Double, semitone: Int): Boolean = listOf(80.0 / 81, 1.0, 81.0 / 80).any { comma ->
        val d = log2(hz / keyHz) - log2(Theory.justRatio(semitone) * comma)
        abs(d - d.roundToInt()) < 1e-9
    }

    private fun tones(chord: Chord) = chord.voiceSemitones.map { it.mod(12) }

    // Regression: pads were transposed by a fractional octave while drone and bells were not, so pads sat out of tune with them.
    @Test
    fun `every pad pitch is a chord tone of the key at every minute of the day`() {
        val c = malletComposer(7L, sr)
        val key = key()
        val padPitches = ArrayList<Pair<Int, Double>>()
        var frame = 0L
        var checked = 0
        for (call in 0 until 2880) {
            val minute = call / 2
            val before = c.chord
            val out = c.advance(frame, 40L * sr, LocalMoment(wednesday, minute, t0 + frame * 1000 / sr)).notes
            val allowed = tones(before) + tones(c.chord)
            for (n in out.filter { it.voice == Voice.Pad }) {
                checked++
                assertTrue(allowed.any { isJust(n.pitchHz, key, it) }, "minute $minute pad ${n.pitchHz} Hz is no chord tone of the key")
                padPitches += minute to n.pitchHz
            }
            frame += 30L * sr
        }
        assertTrue(checked > 500, "only $checked pad notes")
    }

    // Regression: the register must move by whole octaves, once at night and once at dawn, never wander.
    @Test
    fun `the pad register is down an octave at night and normal by day and flips rarely`() {
        val c = malletComposer(8L, sr)
        val key = key()
        val shifts = ArrayList<Pair<Int, Int>>()
        var frame = 0L
        for (call in 0 until 2880) {
            val minute = call / 2
            val out = c.advance(frame, 40L * sr, LocalMoment(wednesday, minute, t0 + frame * 1000 / sr)).notes
            val chord = c.chord
            for (n in out.filter { it.voice == Voice.Pad && it.pan == -0.5f && it.velocity == 0.5f }) {
                val d = log2(n.pitchHz / key) - log2(Theory.justRatio(chord.voiceSemitones[0]))
                shifts += minute to d.roundToInt()
            }
            frame += 30L * sr
        }
        assertTrue(shifts.all { it.second == 0 || it.second == -1 })
        assertTrue(shifts.filter { it.first in 120..240 }.all { it.second == -1 }, "3:00 should sit an octave down")
        assertTrue(shifts.filter { it.first in 840..960 }.all { it.second == 0 }, "15:00 should be the normal register")
        assertTrue(shifts.zipWithNext().count { (a, b) -> a.second != b.second } <= 2, "the register flipped more than twice")
    }

    @Test
    fun `a motif has three or four notes and its first role is the node's own`() {
        for (node in 1L..300L) {
            val m = Motifs.of(node)
            assertTrue(m.size in 3..4)
            assertEquals(Ornaments.role(node), m.roles[0])
            assertEquals(0.0, m.gaps[0])
            assertTrue(m.roles.all { it in 0..4 })
            assertTrue(m.roles.toList().zipWithNext().all { (a, b) -> a != b })
            assertTrue(m.gaps.drop(1).all { it in Motifs.GAPS_SECONDS.toList() })
        }
        assertEquals(Motifs.of(77L).roles.toList(), Motifs.of(77L).roles.toList())
        assertTrue((1L..40L).map { Motifs.of(it).roles.toList() to Motifs.of(it).gaps.toList() }.toSet().size > 20, "motifs barely differ")
    }

    @Test
    fun `a lone packet in a quiet mesh plays its node's whole motif at its rhythm`() {
        val c = malletComposer(3L, sr)
        c.advance(0, lookahead, moment(0))
        c.onEvent(heard(4242L, atMs = t0 + 100))
        val notes = c.drive(step, 8).filter { it.voice == Voice.Mallet }.sortedBy { it.atFrame }
        val m = Motifs.of(4242L)
        assertEquals(m.size, notes.size)
        val band = Ornaments.band(4242L)
        m.roles.forEachIndexed { i, role ->
            assertEquals(Ornaments.pitchHz(role, band, c.chord, key()), notes[i].pitchHz, 1e-9)
            assertEquals(4242L, notes[i].node)
            if (i > 0) assertEquals(m.gaps[i], (notes[i].atFrame - notes[i - 1].atFrame) / sr.toDouble(), 1e-3)
        }
    }

    @Test
    fun `a busy mesh plays one note per packet and no motif`() {
        val c = malletComposer(3L, sr)
        c.advance(0, lookahead, moment(0))
        for (i in 0 until 14) c.onEvent(heard(900L + i, atMs = t0 + i * 400L))
        val notes = c.drive(step, 8).filter { it.voice == Voice.Mallet }
        assertEquals(14, notes.size)
        assertEquals(14, notes.map { it.node }.toSet().size)
    }

    @Test
    fun `memory bells replay fragments of the recent nodes' motifs and carry their node`() {
        val c = malletComposer(13L, sr)
        c.advance(0, lookahead, moment(0))
        c.onEvent(heard(555L, atMs = t0 + 100))
        c.drive(step, 4)
        val chord = c.chord
        val m = Motifs.of(555L)
        val allowed = m.roles.map { Ornaments.pitchHz(it, Ornaments.band(555L), chord, key()) }.toSet()
        val bells = c.drive(5L * sr, 90).filter { it.voice == Voice.Bell && it.velocity == 0.15f }
        assertTrue(bells.size >= 4, "only ${bells.size} memory bells")
        assertTrue(bells.all { it.node == 555L })
        assertTrue(bells.all { it.pitchHz in allowed }, "a memory bell left the motif")
        assertTrue(bells.map { it.pitchHz }.toSet().size >= 2, "memory bells never moved")
    }

    @Test
    fun `an echo through a relay carries the relay's node and the rest the source`() {
        val c = malletComposer(24L, sr)
        c.advance(0, lookahead, moment(0))
        val source = 1000L
        val relay = 2000L
        c.know(source)
        c.know(relay)
        c.onEvent(heard(source, atMs = t0 + 100, hops = 6, relay = (relay and 0xFF).toInt()))
        val notes = c.drive(step, 8).filter { it.voice == Voice.Mallet }.sortedBy { it.atFrame }
        val echoes = notes.drop(Motifs.of(source).size)
        assertEquals(listOf(relay, source), echoes.map { it.node })
        assertTrue(notes.take(Motifs.of(source).size).all { it.node == source })
    }

    private val home = GeoPoint(45.0, 10.0)
    private val near = GeoPoint(45.01, 10.0)
    private val far = GeoPoint(46.0, 11.0)

    private fun answers(notes: List<NoteEvent>, who: Long) = notes.filter { it.voice == Voice.Bell && it.node == who }

    @Test
    fun `the nearest known node answers a packet a third or fifth up two to four seconds later`() {
        val c = malletComposer(5L, sr)
        c.advance(0, lookahead, moment(0))
        c.know(1L, home)
        c.know(2L, far)
        c.know(3L, near)
        c.onEvent(heard(1L, atMs = t0 + 100))
        val notes = c.drive(step, 12)
        val reply = answers(notes, 3L)
        assertEquals(1, reply.size)
        assertTrue(answers(notes, 2L).isEmpty(), "the far node answered")
        val first = notes.filter { it.voice == Voice.Mallet }.minOf { it.atFrame }
        val wait = (reply[0].atFrame - first) / sr.toDouble()
        assertTrue(wait in 2.0..4.0, "answer after $wait s")
        val chord = c.chord
        val role = Ornaments.role(1L)
        val classes = listOf(1, 2).map { chord.roleSemitone((role + it).mod(5)) }
        assertTrue(classes.any { isJust(reply[0].pitchHz, key(), it) }, "answer is no third or fifth up the chord stack")
        assertTrue(reply[0].pitchHz > Ornaments.pitchHz(1L, chord, key()))
    }

    @Test
    fun `answers come at most once every twenty seconds`() {
        val c = malletComposer(5L, sr)
        c.advance(0, lookahead, moment(0))
        c.know(1L, home)
        c.know(3L, near)
        c.onEvent(heard(1L, atMs = t0 + 100))
        c.onEvent(heard(1L, atMs = t0 + 7_000))
        c.onEvent(heard(1L, atMs = t0 + 14_000))
        val early = answers(c.drive(step, 22), 3L)
        assertEquals(1, early.size)
        c.onEvent(heard(1L, atMs = t0 + 40_000))
        val later = answers(c.drive(22L * sr, 30), 3L)
        assertEquals(1, later.size)
    }

    @Test
    fun `with no positions the resolved relay answers and with no neighbor nobody does`() {
        val relay = 2000L
        val c = malletComposer(5L, sr)
        c.advance(0, lookahead, moment(0))
        c.know(1L)
        c.know(relay)
        c.onEvent(heard(1L, atMs = t0 + 100, relay = (relay and 0xFF).toInt()))
        assertEquals(1, answers(c.drive(step, 12), relay).filter { it.velocity == 0.3f }.size)
        val alone = malletComposer(5L, sr)
        alone.advance(0, lookahead, moment(0))
        alone.onEvent(heard(1L, atMs = t0 + 100))
        assertTrue(alone.drive(step, 12).none { it.voice == Voice.Bell && it.velocity == 0.3f })
    }

    private fun chimes(notes: List<NoteEvent>, key: Double) = notes.filter {
        it.voice == Voice.Bell && it.node == null && (abs(it.pitchHz - 2 * key) < 1e-6 || abs(it.pitchHz - 3 * key) < 1e-6)
    }.sortedBy { it.atFrame }

    private fun crossing(seed: Long, fromMinute: Int, toMinute: Int, frame: Long = 10L * sr): List<NoteEvent> {
        val c = malletComposer(seed, sr)
        val out = ArrayList<NoteEvent>()
        out += c.advance(frame, lookahead, moment(frame, fromMinute)).notes
        var f = frame + step
        while (f < frame + 40L * sr) {
            out += c.advance(f, lookahead, moment(f, toMinute)).notes
            f += step
        }
        return out
    }

    @Test
    fun `the hour strikes its count alternating root and fifth once`() {
        for ((hour, count) in listOf(11 to 11, 12 to 12, 13 to 1, 0 to 12, 3 to 3)) {
            val notes = chimes(crossing(4L, (hour * 60 + 1439) % 1440, hour * 60), key())
            assertEquals(count, notes.size, "hour $hour")
            notes.forEachIndexed { i, n -> assertEquals(if (i % 2 == 0) 2.0 else 3.0, n.pitchHz / key(), 1e-9) }
        }
    }

    @Test
    fun `no chime strikes inside a rest where the form is below its floor`() {
        val seed = 4L
        val form = Form(seed)
        val quietest = (0 until 6 * 3600).minBy { Form.visible(form.levels(it.toDouble())) }
        assertTrue(Form.visible(form.levels(quietest.toDouble())) <= Form.FLOOR, "no rest found")
        val notes = crossing(seed, 659, 660, frame = quietest.toLong() * sr)
        assertTrue(chimes(notes, key()).isEmpty())
    }

    @Test
    fun `dawn swells the shimmer root then fifth and dusk fifth then root`() {
        fun swell(from: Int, to: Int) = crossing(6L, from, to).filter {
            it.voice == Voice.Shimmer && it.node == null && (abs(it.pitchHz - 2 * key()) < 1e-6 || abs(it.pitchHz - 3 * key()) < 1e-6)
        }.sortedBy { it.atFrame }.map { it.pitchHz / key() }
        assertEquals(listOf(2.0, 3.0), swell(419, 420))
        assertEquals(listOf(3.0, 2.0), swell(1139, 1140))
        assertTrue(swell(500, 501).isEmpty())
    }

    private fun env(node: Long, atMs: Long, pressure: Float? = null, wind: Float? = null, temp: Float? = null) =
        MeshEvent.Environment(node, temp, null, pressure, wind, null, atMs)

    /** One composer call per [stepMs] of wall time and five seconds of audio, feeding [feed] before each. */
    private fun weatherRun(
        seed: Long,
        calls: Int,
        stepMs: Long,
        feed: (Composer, Int, Long) -> Unit,
    ): List<NoteEvent> {
        val c = malletComposer(seed, sr)
        val out = ArrayList<NoteEvent>()
        for (i in 0 until calls) {
            val ms = t0 + i * stepMs
            feed(c, i, ms)
            val f = 10L * sr + i * 5L * sr
            out += c.advance(f, lookahead, moment(f, epoch = ms)).notes
        }
        return out
    }

    private fun lowSwells(notes: List<NoteEvent>) = notes.filter { it.voice == Voice.Pad && it.pitchHz < key() }

    @Test
    fun `a pressure drop of two hectopascals over three hours is one low swell`() {
        val tenMin = 10L * 60 * 1000
        val falling = weatherRun(9L, 22, tenMin) { c, i, ms -> c.onEvent(env(1L, ms, pressure = 1015f - 1.0f * i * 10 / 60 * 1.5f)) }
        assertEquals(1, lowSwells(falling).size)
        val steady = weatherRun(9L, 60, tenMin) { c, _, ms -> c.onEvent(env(1L, ms, pressure = 1015f)) }
        assertTrue(lowSwells(steady).isEmpty(), "a steady barometer swelled")
        val slow = weatherRun(9L, 40, tenMin) { c, i, ms -> c.onEvent(env(1L, ms, pressure = 1015f - 0.4f * i * 10 / 60)) }
        assertTrue(lowSwells(slow).isEmpty(), "a fall under two hectopascals in three hours swelled")
    }

    @Test
    fun `a wind gust of three metres a second breathes noise and a steady wind does not`() {
        val minute = 60_000L
        val gust = weatherRun(10L, 6, minute) { c, i, ms ->
            c.know(7L, home, atMs = ms)
            c.onEvent(env(7L, ms, wind = if (i < 3) 2f else 6f))
        }
        val breaths = gust.filter { it.voice == Voice.Breath }
        assertEquals(1, breaths.size)
        assertEquals(7L, breaths[0].node)
        val mild = weatherRun(10L, 6, minute) { c, i, ms -> c.onEvent(env(7L, ms, wind = 2f + 0.5f * i)) }
        assertTrue(mild.none { it.voice == Voice.Breath })
    }

    @Test
    fun `a temperature crossing in the lydian morning strikes the raised fourth rising and the tonic falling`() {
        // 10:00 is Lydian, which holds no dark color, so a cooling crossing falls back to the tonic rather than leave
        // the scale. Pitches are compared within a syntonic comma because the chord may tune a tone by one.
        val minute = 60_000L
        val temps = listOf(5f, 5f, 20f, 20f, 5f, 5f)
        val notes = weatherRun(11L, temps.size, minute) { c, i, ms -> c.onEvent(env(3L, ms, temp = temps[i])) }
        val colors = notes.filter { it.voice == Voice.Bell && it.node == null }.sortedBy { it.atFrame }
        assertEquals(2, colors.size)
        val comma = 81.0 / 80.0
        val rising = colors[0].pitchHz / (key() * 2 * 45.0 / 32.0)
        val falling = colors[1].pitchHz / (key() * 2)
        assertTrue(rising in 1 / comma - 1e-9..comma + 1e-9, "rising ratio off the raised fourth: $rising")
        assertTrue(falling in 1 / comma - 1e-9..comma + 1e-9, "falling ratio off the tonic: $falling")
    }

    @Test
    fun `a color tone is always in the scale and the two directions never match`() {
        // The color bell used to play the raised fourth or minor sixth in any mode, an out-of-scale note in most.
        for (mode in Mode.entries) {
            val up = colorSemitone(mode, rising = true)
            val down = colorSemitone(mode, rising = false)
            assertTrue(up in mode.semitones && down in mode.semitones, "${mode.name} color $up $down out of scale")
            assertTrue(up != down && up != 1 && down != 1, "${mode.name} colors $up $down")
        }
        assertEquals(6, colorSemitone(Mode.Lydian, rising = true))
        assertEquals(8, colorSemitone(Mode.Aeolian, rising = false))
    }

    @Test
    fun `a node first seen this session adds a soft pad voice for the session up to three`() {
        val c = malletComposer(12L, sr)
        c.connected()
        c.advance(0, lookahead, moment(0))
        for (n in 100L..104L) c.know(n, first = true)
        c.know(200L, first = false)
        c.onEvent(MeshEvent.Link(LinkState.WarmingUp, t0))
        c.know(201L, first = true)
        val notes = c.drive(step, 100).filter { it.voice == Voice.Pad && it.node != null }
        val voices = notes.map { it.node }.toSet()
        assertEquals(setOf(102L, 103L, 104L), voices, "the three newest stay")
        assertTrue(200L !in voices)
        val chord = c.chord
        for (n in notes) {
            assertTrue(isJust(n.pitchHz, key(), chord.roleSemitone(Ornaments.role(n.node!!))), "voice for ${n.node} off its chord role")
            assertTrue(n.velocity <= 0.15f)
        }
        assertTrue(notes.count { it.node == 103L } >= 4, "the voice did not last")
    }

    @Test
    fun `a node silent for an hour gets one falling dyad of chord tones and speaking again re-arms it`() {
        val c = malletComposer(14L, sr)
        c.advance(0, lookahead, moment(0))
        c.know(9L)
        c.onEvent(heard(9L, atMs = t0))
        fun farewell(atMs: Long, frame: Long) =
            c.advance(frame, lookahead, moment(frame, epoch = atMs)).notes.filter { it.voice == Voice.Bell && it.node == 9L && it.velocity == 0.25f }
        assertTrue(farewell(t0 + 59 * 60_000L, 10L * sr).isEmpty(), "farewell before the hour")
        val gone = farewell(t0 + 61 * 60_000L, 11L * sr).sortedBy { it.atFrame }
        assertEquals(2, gone.size)
        assertTrue(gone[1].pitchHz < gone[0].pitchHz, "the dyad does not fall")
        val chord = c.chord
        assertTrue(isJust(gone[0].pitchHz, key(), chord.roleSemitone(Ornaments.role(9L))))
        assertTrue(isJust(gone[1].pitchHz, key(), chord.roleSemitone((Ornaments.role(9L) + 4).mod(5))))
        assertTrue(farewell(t0 + 63 * 60_000L, 12L * sr).isEmpty(), "farewell twice")
        c.onEvent(heard(9L, atMs = t0 + 64 * 60_000L))
        assertEquals(2, farewell(t0 + 130 * 60_000L, 13L * sr).size)
    }

    @Test
    fun `a node never heard this session gets no farewell`() {
        val c = malletComposer(14L, sr)
        c.advance(0, lookahead, moment(0))
        c.know(9L)
        val notes = c.advance(10L * sr, lookahead, moment(10L * sr, epoch = t0 + 3 * 3600_000L)).notes
        assertTrue(notes.none { it.node == 9L })
    }

    @Test
    fun `section labels count the form's sections and name its energy`() {
        val seed = 21L
        val c = malletComposer(seed, sr)
        val form = Form(seed)
        val bounds = form.sectionMinutes(6 * 3600.0).runningFold(0.0) { a, m -> a + m * 60 }.drop(1)
        for (t in 0 until 6 * 3600 step 37) {
            val label = c.sectionAt(t.toDouble())
            val index = bounds.count { it <= t } + 1
            val e = form.energy(t.toDouble())
            val word = if (e < 0.2) "near silence" else if (e < 0.5) "gathering" else if (e < 0.8) "full" else "peak"
            assertEquals("$index - $word", label)
        }
    }
}
