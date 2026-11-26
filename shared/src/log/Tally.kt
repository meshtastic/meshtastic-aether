package org.meshtastic.ambient.log

import org.meshtastic.ambient.mesh.PacketKind

/** Counts packets heard by kind between summaries. */
class Tally {
    private val counts = LinkedHashMap<PacketKind, Int>()

    val total: Int get() = counts.values.sum()

    fun add(kind: PacketKind) {
        counts[kind] = (counts[kind] ?: 0) + 1
    }

    /** "Text=1 Position=3", kinds in the enum's order, zero counts omitted; "none" when empty. */
    fun describe(): String =
        PacketKind.entries.filter { (counts[it] ?: 0) > 0 }.joinToString(" ") { "${it.name}=${counts[it]}" }
            .ifEmpty { "none" }

    fun reset() = counts.clear()
}
