package org.meshtastic.ambient.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaletteTest {
    private val hardware = listOf(
        "HELTEC_V3", "TBEAM", "TLORA_V2", "RAK4631", "T_ECHO", "STATION_G2", "HELTEC_V4", "TLORA_T3_S3", "LILYGO_TBEAM_S3_CORE",
        "NANO_G1", "WIO_TRACKER_L1", "SEEED_XIAO_S3", "RP2040_LORA", "HELTEC_WIRELESS_PAPER", "T_DECK",
    )

    private fun voices(role: String?, node: Long = 1L) = hardware.map { Palette.voiceFor(role, it, node) }.toSet()

    // The family lists below are the design's Palettes section; a role mapped to the wrong family shows here.
    @Test
    fun `router and router late play only the low voices`() {
        for (role in listOf("ROUTER", "ROUTER_LATE")) assertEquals(setOf(Voice.Sub, Voice.Drone, Voice.Gong), voices(role), role)
    }

    @Test
    fun `a repeater plays vibes or glass and nothing else`() {
        assertEquals(setOf(Voice.Vibes, Voice.Glass), voices("REPEATER"))
    }

    @Test
    fun `trackers and TAK nodes play harp pluck or kalimba`() {
        for (role in listOf("TRACKER", "TAK", "TAK_TRACKER")) assertEquals(setOf(Voice.Harp, Voice.Pluck, Voice.Kalimba), voices(role), role)
    }

    @Test
    fun `a sensor plays glass kalimba or bell`() {
        assertEquals(setOf(Voice.Glass, Voice.Kalimba, Voice.Bell), voices("SENSOR"))
    }

    @Test
    fun `client roles and an unknown role play marimba bell mallet or bowed`() {
        val client = setOf(Voice.Marimba, Voice.Bell, Voice.Mallet, Voice.Bowed)
        for (role in listOf("CLIENT", "CLIENT_MUTE", "CLIENT_BASE", "CLIENT_HIDDEN", null, "", "SOMETHING_NEW")) {
            assertEquals(client, voices(role), "role $role")
        }
    }

    // Regression: a voice that moved with the node number would change every time a node was re-seen under another id.
    @Test
    fun `the same role and hardware always give the same voice whatever the node`() {
        for (hw in hardware) for (role in listOf("ROUTER", "SENSOR", null)) {
            val first = Palette.voiceFor(role, hw, 1L)
            for (node in listOf(2L, 99L, 0xFFFFFFFFL, 123456789L)) assertEquals(first, Palette.voiceFor(role, hw, node), "$role $hw")
        }
    }

    @Test
    fun `role spelling in lower case or with spaces picks the same family`() {
        assertEquals(Palette.voiceFor("ROUTER", "TBEAM", 1L), Palette.voiceFor(" router ", "tbeam", 1L))
    }

    // Regression: all nodes with no hardware reported sharing one voice made an unknown mesh sound like one instrument.
    @Test
    fun `with no hardware model the node number spreads nodes over the whole family`() {
        val family = Family.Client.voices.toSet()
        for (hw in listOf(null, "", "UNSET")) {
            val seen = (1L..200L).map { Palette.voiceFor("CLIENT", hw, it) }.toSet()
            assertEquals(family, seen, "hardware $hw")
        }
    }

    @Test
    fun `the palette never hands out the pad shimmer or the pitchless breath`() {
        val everything = Family.entries.flatMap { it.voices }
        assertTrue(everything.none { it == Voice.Pad || it == Voice.Shimmer || it == Voice.Breath })
    }

    // The design asks for about fifteen timbres; each name below is on its list.
    @Test
    fun `the voice enum holds every instrument the design names`() {
        val named = listOf(
            "Bell", "Marimba", "Vibes", "Kalimba", "Glass", "Harp", "Pluck", "Mallet", "Gong", "Bowed", "Choir", "Pad", "Drone", "Sub",
            "Shimmer", "Breath",
        )
        assertEquals(named.toSet(), Voice.entries.map { it.name }.toSet())
    }
}
