package org.meshtastic.ambient.music

import org.meshtastic.ambient.mesh.GeoPoint
import org.meshtastic.ambient.mesh.PacketKind
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrnamentsTest {
    private val root = 146.83
    private val ionianI = Chord(Mode.Ionian, 0, listOf(7, 9, 11, 13))
    private val ionianIV = Chord(Mode.Ionian, 3, listOf(7, 9, 10, 12))

    /** Just ratios of the chord's root, third, fifth, seventh and ninth above the key root, from the 5-limit table. */
    private val ratiosI = listOf(1.0, 5.0 / 4, 3.0 / 2, 15.0 / 8, 9.0 / 8)
    private val ratiosIV = listOf(4.0 / 3, 5.0 / 3, 1.0, 5.0 / 4, 3.0 / 2)

    private fun octave(ratio: Double) = floor(ln(ratio) / ln(2.0) + 1e-9).toInt()

    private fun folded(ratio: Double) = ratio / 2.0.pow(octave(ratio))

    @Test
    fun `ornament pitch is the node's chord tone in the node's band`() {
        for (node in listOf(1L, 2L, 3L, 0xABCDEFL, 987654321L, -5L)) {
            val role = Ornaments.role(node)
            val ratio = Ornaments.pitchHz(node, ionianI, root) / root
            assertEquals(ratiosI[role], folded(ratio), 1e-9, "node $node role $role")
            assertEquals(octave(ratio), Ornaments.band(node))
            assertTrue(octave(ratio) in 0..2)
        }
    }

    @Test
    fun `a node keeps its role and band when the chord changes`() {
        for (node in 1L..60L) {
            val a = Ornaments.pitchHz(node, ionianI, root) / root
            val b = Ornaments.pitchHz(node, ionianIV, root) / root
            val role = Ornaments.role(node)
            assertEquals(ratiosI[role], folded(a), 1e-9)
            assertEquals(ratiosIV[role], folded(b), 1e-9)
            assertEquals(octave(a), octave(b))
        }
    }

    @Test
    fun `node numbers spread over all five roles and all three bands`() {
        assertEquals(setOf(0, 1, 2, 3, 4), (1L..200L).map { Ornaments.role(it) }.toSet())
        assertEquals(setOf(0, 1, 2), (1L..200L).map { Ornaments.band(it) }.toSet())
    }

    @Test
    fun `unknown hops sit exactly between direct and the farthest`() {
        val direct = Ornaments.reverbSendFor(0, false)
        val far = Ornaments.reverbSendFor(Ornaments.MAX_HOPS, false)
        assertEquals((direct + far) / 2, Ornaments.reverbSendFor(null, false), 1e-6f)
        assertTrue(direct < Ornaments.reverbSendFor(null, false))
    }

    @Test
    fun `each hop adds reverb send and shortens brightness and beyond the cap changes nothing`() {
        val sends = (0..Ornaments.MAX_HOPS).map { Ornaments.reverbSendFor(it, false) }
        sends.zipWithNext().forEach { (a, b) -> assertTrue(b > a) }
        assertEquals(sends.last(), Ornaments.reverbSendFor(40, false))
        val dull = (0..4).map { Ornaments.brightnessFor(PacketKind.Position, it, false) }
        dull.zipWithNext().forEach { (a, b) -> assertTrue(b < a) }
    }

    @Test
    fun `a packet via MQTT sounds one hop further than the same packet direct`() {
        assertEquals(Ornaments.reverbSendFor(3, false), Ornaments.reverbSendFor(2, true))
        assertTrue(Ornaments.echoesFor(0, false) < Ornaments.echoesFor(null, false))
    }

    @Test
    fun `echo repeats grow with distance from none when direct to two at the farthest`() {
        assertEquals(0, Ornaments.echoesFor(0, false))
        assertEquals(1, Ornaments.echoesFor(null, false))
        assertEquals(2, Ornaments.echoesFor(8, false))
        assertEquals(2, Ornaments.echoesFor(40, false))
    }

    @Test
    fun `unknown SNR gives the middle velocity and the range is limited`() {
        val lo = Ornaments.velocityFor(-20f)
        val hi = Ornaments.velocityFor(10f)
        assertEquals(0.25f, lo, 1e-6f)
        assertEquals(0.6f, hi, 1e-6f)
        assertTrue(20 * kotlin.math.log10(hi / lo) < 9f, "the SNR span is under 9 dB of loudness")
        assertEquals((lo + hi) / 2, Ornaments.velocityFor(null), 1e-6f)
        assertEquals(lo, Ornaments.velocityFor(-60f))
        assertEquals(hi, Ornaments.velocityFor(40f))
        assertTrue(lo > 0f && hi < 1f)
        assertTrue(Ornaments.velocityFor(-5f) < Ornaments.velocityFor(5f))
    }

    @Test
    fun `pan follows the bearing east right west left and centre when unknown`() {
        val here = GeoPoint(45.0, 10.0)
        assertEquals(0.7f, Ornaments.panFor(GeoPoint(45.0, 10.5), here), 1e-6f)
        assertEquals(-0.7f, Ornaments.panFor(GeoPoint(45.0, 9.5), here), 1e-6f)
        assertEquals(0f, Ornaments.panFor(GeoPoint(45.5, 10.0), here), 1e-6f)
        assertEquals(0f, Ornaments.panFor(null, here))
        assertEquals(0f, Ornaments.panFor(here, null))
        assertEquals(0f, Ornaments.panFor(here, here))
    }

    @Test
    fun `articulation changes length and brightness but stays in the bell mallet pluck family`() {
        val pitched = PacketKind.entries - PacketKind.Encrypted
        pitched.forEach { assertTrue(Ornaments.articulation(it).voice in listOf(Voice.Bell, Voice.Mallet, Voice.Pluck)) }
        assertEquals(Voice.Breath, Ornaments.articulation(PacketKind.Encrypted).voice)
        assertTrue(pitched.map { Ornaments.articulation(it).seconds }.toSet().size > 3)
        assertTrue(PacketKind.entries.all { Ornaments.articulation(it).seconds <= 1.5 })
    }

    @Test
    fun `fifteen simultaneous requests get slots and the sixteenth is dropped`() {
        val slots = SlotAllocator(Ornaments.SLOT_CAP)
        repeat(Ornaments.SLOT_CAP) { assertEquals(0L, slots.allocate(0L, 1000L, 0L)) }
        assertNull(slots.allocate(0L, 1000L, 0L))
    }

    @Test
    fun `a request waits for a slot to free but never past its deadline`() {
        val slots = SlotAllocator(2)
        slots.allocate(0L, 500L, 0L)
        slots.allocate(0L, 800L, 0L)
        assertEquals(500L, slots.allocate(0L, 100L, 600L))
        assertNull(slots.allocate(0L, 100L, 599L))
    }

    @Test
    fun `a request that would start after its deadline is dropped even with a free slot`() {
        assertNull(SlotAllocator(4).allocate(2000L, 100L, 1999L))
    }

    @Test
    fun `a slot is reusable the instant its note ends`() {
        val slots = SlotAllocator(1)
        assertEquals(0L, slots.allocate(0L, 1000L, 0L))
        assertEquals(1000L, slots.allocate(1000L, 1000L, 1000L))
        assertNotEquals(500L, slots.allocate(500L, 10L, 5000L))
    }
}
