package org.meshtastic.ambient.music

import org.meshtastic.ambient.mesh.GeoPoint
import org.meshtastic.ambient.mesh.LinkState
import org.meshtastic.ambient.mesh.MeshEvent
import org.meshtastic.ambient.mesh.PacketKind
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ComposerTest {
    private val sr = 48_000
    private val t0 = 1_800_000_000_000L
    private val lookahead = sr / 2L
    private val step = sr / 4L

    private fun moment(frame: Long, day: Int = 3, minute: Int = 600) = LocalMoment(day, minute, t0 + frame * 1000 / sr)

    private fun heard(
        node: Long,
        kind: PacketKind = PacketKind.Position,
        atMs: Long = t0,
        snr: Float? = null,
        hops: Int? = null,
        mqtt: Boolean = false,
    ) = MeshEvent.PacketHeard(node, kind, snr, null, hops, mqtt, atMs)

    private class Run(val notes: MutableList<NoteEvent> = ArrayList(), val controls: MutableList<ControlEvent> = ArrayList())

    private fun Composer.drive(from: Long, seconds: Int, base: Run = Run(), minute: Int = 600): Run {
        var f = from
        val end = from + seconds.toLong() * sr
        while (f < end) {
            val out = advance(f, lookahead, moment(f, minute = minute))
            base.notes += out.notes
            base.controls += out.controls
            f += step
        }
        return base
    }

    private val ornamentVoices = setOf(Voice.Bell, Voice.Mallet, Voice.Pluck, Voice.Breath)

    private fun maxSounding(notes: List<NoteEvent>): Int {
        val edges = notes.flatMap { listOf(it.atFrame to 1, it.atFrame + it.durationFrames to -1) }
            .sortedWith(compareBy({ it.first }, { it.second }))
        var n = 0
        var best = 0
        for ((_, d) in edges) {
            n += d
            if (n > best) best = n
        }
        return best
    }

    @Test
    fun `an hour with zero packets still produces notes and textures`() {
        val run = Composer(1L, sr).drive(0, 3600)
        assertTrue(run.notes.size > 50, "only ${run.notes.size} notes")
        assertTrue(run.controls.filterIsInstance<ControlEvent.SetTexture>().size > 100)
        assertTrue(run.notes.any { it.atFrame > 30L * 60 * sr }, "silent after thirty minutes")
        for (n in run.notes) {
            assertTrue(n.pitchHz.isFinite() && n.pitchHz > 0.0)
            assertTrue(n.velocity in 0f..1f && n.reverbSend in 0f..1f && n.brightness in 0f..1f && n.pan in -1f..1f)
            assertTrue(n.durationFrames > 0)
        }
        for (c in run.controls.filterIsInstance<ControlEvent.SetTexture>()) {
            val t = c.texture
            listOf(t.brightness, t.reverbSize, t.reverbWet, t.detune, t.shimmer, t.noise).forEach { assertTrue(it in 0f..1f) }
            assertTrue(t.layerLevels.values.all { it in 0f..1f })
            assertTrue(t.rootHz.isFinite() && t.rootHz > 0.0)
        }
    }

    @Test
    fun `the autonomous piece plays only bed voices when no packet arrives`() {
        val run = Composer(2L, sr).drive(0, 1800)
        assertTrue(run.notes.all { it.voice in setOf(Voice.Drone, Voice.Pad, Voice.Shimmer) })
    }

    @Test
    fun `the same seed events and calls give the same output`() {
        fun go(seed: Long): Run {
            val c = Composer(seed, sr)
            val run = c.drive(0, 60)
            for (i in 1L..30L) c.onEvent(heard(i, atMs = t0 + 60_000 + i * 100))
            return c.drive(60L * sr, 60, run)
        }
        val a = go(5L)
        val b = go(5L)
        assertEquals(a.notes, b.notes)
        assertEquals(a.controls, b.controls)
        assertNotEquals(a.notes, go(6L).notes)
    }

    @Test
    fun `two hundred packets in one second never exceed the slot cap`() {
        val c = Composer(3L, sr)
        c.advance(0, lookahead, moment(0))
        for (i in 0 until 200) c.onEvent(heard(100L + i, atMs = t0 + i * 5L))
        val orn = c.drive(sr / 4L, 12).notes.filter { it.voice in ornamentVoices }
        assertTrue(orn.isNotEmpty())
        assertEquals(Ornaments.SLOT_CAP, maxSounding(orn), "cap should bind in this burst")
    }

    @Test
    fun `each note starts within two seconds of its own packet's arrival and never queues for the next burst`() {
        val c = Composer(3L, sr)
        c.advance(0, lookahead, moment(0))
        for (i in 0 until 200) c.onEvent(heard(100L + i, atMs = t0))
        for (i in 0 until 200) c.onEvent(heard(400L + i, atMs = t0 + 3000))
        val orn = c.drive(sr / 4L, 12).notes.filter { it.voice in ornamentVoices }
        val first = orn.filter { it.atFrame < 5L * sr / 2 }
        val second = orn.filter { it.atFrame >= 5L * sr / 2 }
        assertTrue(first.isNotEmpty() && second.isNotEmpty())
        assertTrue(first.all { it.atFrame <= 2L * sr }, "a first burst note started at ${first.maxOf { it.atFrame }}")
        assertTrue(second.all { it.atFrame <= 5L * sr }, "a second burst note started at ${second.maxOf { it.atFrame }}")
    }

    /** Ornament notes of a steady stream, and how long after the call that scheduled it each one starts. */
    private class Stream(val notes: MutableList<NoteEvent> = ArrayList(), val waits: MutableList<Double> = ArrayList())

    private fun Composer.drivePackets(perSecond: Int, seconds: Int, kind: PacketKind, hops: Int? = null): Stream {
        advance(0, lookahead, moment(0))
        val notes = Stream()
        val total = perSecond * seconds
        var n = 0
        var f = step
        while (f < (seconds + 4L) * sr) {
            val until = t0 + f * 1000 / sr
            while (n < total && t0 + n * 1000L / perSecond <= until) {
                onEvent(heard(1000L + n, kind, atMs = t0 + n * 1000L / perSecond, hops = hops))
                n++
            }
            for (note in advance(f, lookahead, moment(f)).notes.filter { it.voice in ornamentVoices }) {
                notes.notes += note
                notes.waits += (note.atFrame - f) / sr.toDouble()
            }
            f += step
        }
        return notes
    }

    @Test
    fun `a slow trickle of packets is not thinned and every packet sounds its note and one echo`() {
        val stream = Composer(3L, sr).drivePackets(10, 10, PacketKind.Routing)
        // Only the first few packets meet a quiet mesh and play a 3 to 4 note motif instead of one note.
        assertTrue(stream.notes.size in 2 * 100..2 * 100 + 6 * 3, "${stream.notes.size} notes for 100 packets")
    }

    @Test
    fun `a sustained storm is thinned to a sample that keeps sounding every second rather than dropped past a limit`() {
        val stream = Composer(3L, sr).drivePackets(100, 10, PacketKind.Routing, hops = 0)
        val notes = stream.notes
        for (second in 1..9) {
            val inSecond = notes.count { it.atFrame in (second * sr.toLong())..((second + 1) * sr.toLong() - 1) }
            assertTrue(inSecond >= 5, "second $second sounded $inSecond notes")
        }
        assertTrue(notes.size < 450, "nothing was thinned: ${notes.size} notes for 1000 packets")
        assertTrue(stream.waits.average() < 1.0, "notes waited ${stream.waits.average()} s: the slot queue is pinned at its TTL")
    }

    @Test
    fun `thirty packets in a tenth of a second stay at or under the slot cap`() {
        val c = Composer(4L, sr)
        c.advance(0, lookahead, moment(0))
        for (i in 0 until 30) c.onEvent(heard(10L + i, atMs = t0 + i * 3L))
        val orn = c.drive(sr / 4L, 8).notes.filter { it.voice in ornamentVoices }
        assertTrue(orn.size > Ornaments.SLOT_CAP)
        assertEquals(Ornaments.SLOT_CAP, maxSounding(orn))
    }

    @Test
    fun `a packet at unknown distance gets one echo while a direct one gets none and a far one two`() {
        fun notesFor(hops: Int?): Int {
            val c = Composer(7L, sr)
            c.advance(0, lookahead, moment(0))
            c.onEvent(heard(42L, atMs = t0, hops = hops))
            return c.drive(sr / 4L, 6).notes.count { it.voice in ornamentVoices }
        }
        val motif = Motifs.of(42L).size
        assertEquals(0, notesFor(0) - motif)
        assertEquals(1, notesFor(null) - motif)
        assertEquals(2, notesFor(8) - motif)
    }

    @Test
    fun `a node with no name no position null SNR and unknown hops sounds at middle distance centre pan and mid velocity`() {
        val c = Composer(8L, sr)
        c.advance(0, lookahead, moment(0))
        c.onEvent(heard(77L, snr = null, hops = null))
        val n = c.drive(sr / 4L, 3).notes.first { it.voice == Voice.Mallet }
        assertEquals(0f, n.pan)
        assertEquals(0.55f, n.velocity, 1e-6f)
        assertEquals(0.5f, n.reverbSend, 1e-6f)
        assertTrue(n.pitchHz.isFinite() && n.pitchHz > 0.0)
    }

    @Test
    fun `a direct packet is drier than an unknown one and a far one wetter`() {
        fun sendFor(hops: Int?): Float {
            val c = Composer(9L, sr)
            c.advance(0, lookahead, moment(0))
            c.onEvent(heard(5L, hops = hops))
            return c.drive(sr / 4L, 3).notes.first { it.voice == Voice.Mallet }.reverbSend
        }
        assertTrue(sendFor(0) < sendFor(null))
        assertTrue(sendFor(null) < sendFor(7))
    }

    @Test
    fun `encrypted packets give the same pitchless breath whatever the node`() {
        val c = Composer(10L, sr)
        c.advance(0, lookahead, moment(0))
        for (node in listOf(1L, 2L, 3L)) c.onEvent(heard(node, PacketKind.Encrypted, atMs = t0 + node * 400))
        val breaths = c.drive(sr / 4L, 6).notes.filter { it.voice == Voice.Breath }
        assertEquals(3, breaths.size)
        assertEquals(setOf(Ornaments.BREATH_HZ), breaths.map { it.pitchHz }.toSet())
    }

    @Test
    fun `a text message triggers a bell phrase now and a chord change once forty five seconds have passed`() {
        val c = Composer(11L, sr)
        val before = c.drive(0, 20)
        val chordBefore = c.chord
        c.onEvent(heard(9L, PacketKind.Text, atMs = t0 + 20_000))
        val phrase = c.drive(20L * sr, 6)
        assertEquals(chordBefore.rootStep, c.chord.rootStep, "the chord changed inside forty five seconds of the last one")
        assertTrue(phrase.notes.count { it.voice == Voice.Bell } >= 3)
        assertTrue(before.notes.none { it.voice == Voice.Bell })
        val after = c.drive(26L * sr, 44)
        assertNotEquals(chordBefore.rootStep, c.chord.rootStep)
        assertTrue(after.controls.filterIsInstance<ControlEvent.SetTexture>().isNotEmpty())
    }

    private fun Composer.driveTexts(seconds: Int, everyMs: Long, from: (Long) -> LocalMoment = ::moment): Pair<Run, List<Long>> {
        val run = Run()
        val changes = ArrayList<Long>()
        var fed = t0
        var f = 0L
        while (f < seconds.toLong() * sr) {
            val now = from(f).epochMillis
            while (fed + everyMs <= now) {
                fed += everyMs
                onEvent(heard(9L, PacketKind.Text, atMs = fed))
            }
            val before = chord
            val out = advance(f, lookahead, from(f))
            run.notes += out.notes
            run.controls += out.controls
            if (chord !== before) changes += out.controls.minOf { it.atFrame }
            f += step
        }
        return run to changes
    }

    private val bedVoices = setOf(Voice.Drone, Voice.Pad, Voice.Shimmer)

    @Test
    fun `thirty texts in one minute never leave more than one chord sounding at any frame`() {
        val (run, changes) = Composer(21L, sr).driveTexts(60, 2000)
        assertTrue(changes.size in 1..2, "chord changed at $changes")
        val bed = run.notes.filter { it.voice in bedVoices }
        assertEquals(1, maxSounding(bed.filter { it.voice == Voice.Drone }))
        for (change in changes) {
            val spanning = bed.filter { it.atFrame < change && it.atFrame + it.durationFrames > change }
            assertTrue(spanning.isEmpty(), "${spanning.size} notes of the old chord sound past the change at $change")
        }
        assertTrue(maxSounding(bed) <= 9, "${maxSounding(bed)} bed notes sound at once")
    }

    @Test
    fun `texts every second change the chord at most once in forty five seconds but still bend the piece`() {
        val (_, changes) = Composer(22L, sr).driveTexts(300, 1000)
        assertTrue(changes.size >= 4, "chord changed only at $changes")
        changes.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 45L * sr, "changes ${(b - a) / sr.toDouble()} s apart") }
    }

    @Test
    fun `the day's key changes once and only on a chord boundary with no glide`() {
        val sunday = Theory.C3_HZ * 2.0.pow(5.0 / 12)
        val monday = Theory.C3_HZ
        fun at(f: Long): LocalMoment {
            val minutes = 1435 + (f / sr) / 60
            val epoch = t0 + f * 1000 / sr
            return if (minutes < 1440) LocalMoment(7, minutes.toInt(), epoch) else LocalMoment(1, (minutes - 1440).toInt(), epoch)
        }
        val (run, changes) = Composer(23L, sr).driveTexts(15 * 60, Long.MAX_VALUE / 4, ::at)
        val textures = run.controls.filterIsInstance<ControlEvent.SetTexture>()
        assertTrue(textures.all { it.texture.rootHz == sunday || it.texture.rootHz == monday }, "a glide value reached the synth")
        val firstMonday = textures.first { it.texture.rootHz == monday }.atFrame
        assertTrue(textures.filter { it.atFrame >= firstMonday }.all { it.texture.rootHz == monday }, "the key went back")
        assertTrue(firstMonday in 300L * sr..321L * sr, "key changed at ${firstMonday / sr.toDouble()} s, midnight is 300 s")
        assertTrue(firstMonday in changes, "the key changed away from a chord change")
        val spanning = run.notes.filter { it.voice in bedVoices && it.atFrame < firstMonday && it.atFrame + it.durationFrames > firstMonday }
        assertTrue(spanning.isEmpty(), "${spanning.size} Sunday notes sound into Monday")
    }

    @Test
    fun `chords do not change without a text inside two minutes`() {
        val c = Composer(11L, sr)
        c.drive(0, 5)
        val root = c.chord.rootStep
        val voicing = c.chord.voicing
        c.drive(5L * sr, 100)
        assertEquals(root, c.chord.rootStep)
        assertEquals(voicing, c.chord.voicing)
    }

    @Test
    fun `packets during warm-up make no ornaments and after it they do`() {
        val c = Composer(12L, sr)
        c.advance(0, lookahead, moment(0))
        c.onEvent(MeshEvent.Link(LinkState.WarmingUp, t0))
        for (i in 0 until 20) c.onEvent(heard(i + 1L, atMs = t0 + i * 50L))
        val warm = c.drive(sr / 4L, 5)
        assertTrue(warm.notes.none { it.voice in ornamentVoices })
        c.onEvent(MeshEvent.Link(LinkState.Connected, t0 + 5_000))
        for (i in 0 until 20) c.onEvent(heard(i + 1L, atMs = t0 + 5_000 + i * 50L))
        val live = c.drive(5L * sr, 5)
        assertTrue(live.notes.any { it.voice == Voice.Mallet })
    }

    @Test
    fun `a quiet mesh brings recent nodes back as soft bell notes`() {
        val c = Composer(13L, sr)
        c.advance(0, lookahead, moment(0))
        for (i in 1L..3L) c.onEvent(heard(i, atMs = t0 + i * 100))
        val run = c.drive(sr / 4L, 20 * 60)
        val bells = run.notes.filter { it.voice == Voice.Bell }
        assertTrue(bells.isNotEmpty(), "no memory notes in twenty quiet minutes")
        assertTrue(bells.all { it.velocity <= 0.3f })
    }

    @Test
    fun `a busy mesh gets no memory notes`() {
        val c = Composer(13L, sr)
        c.advance(0, lookahead, moment(0))
        for (s in 0 until 600) for (k in 0 until 3) c.onEvent(heard(1L + k, atMs = t0 + s * 1000L + k * 300))
        val bells = c.drive(sr / 4L, 600).notes.filter { it.voice == Voice.Bell }
        assertTrue(bells.isEmpty())
    }

    @Test
    fun `a node east of the origin pans right and one to the west pans left`() {
        val c = Composer(14L, sr)
        c.origin = GeoPoint(45.0, 10.0)
        c.advance(0, lookahead, moment(0))
        c.onEvent(MeshEvent.NodeSeen(1L, null, GeoPoint(45.0, 10.5), false, t0))
        c.onEvent(MeshEvent.NodeSeen(2L, null, GeoPoint(45.0, 9.5), false, t0))
        c.onEvent(heard(1L, atMs = t0 + 500))
        c.onEvent(heard(2L, atMs = t0 + 2500))
        val mallets = c.drive(sr / 4L, 6).notes.filter { it.voice == Voice.Mallet }
        val pans = mallets.map { it.pan }
        assertTrue(pans.any { it > 0.5f } && pans.any { it < -0.5f })
    }

    @Test
    fun `a node first seen this session adds one soft pad swell`() {
        fun go(withNode: Boolean): List<NoteEvent> {
            val c = Composer(15L, sr)
            c.advance(0, lookahead, moment(0))
            if (withNode) c.onEvent(MeshEvent.NodeSeen(5L, "x", null, true, t0))
            return c.drive(sr / 4L, 5).notes
        }
        val extra = go(true) - go(false).toSet()
        assertEquals(1, extra.size)
        assertEquals(Voice.Pad, extra.single().voice)
    }

    @Test
    fun `ornaments land at frame precision a year into the run`() {
        val far = 1_500_000_000_000L
        val c = Composer(16L, sr)
        c.advance(far, lookahead, moment(far))
        c.onEvent(heard(3L, atMs = moment(far).epochMillis))
        val first = c.drive(far + step, 3).notes.first { it.voice == Voice.Mallet }
        val grid = (Ornaments.GRID_SECONDS * sr).toLong()
        assertTrue(first.atFrame >= far + step, "started at ${first.atFrame}")
        assertTrue(first.atFrame <= far + step + grid + sr / 50, "started ${first.atFrame - far} after the packet")
    }

    @Test
    fun `an old packet past its TTL is dropped rather than played late`() {
        val c = Composer(17L, sr)
        c.advance(0, lookahead, moment(0))
        c.onEvent(heard(3L, atMs = t0 - 10_000))
        assertTrue(c.drive(sr / 4L, 5).notes.none { it.voice in ornamentVoices })
    }

    @Test
    fun `day rollover with a weather range of one reading yields finite textures`() {
        val c = Composer(18L, sr)
        c.onEvent(MeshEvent.Environment(1L, 12f, 55f, 1005f, 3f, 200f, t0))
        c.advance(0, lookahead, LocalMoment(7, 1439, t0))
        val out = c.advance(step, lookahead, LocalMoment(1, 0, t0 + 60_000))
        val all = out.controls.filterIsInstance<ControlEvent.SetTexture>()
        val texture = c.drive(2L * step, 30, minute = 0).controls.filterIsInstance<ControlEvent.SetTexture>() + all
        assertTrue(texture.isNotEmpty())
        texture.forEach { t ->
            listOf(t.texture.brightness, t.texture.reverbSize, t.texture.reverbWet, t.texture.detune, t.texture.shimmer, t.texture.noise)
                .forEach { assertTrue(!it.isNaN() && it in 0f..1f) }
        }
    }

    private fun firstTexture(c: Composer, epoch: Long): Texture =
        c.advance(0, lookahead, LocalMoment(3, 600, epoch)).controls.filterIsInstance<ControlEvent.SetTexture>().first().texture

    private fun temp(node: Long, c: Float, at: Long) = MeshEvent.Environment(node, c, null, null, null, null, at)

    @Test
    fun `weather is the median across nodes rather than the last node to report`() {
        val three = Composer(24L, sr)
        three.onEvent(temp(3L, 20f, t0))
        three.onEvent(temp(1L, 10f, t0 + 1000))
        three.onEvent(temp(2L, 30f, t0 + 2000))
        val one = Composer(24L, sr)
        one.onEvent(temp(9L, 10f, t0))
        one.onEvent(temp(9L, 30f, t0 + 1000))
        one.onEvent(temp(9L, 20f, t0 + 2000))
        assertEquals(firstTexture(one, t0 + 3000).brightness, firstTexture(three, t0 + 3000).brightness, 1e-6f)
    }

    @Test
    fun `a node that has not reported for an hour drops out of the weather median`() {
        val min = 60_000L
        val two = Composer(25L, sr)
        two.onEvent(temp(1L, 30f, t0))
        two.onEvent(temp(2L, 10f, t0 + 30 * min))
        val one = Composer(25L, sr)
        one.onEvent(temp(1L, 30f, t0))
        one.onEvent(temp(1L, 10f, t0 + 30 * min))
        assertEquals(firstTexture(one, t0 + 75 * min).brightness, firstTexture(two, t0 + 75 * min).brightness, 1e-6f)
    }

    @Test
    fun `bed notes carry a fixed velocity and the form level reaches the synth only through the layer levels`() {
        val run = Composer(2L, sr).drive(0, 3600)
        assertEquals(1, run.notes.filter { it.voice == Voice.Drone }.map { it.velocity }.toSet().size)
        assertTrue(run.notes.filter { it.voice == Voice.Shimmer }.map { it.velocity }.toSet().size <= 1)
        assertTrue(run.notes.filter { it.voice == Voice.Pad }.map { it.velocity }.toSet().size <= 3)
        val textures = run.controls.filterIsInstance<ControlEvent.SetTexture>().map { it.texture }
        assertTrue(textures.all { it.layerLevels.keys == bedVoices }, "ornament voices must not be scaled by the form")
        assertTrue(textures.map { it.layerLevels.getValue(Voice.Drone) }.toSet().size > 10)
    }

    @Test
    fun `ornaments sound at their own velocity even where the form has gone silent`() {
        val seed = 31L
        val form = Form(seed)
        val tRest = (0 until 3 * 3600 step 5).first { Form.visible(form.levels(it.toDouble())) < 0.001f }
        val f = tRest * sr.toLong()
        val c = Composer(seed, sr)
        c.advance(f, lookahead, moment(f))
        c.onEvent(heard(77L, atMs = moment(f).epochMillis))
        val mallet = c.drive(f + step, 3).notes.first { it.voice == Voice.Mallet }
        assertEquals(0.55f, mallet.velocity, 1e-6f)
        assertTrue(c.drive(f + 4L * sr, 20).controls.filterIsInstance<ControlEvent.SetTexture>().all { it.texture.layerLevels.keys == bedVoices })
    }

    @Test
    fun `the shimmer plays in the pad's register not two octaves above it`() {
        val seed = (1L..30L).first { s -> Form(s).let { form -> (0 until 4 * 3600 step 10).any { form.levels(it.toDouble()).getValue(Voice.Shimmer) > 0.3f } } }
        val form = Form(seed)
        val tSec = (0 until 4 * 3600 step 10).first { form.levels(it.toDouble()).getValue(Voice.Shimmer) > 0.3f }
        val f = tSec.toLong() * sr
        val c = Composer(seed, sr)
        val run = c.drive(f, 180)
        val shimmer = run.notes.filter { it.voice == Voice.Shimmer }
        assertTrue(shimmer.isNotEmpty(), "no shimmer note near second $tSec for seed $seed")
        val padPitches = run.notes.filter { it.voice == Voice.Pad }.map { it.pitchHz }.toSet()
        assertTrue(shimmer.all { it.pitchHz in padPitches }, "shimmer ${shimmer.map { it.pitchHz }} vs pads $padPitches")
    }

    private fun link(
        node: Long,
        to: Long? = null,
        relay: Int? = null,
        toUs: Boolean = false,
        hops: Int? = 6,
        kind: PacketKind = PacketKind.Position,
    ) = MeshEvent.PacketHeard(node, kind, null, null, hops, false, t0, to, relay, toUs)

    private fun Composer.know(vararg nodes: Long) {
        for (n in nodes) onEvent(MeshEvent.NodeSeen(n, null, null, false, t0))
    }

    private fun hzOf(node: Long, c: Composer): Double =
        Ornaments.pitchHz(node, c.chord, Day.at(moment(0)).rootHz)

    private fun mallets(c: Composer, seconds: Int = 6) =
        c.drive(sr / 4L, seconds).notes.filter { it.voice == Voice.Mallet }.sortedBy { it.atFrame }

    /** The echoes of a lone packet in a quiet mesh: what follows its node's motif. */
    private fun echoes(c: Composer, node: Long) = mallets(c).drop(Motifs.of(node).size)

    @Test
    fun `a call to us is drier shorter brighter and louder than the same packet as a broadcast`() {
        fun first(to: Long, toUs: Boolean): NoteEvent {
            val c = Composer(21L, sr)
            c.advance(0, lookahead, moment(0))
            c.onEvent(link(5L, to = to, toUs = toUs, hops = 4))
            return mallets(c).first()
        }
        val call = first(77L, true)
        val broadcast = first(Ornaments.BROADCAST, false)
        assertTrue(call.reverbSend < broadcast.reverbSend)
        assertTrue(call.durationFrames < broadcast.durationFrames)
        assertTrue(call.velocity > broadcast.velocity)
        assertTrue(call.brightness > broadcast.brightness)
    }

    @Test
    fun `a broadcast sounds a slightly wider reverb send than a packet of unknown destination`() {
        fun send(to: Long?): Float {
            val c = Composer(22L, sr)
            c.advance(0, lookahead, moment(0))
            c.onEvent(link(5L, to = to, hops = 4))
            return mallets(c).first().reverbSend
        }
        assertEquals(send(null) + 0.05f, send(Ornaments.BROADCAST), 1e-6f)
    }

    @Test
    fun `a self event sets the origin and a node east of it then pans right`() {
        val c = Composer(23L, sr)
        c.onEvent(MeshEvent.Self(1L, "me", GeoPoint(45.0, 10.0), t0))
        assertEquals(GeoPoint(45.0, 10.0), c.origin)
        c.advance(0, lookahead, moment(0))
        c.onEvent(MeshEvent.NodeSeen(2L, null, GeoPoint(45.0, 10.5), false, t0))
        c.onEvent(heard(2L, atMs = t0 + 500))
        assertTrue(mallets(c).all { it.pan > 0.5f })
    }

    @Test
    fun `an echo after a resolved relay plays the relay's pitch and the later echo the source's`() {
        val source = 1000L
        val relay = (2000L..2255L).first { hzOf(it, Composer(24L, sr).also { c -> c.advance(0, lookahead, moment(0)) }) != hzOf(source, Composer(24L, sr).also { c -> c.advance(0, lookahead, moment(0)) }) }
        val c = Composer(24L, sr)
        c.advance(0, lookahead, moment(0))
        c.know(source, relay)
        c.onEvent(link(source, relay = (relay and 0xFF).toInt()))
        val notes = echoes(c, source)
        assertEquals(2, notes.size)
        assertEquals(listOf(hzOf(relay, c), hzOf(source, c)), notes.map { it.pitchHz })
    }

    @Test
    fun `an unresolved relay keeps every echo on the source pitch`() {
        val source = 1000L
        val c = Composer(25L, sr)
        c.advance(0, lookahead, moment(0))
        c.know(source, 3001L, 3002L)
        c.onEvent(link(source, relay = 0x77))
        val notes = echoes(c, source)
        assertEquals(2, notes.size)
        assertEquals(setOf(hzOf(source, c)), notes.map { it.pitchHz }.toSet())
    }

    @Test
    fun `a relay byte shared by two known nodes keeps the echoes on the source pitch`() {
        val source = 1000L
        val c = Composer(26L, sr)
        c.advance(0, lookahead, moment(0))
        c.know(source, 0x1105L, 0x2205L)
        c.onEvent(link(source, relay = 0x05))
        assertEquals(setOf(hzOf(source, c)), echoes(c, source).map { it.pitchHz }.toSet())
    }

    @Test
    fun `a relay byte matching only our own node is not resolved`() {
        val source = 1000L
        val me = (2000L..2255L).first { hzOf(it, Composer(27L, sr).also { c -> c.advance(0, lookahead, moment(0)) }) != hzOf(source, Composer(27L, sr).also { c -> c.advance(0, lookahead, moment(0)) }) }
        val c = Composer(27L, sr)
        c.onEvent(MeshEvent.Self(me, null, null, t0))
        c.advance(0, lookahead, moment(0))
        c.know(source, me)
        c.onEvent(link(source, relay = (me and 0xFF).toInt()))
        assertEquals(setOf(hzOf(source, c)), echoes(c, source).map { it.pitchHz }.toSet())
    }

    @Test
    fun `a route phrase plays bells through the route nodes in order`() {
        val c = Composer(28L, sr)
        c.advance(0, lookahead, moment(0))
        val route = listOf(11L, 12L, 13L, 14L)
        c.onEvent(MeshEvent.Route(route, t0))
        val bells = c.drive(sr / 4L, 6).notes.filter { it.voice == Voice.Bell }.sortedBy { it.atFrame }
        assertEquals(route.map { hzOf(it, c) }, bells.map { it.pitchHz })
    }

    @Test
    fun `route phrases inside a packet burst keep the ornament slot cap`() {
        val c = Composer(29L, sr)
        c.advance(0, lookahead, moment(0))
        for (i in 0 until 200) c.onEvent(heard(100L + i, atMs = t0 + i * 5L))
        repeat(5) { c.onEvent(MeshEvent.Route(listOf(1L, 2L, 3L, 4L, 5L, 6L), t0)) }
        val orn = c.drive(sr / 4L, 12).notes.filter { it.voice in ornamentVoices }
        assertEquals(Ornaments.SLOT_CAP, maxSounding(orn))
    }

    private fun dyadExtras(vararg at: Long): Int {
        fun run(withEvents: Boolean): List<NoteEvent> {
            val c = Composer(30L, sr)
            c.advance(0, lookahead, moment(0))
            c.know(1L, 2L, 3L)
            val out = ArrayList<NoteEvent>()
            var f = sr / 4L
            for (ms in at) {
                if (withEvents) c.onEvent(MeshEvent.Neighbors(1L, listOf(9L, 2L, 3L), t0 + ms))
                val end = (ms / 1000 + 5) * sr
                while (f < end) {
                    out += c.advance(f, lookahead, moment(f)).notes
                    f += step
                }
            }
            return out
        }
        return (run(true) - run(false).toSet()).count { it.voice == Voice.Pad }
    }

    @Test
    fun `neighbor reports sound one pad dyad of two roles per minute at most`() {
        assertEquals(2, dyadExtras(0L, 30_000L))
        assertEquals(4, dyadExtras(0L, 30_000L, 61_000L))
    }
}
