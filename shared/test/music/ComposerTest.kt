package org.meshtastic.ambient.music

import org.meshtastic.ambient.mesh.GeoPoint
import org.meshtastic.ambient.mesh.LinkState
import org.meshtastic.ambient.mesh.MeshEvent
import org.meshtastic.ambient.mesh.PacketKind
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
    fun `two hundred packets in one second never exceed the slot cap and none start after the TTL`() {
        val c = Composer(3L, sr)
        c.advance(0, lookahead, moment(0))
        for (i in 0 until 200) c.onEvent(heard(100L + i, atMs = t0 + i * 5L))
        val run = c.drive(sr / 4L, 12)
        val orn = run.notes.filter { it.voice in ornamentVoices }
        assertTrue(orn.isNotEmpty())
        assertEquals(Ornaments.SLOT_CAP, maxSounding(orn), "cap should bind in this burst")
        val lastArrival = sr * 995L / 1000
        val latest = lastArrival + (Ornaments.TTL_SECONDS * sr).toLong()
        assertTrue(orn.all { it.atFrame <= latest }, "a note started ${orn.maxOf { it.atFrame } - latest} frames past TTL")
        assertTrue(orn.size < 200, "thinning should keep ${orn.size} of 200 under the packet count")
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
    fun `a packet gets only the single hop echo count for unknown distance`() {
        val c = Composer(7L, sr)
        c.advance(0, lookahead, moment(0))
        c.onEvent(heard(42L, atMs = t0))
        val orn = c.drive(sr / 4L, 6).notes.filter { it.voice in ornamentVoices }
        assertEquals(1 + Ornaments.echoesFor(null, false), orn.size)
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
    fun `a text message triggers a chord change and a bell phrase`() {
        val c = Composer(11L, sr)
        val before = c.drive(0, 20)
        val chordBefore = c.chord
        assertEquals(chordBefore, c.chord)
        c.onEvent(heard(9L, PacketKind.Text, atMs = t0 + 20_000))
        val after = c.drive(20L * sr, 6)
        assertNotEquals(chordBefore.rootStep, c.chord.rootStep)
        assertTrue(after.notes.count { it.voice == Voice.Bell } >= 3)
        assertTrue(before.notes.none { it.voice == Voice.Bell })
        val textures = after.controls.filterIsInstance<ControlEvent.SetTexture>()
        assertTrue(textures.isNotEmpty())
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
        assertTrue(bells.map { it.atFrame }.zipWithNext().all { (a, b) -> b >= a })
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
}
