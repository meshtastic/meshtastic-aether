package org.meshtastic.aether.midi

import kotlin.test.Test
import kotlin.test.assertEquals

class TimedQueueTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    // The port's thread pops what is due; messages added out of time order (a bend placed after a later note-off)
    // must still go out in time order, and a note-off and the note-on that follows it at one time keep their order.
    @Test
    fun `messages leave in time order and keep their order at one time`() {
        val q = TimedQueue()
        q.add(bytes(0x80, 60, 64), 300)
        q.add(bytes(0x90, 60, 90), 300)
        q.add(bytes(0xE1, 0, 64), 100)
        q.add(bytes(0x91, 62, 90), 200)
        val out = ArrayList<Int>()
        q.popDue(250) { out += it[0].toInt() and 0xFF }
        assertEquals(listOf(0xE1, 0x91), out)
        assertEquals(300L, q.nextAt())
        q.popDue(300) { out += it[0].toInt() and 0xFF }
        assertEquals(listOf(0xE1, 0x91, 0x80, 0x90), out)
    }

    @Test
    fun `clearing drops every message not yet sent`() {
        val q = TimedQueue()
        q.add(bytes(0x90, 60, 90), 100)
        q.add(bytes(0x80, 60, 64), 200)
        q.clear()
        val out = ArrayList<ByteArray>()
        q.popDue(Long.MAX_VALUE) { out += it }
        assertEquals(0, out.size)
        assertEquals(null, q.nextAt())
    }
}
