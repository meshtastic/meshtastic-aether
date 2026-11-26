package org.meshtastic.ambient.music

import org.meshtastic.ambient.mesh.GeoPoint
import org.meshtastic.ambient.mesh.MeshEvent
import org.meshtastic.ambient.mesh.PacketKind
import org.meshtastic.ambient.mood.ParamValues
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The composer's per-event layer: each node attribute reaches the note it should, and nothing moves when it is absent. */
class ComposerTraitsTest {
    private val sr = 48_000
    private val t0 = 1_800_000_000_000L
    private val lookahead = sr / 2L
    private val step = sr / 4L

    private fun moment(frame: Long, minute: Int = 600) = LocalMoment(3, minute, t0 + frame * 1000 / sr)

    private fun heard(
        node: Long,
        kind: PacketKind = PacketKind.Position,
        atMs: Long = t0,
        rssi: Int? = null,
        mqtt: Boolean = false,
        payload: Int? = null,
        text: Int? = null,
        channel: Int? = null,
    ) = MeshEvent.PacketHeard(node, kind, null, rssi, null, mqtt, atMs, payloadBytes = payload, textLength = text, channel = channel)

    private fun Composer.drive(from: Long, seconds: Int, minute: Int = 600, into: MutableList<NoteEvent> = ArrayList()): MutableList<NoteEvent> {
        var f = from
        val end = from + seconds.toLong() * sr
        while (f < end) {
            into += advance(f, lookahead, moment(f, minute)).notes
            f += step
        }
        return into
    }

    private fun fresh(seed: Long = 1L): Composer = Composer(seed, sr).also { it.advance(0, lookahead, moment(0)) }

    /** Notes the packet from [node] sounded, excluding the MQTT shimmer layer. */
    private fun ornaments(notes: List<NoteEvent>, node: Long) = notes.filter { it.node == node && it.voice != Voice.Shimmer }.sortedBy { it.atFrame }

    private fun first(setup: Composer.() -> Unit = {}, event: MeshEvent.PacketHeard): NoteEvent {
        val c = fresh()
        c.setup()
        c.onEvent(event)
        return ornaments(c.drive(step, 3), event.from).first()
    }

    // Regression: a mapping that reacts to a missing attribute changes every note from a node that reports nothing.
    @Test
    fun `a packet with no attributes sounds exactly as before`() {
        val n = first(event = heard(77L))
        assertEquals(0.55f, n.velocity, 1e-6f)
        assertEquals(0.5f, n.reverbSend, 1e-6f)
        assertEquals(0.31f, n.brightness, 1e-6f)
        assertEquals(0f, n.pan)
        assertEquals((0.7 * sr).toLong(), n.durationFrames)
    }

    // Base send for unknown hops is 0.1 + 0.1 * 4 = 0.5 and base Position brightness is 0.55 - 0.06 * 4 = 0.31.
    @Test
    fun `a weak rssi adds a tenth of reverb and dulls a tenth and a strong one does the opposite`() {
        val weak = first(event = heard(5L, rssi = -120))
        val strong = first(event = heard(5L, rssi = -30))
        assertEquals(0.6f, weak.reverbSend, 1e-6f)
        assertEquals(0.21f, weak.brightness, 1e-6f)
        assertEquals(0.4f, strong.reverbSend, 1e-6f)
        assertEquals(0.41f, strong.brightness, 1e-6f)
    }

    // Base velocity for unknown SNR is 0.2 + 0.7 * 0.5 = 0.55; the payload trim is 0.9 empty and 1.1 full.
    @Test
    fun `payload size trims the velocity of the note`() {
        assertEquals(0.605f, first(event = heard(5L, payload = 237)).velocity, 1e-5f)
        assertEquals(0.495f, first(event = heard(5L, payload = 0)).velocity, 1e-5f)
    }

    @Test
    fun `the channel index offsets the pan`() {
        assertEquals(0.16f, first(event = heard(5L, channel = 3)).pan, 1e-6f)
        assertEquals(-0.08f, first(event = heard(5L, channel = 2)).pan, 1e-6f)
        assertEquals(0f, first(event = heard(5L, channel = 0)).pan, 1e-6f)
    }

    @Test
    fun `a node's uptime lengthens its note and thirty days is one and a half times`() {
        val old = first({ onEvent(MeshEvent.Health(5L, null, null, 720L * 3600, t0)) }, heard(5L))
        assertTrue(abs(old.durationFrames - 1.05 * sr) <= 1.0, "${old.durationFrames}")
        val young = first({ onEvent(MeshEvent.Health(5L, null, null, 0L, t0)) }, heard(5L))
        assertEquals((0.7 * sr).toLong(), young.durationFrames)
    }

    @Test
    fun `external power or a full cell brightens the note and a low cell dims it`() {
        assertEquals(0.41f, first({ onEvent(MeshEvent.Health(5L, 101, null, null, t0)) }, heard(5L)).brightness, 1e-6f)
        assertEquals(0.41f, first({ onEvent(MeshEvent.Health(5L, null, 4.2f, null, t0)) }, heard(5L)).brightness, 1e-6f)
        assertEquals(0.21f, first({ onEvent(MeshEvent.Health(5L, null, 3.3f, null, t0)) }, heard(5L)).brightness, 1e-6f)
    }

    // Regression: the wavering twin once played for every battery level, or at the wrong ratio, or the same level as its note.
    @Test
    fun `a low battery adds a twin note a few cents sharp that beats against the first`() {
        val c = fresh()
        c.onEvent(MeshEvent.Health(5L, 10, null, null, t0))
        c.onEvent(heard(5L))
        val notes = ornaments(c.drive(step, 3), 5L)
        val at = notes.first().atFrame
        val pair = notes.filter { it.atFrame == at }.sortedByDescending { it.velocity }
        assertEquals(2, pair.size, "no twin")
        assertEquals(1.006956, pair[1].pitchHz / pair[0].pitchHz, 1e-5)
        assertEquals(0.6f, pair[1].velocity / pair[0].velocity, 1e-4f)
    }

    @Test
    fun `a healthy or externally powered battery adds no twin`() {
        for (pct in listOf(50, 90, 100, 101)) {
            val c = fresh()
            c.onEvent(MeshEvent.Health(5L, pct, null, null, t0))
            c.onEvent(heard(5L))
            val notes = ornaments(c.drive(step, 3), 5L)
            assertEquals(1, notes.count { it.atFrame == notes.first().atFrame }, "battery $pct")
        }
    }

    private fun pitchOf(node: Long, altitude: Int?, referenceAltitude: Int?): Double {
        val c = fresh()
        if (altitude != null) c.onEvent(MeshEvent.NodeSeen(node, null, GeoPoint(45.0, 10.0, altitude), false, t0))
        if (referenceAltitude != null) c.onEvent(MeshEvent.NodeSeen(9999L, null, GeoPoint(45.0, 10.0, referenceAltitude), false, t0))
        c.onEvent(heard(node))
        return ornaments(c.drive(step, 3), node).first().pitchHz
    }

    // The mean of the two altitudes is 500 m, so 1000 m is +500 m (an octave up) and 0 m is -500 m (an octave down), clamped to the 0..2 octave band.
    @Test
    fun `high ground rings an octave up and low ground an octave down inside the band`() {
        val low = (1L..60L).first { Ornaments.band(it) == 0 }
        val mid = (1L..60L).first { Ornaments.band(it) == 1 }
        val top = (1L..60L).first { Ornaments.band(it) == 2 }
        assertEquals(2.0, pitchOf(low, 1000, 0) / pitchOf(low, null, null), 1e-9)
        assertEquals(1.0, pitchOf(top, 1000, 0) / pitchOf(top, null, null), 1e-9, "clamped at the top of the band")
        assertEquals(0.5, pitchOf(mid, 0, 1000) / pitchOf(mid, null, null), 1e-9)
        assertEquals(1.0, pitchOf(low, 0, 1000) / pitchOf(low, null, null), 1e-9, "clamped at the bottom of the band")
        assertEquals(1.0, pitchOf(mid, 520, 480) / pitchOf(mid, null, null), 1e-9, "level ground")
    }

    @Test
    fun `an MQTT packet carries a shimmer layer half as loud on its first note and a plain packet does not`() {
        fun go(mqtt: Boolean): List<NoteEvent> {
            val c = fresh()
            c.onEvent(heard(5L, mqtt = mqtt))
            return c.drive(step, 3).filter { it.node == 5L }
        }
        val with = go(true)
        val shimmer = with.single { it.voice == Voice.Shimmer }
        val firstNote = with.filter { it.voice != Voice.Shimmer }.minBy { it.atFrame }
        assertEquals(firstNote.atFrame, shimmer.atFrame)
        assertEquals(firstNote.pitchHz, shimmer.pitchHz, 1e-9)
        assertEquals(0.5f, shimmer.velocity / firstNote.velocity, 1e-5f)
        assertTrue(go(false).none { it.voice == Voice.Shimmer })
    }

    @Test
    fun `text length sets the number of notes in the phrase and the sixth note is an octave over the first`() {
        fun phrase(len: Int?): List<NoteEvent> {
            val c = fresh()
            c.onEvent(heard(9L, PacketKind.Text, text = len))
            return ornaments(c.drive(step, 5), 9L)
        }
        assertEquals(4, phrase(null).size)
        assertEquals(3, phrase(30).size)
        assertEquals(4, phrase(60).size)
        assertEquals(5, phrase(120).size)
        val long = phrase(200)
        assertEquals(6, long.size)
        assertEquals(2.0, long[5].pitchHz / long[0].pitchHz, 1e-9)
    }

    private fun voicesFor(role: String?, hardware: String?, node: Long = 40L, kind: PacketKind = PacketKind.Position): Set<Voice> {
        val c = fresh()
        c.onEvent(MeshEvent.NodeSeen(node, null, null, false, t0, role = role, hardware = hardware))
        c.onEvent(heard(node, kind))
        return ornaments(c.drive(step, 3), node).map { it.voice }.toSet()
    }

    @Test
    fun `a packet plays a voice from its role's family and an unknown node one from the client family`() {
        val hardware = listOf("HELTEC_V3", "TBEAM", "TLORA_V2", "RAK4631", "T_ECHO", "STATION_G2", "HELTEC_V4", "TLORA_T3_S3", "LILYGO_TBEAM_S3_CORE", "NANO_G1")
        val router = hardware.flatMap { voicesFor("ROUTER", it) }.toSet()
        assertTrue(router.isNotEmpty() && router.all { it in setOf(Voice.Sub, Voice.Drone, Voice.Gong) }, "router played $router")
        assertTrue(hardware.flatMap { voicesFor("SENSOR", it) }.all { it in setOf(Voice.Glass, Voice.Kalimba, Voice.Bell) })
        assertTrue(hardware.flatMap { voicesFor("TRACKER", it) }.all { it in setOf(Voice.Harp, Voice.Pluck, Voice.Kalimba) })
        assertTrue(hardware.flatMap { voicesFor("REPEATER", it) }.all { it in setOf(Voice.Vibes, Voice.Glass) })
        val client = setOf(Voice.Marimba, Voice.Bell, Voice.Mallet, Voice.Bowed)
        assertTrue(hardware.flatMap { voicesFor(null, it) }.all { it in client })
        val unknown = (1L..40L).flatMap { voicesFor(null, null, it) }.toSet()
        assertTrue(unknown.all { it in client })
        assertTrue(unknown.size > 1, "every unknown node played the same voice")
    }

    @Test
    fun `the hardware model changes the voice inside the family`() {
        val played = listOf("HELTEC_V3", "TBEAM", "TLORA_V2", "RAK4631", "T_ECHO", "STATION_G2", "HELTEC_V4", "TLORA_T3_S3")
            .map { voicesFor("ROUTER", it).single() }.toSet()
        assertTrue(played.size >= 2, "all hardware sounded as $played")
    }

    @Test
    fun `an encrypted packet is the breath whatever the role`() {
        assertEquals(setOf(Voice.Breath), voicesFor("ROUTER", "TBEAM", kind = PacketKind.Encrypted))
    }

    // Regression: the battery twin and the MQTT shimmer take slots too, so a burst of both must still obey the cap.
    @Test
    fun `a burst of low battery MQTT packets never exceeds the slot cap`() {
        val c = fresh(3L)
        for (i in 0 until 200) {
            c.onEvent(MeshEvent.Health(100L + i, 5, null, null, t0))
            c.onEvent(heard(100L + i, atMs = t0 + i * 5L, mqtt = true))
        }
        val notes = c.drive(step, 12).filter { it.node != null && it.node!! in 100L..299L }
        assertTrue(notes.isNotEmpty())
        val edges = notes.flatMap { listOf(it.atFrame to 1, it.atFrame + it.durationFrames to -1) }.sortedWith(compareBy({ it.first }, { it.second }))
        var n = 0
        var best = 0
        for ((_, d) in edges) {
            n += d
            if (n > best) best = n
        }
        assertTrue(best <= Ornaments.SLOT_CAP, "$best sounding at once")
        assertTrue(notes.any { it.voice == Voice.Shimmer } && notes.any { it.pitchHz > 0 })
    }

    @Test
    fun `the tempo band divides the gaps of a node's motif`() {
        fun gapFrames(speed: Double): Long {
            val c = fresh()
            c.paramOverride = ParamValues(phraseSpeed = speed)
            c.onEvent(heard(21L))
            val notes = ornaments(c.drive(step, 5), 21L)
            return notes[1].atFrame - notes[0].atFrame
        }
        val gap = Motifs.of(21L).gaps[1]
        for (speed in listOf(0.5, 0.75, 1.0, 1.5)) {
            assertTrue(abs(gapFrames(speed) - gap / speed * sr) <= 1.0, "speed $speed gap $gap gave ${gapFrames(speed)}")
        }
    }

    @Test
    fun `the composer's params are neutral before any data and carry the mood's clock after an advance`() {
        val c = Composer(1L, sr)
        assertEquals(ParamValues.NEUTRAL, c.params())
        for (i in 0 until 30) c.onEvent(heard(1L + i % 3, atMs = t0 + i * 1000L))
        c.advance(30L * sr, lookahead, moment(30L * sr))
        val snap = c.moodSnapshot()
        assertEquals(t0 + 30_000L, snap.nowMillis)
        assertTrue(snap.packetsPerMinute > 0.0)
        assertEquals(snap.params, c.params())
    }

    // Regression: the scale must change only when a new chord starts, so the notes sounding under a chord keep its mode.
    @Test
    fun `a lean change takes hold at the next chord and never inside the chord in force`() {
        val c = Composer(4L, sr)
        c.paramOverride = ParamValues(modeLean = 0.0)
        val minute = 900 // 15:00 on a Wednesday: brightness 0.725, the clock's base is Mixolydian
        c.drive(0, 60, minute)
        assertEquals(Mode.Mixolydian, c.chord.mode)
        c.paramOverride = ParamValues(modeLean = 1.0)
        val inForce = c.chord
        var f = 60L * sr + step
        var guard = 0
        while (c.chord === inForce && guard++ < 4000) {
            c.advance(f, lookahead, moment(f, minute))
            f += step
            if (c.chord === inForce) assertEquals(Mode.Mixolydian, c.chord.mode)
        }
        assertNotEquals(inForce, c.chord, "the chord never changed")
        assertEquals(Mode.Ionian, c.chord.mode, "the new chord took the leaned mode")
    }

    @Test
    fun `a high pentatonic bias moves the chords into the matching pentatonic and back`() {
        val c = Composer(5L, sr)
        val minute = 900
        c.paramOverride = ParamValues(scalePentatonicBias = 1.0)
        c.drive(0, 600, minute)
        assertEquals(Mode.MajorPentatonic, c.chord.mode)
        c.paramOverride = ParamValues(scalePentatonicBias = 0.0)
        c.drive(600L * sr + step, 600, minute)
        assertEquals(Mode.Mixolydian, c.chord.mode)
    }
}
