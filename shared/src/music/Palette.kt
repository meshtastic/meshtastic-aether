package org.meshtastic.ambient.music

/** Instrument families: a node's role picks one, its hardware model picks the voice inside it. */
enum class Family(val voices: List<Voice>) {
    /** Router and router-late: the low, sustained voices. */
    Low(listOf(Voice.Sub, Voice.Drone, Voice.Gong)),

    /** Repeater: struck metal and glass. */
    Ringing(listOf(Voice.Vibes, Voice.Glass)),

    /** Tracker and TAK: plucked and thumbed strings. */
    Plucked(listOf(Voice.Harp, Voice.Pluck, Voice.Kalimba)),

    /** Sensor: clear, small timbres. */
    Sensor(listOf(Voice.Glass, Voice.Kalimba, Voice.Bell)),

    /** Client, client-mute, client-base and any unknown role. */
    Client(listOf(Voice.Marimba, Voice.Bell, Voice.Mallet, Voice.Bowed)),
}

/** Which timbre a node plays: role (as the proto spells it) picks the [Family], the hardware model hash picks the voice. */
object Palette {
    fun family(role: String?): Family = when (role?.trim()?.uppercase()) {
        "ROUTER", "ROUTER_LATE" -> Family.Low
        "REPEATER" -> Family.Ringing
        "TRACKER", "TAK", "TAK_TRACKER" -> Family.Plucked
        "SENSOR" -> Family.Sensor
        else -> Family.Client
    }

    /**
     * The voice for a packet from [node]: the same (role, hardware, node) always gives the same voice. With no hardware
     * model (or UNSET) the node number's hash picks inside the family.
     */
    fun voiceFor(role: String?, hardware: String?, node: Long): Voice {
        val voices = family(role).voices
        val key = hardware?.trim()?.takeIf { it.isNotEmpty() && !it.equals("UNSET", ignoreCase = true) }
            ?.let(::hashOf) ?: Rng.mix(node)
        return voices[(key ushr 1).mod(voices.size.toLong()).toInt()]
    }

    /** FNV-1a over the upper-cased name, then mixed, so case does not matter and neighbors spread. */
    private fun hashOf(name: String): Long {
        var h = -0x340d631b7bdddcdbL // FNV offset basis 0xCBF29CE484222325
        for (c in name.uppercase()) h = (h xor c.code.toLong()) * 0x100000001B3L
        return Rng.mix(h)
    }
}
