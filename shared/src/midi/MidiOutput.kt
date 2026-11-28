package org.meshtastic.aether.midi

/** A MIDI output port. */
interface MidiOutput {
    val name: String

    /** Sends [data] when the monotonic clock reaches [atNanos], at once if that has passed. */
    fun send(data: ByteArray, atNanos: Long)

    /** Drops every message not yet sent. */
    fun cancel()

    /**
     * Sends what is due, drops the rest, ends every note the port sent and has not ended (a note-off the caller
     * handed over may be among those dropped), and releases the port.
     */
    fun close()
}

/** Whether Aether can play to MIDI outputs here; the settings screen shows its MIDI section only where it can. */
expect fun midiSupported(): Boolean

/** The names of the MIDI outputs this platform can open; empty where Aether has no MIDI output yet. */
expect fun midiOutputNames(): List<String>

/** The output whose name is [name], else the first whose name contains it ignoring case; null if none opens. */
expect fun openMidiOutput(name: String): MidiOutput?

/** Messages in send-time order; messages for one time keep the order they were added in. */
class TimedQueue {
    private class Item(val atNanos: Long, val data: ByteArray)

    private val items = ArrayList<Item>()

    val size: Int get() = items.size

    fun add(data: ByteArray, atNanos: Long) {
        var i = items.size
        while (i > 0 && items[i - 1].atNanos > atNanos) i--
        items.add(i, Item(atNanos, data))
    }

    /** The send time of the first message, or null when empty. */
    fun nextAt(): Long? = items.firstOrNull()?.atNanos

    /** Hands [emit] every message due by [now], in order, and removes them. */
    fun popDue(now: Long, emit: (ByteArray) -> Unit) {
        var n = 0
        while (n < items.size && items[n].atNanos <= now) emit(items[n++].data)
        items.subList(0, n).clear()
    }

    fun clear() = items.clear()
}
