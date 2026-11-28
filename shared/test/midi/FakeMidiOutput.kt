package org.meshtastic.aether.midi

/**
 * A port whose sending thread runs only when told: [deliver] sends what is due by [now] to a receiver that tracks the
 * sounding keys, the way `JvmMidiOutput` does, and [close] sends what is due and drops the rest.
 */
internal class FakeMidiOutput : MidiOutput {
    override val name = "fake"
    val queue = TimedQueue()
    val sent = ArrayList<Pair<Long, ByteArray>>()
    val stamped = ArrayList<Pair<Long, ByteArray>>()
    val sounding = HashSet<Pair<Int, Int>>()
    var now = Long.MIN_VALUE
    var closed = false
    var cancels = 0

    override fun send(data: ByteArray, atNanos: Long) {
        check(!closed) { "sent to a closed port" }
        stamped += atNanos to data
        queue.add(data, atNanos)
    }

    fun deliver() = queue.popDue(now) { d ->
        sent += now to d
        val status = d[0].toInt() and 0xF0
        val key = (d[0].toInt() and 0x0F) to d[1].toInt()
        if (status == 0x90 && d[2] > 0) sounding += key else if (status == 0x80) sounding -= key
    }

    override fun cancel() {
        cancels++
        queue.clear()
    }

    override fun close() {
        deliver()
        queue.clear()
        closed = true
    }
}
