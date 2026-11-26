package org.meshtastic.ambient.log

import org.meshtastic.ambient.mesh.PacketKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogTest {
    private val saved = Log.sinks
    private val savedLevel = Log.minLevel

    @AfterTest
    fun restore() {
        Log.sinks = saved
        Log.minLevel = savedLevel
    }

    // Regression: a line without level and tag cannot be grepped from a long file.
    @Test
    fun `a line carries timestamp padded level tag and message`() {
        val line = Log.format("2026-11-25T19:18:03Z", Level.Warn, "link", "lost", null)
        assertEquals("2026-11-25T19:18:03Z WARN  link: lost", line)
    }

    @Test
    fun `an error appends its type and message`() {
        val line = Log.format("T", Level.Error, "tcp", "connect failed", IllegalStateException("refused"))
        assertEquals("T ERROR tcp: connect failed (IllegalStateException: refused)", line)
    }

    // Regression: debug chatter reaching the file at the default level fills the disk on an unattended install.
    @Test
    fun `lines below the minimum level reach no sink`() {
        val seen = mutableListOf<String>()
        Log.sinks = listOf(LogSink { _, line -> seen += line })
        Log.minLevel = Level.Info
        Log.d("t", "quiet")
        Log.i("t", "loud")
        assertEquals(1, seen.size)
        assertTrue(seen.single().endsWith("INFO  t: loud"))
    }

    @Test
    fun `every sink receives each line`() {
        val a = mutableListOf<String>()
        val b = mutableListOf<String>()
        Log.sinks = listOf(LogSink { _, l -> a += l }, LogSink { _, l -> b += l })
        Log.i("t", "x")
        assertEquals(1, a.size)
        assertEquals(a, b)
    }

    // Regression: a summary that keeps last minute's counts double-reports packets.
    @Test
    fun `a tally describes kinds in enum order and resets to none`() {
        val tally = Tally()
        tally.add(PacketKind.Position)
        tally.add(PacketKind.Text)
        tally.add(PacketKind.Position)
        assertEquals(3, tally.total)
        assertEquals("Text=1 Position=2", tally.describe())
        tally.reset()
        assertEquals(0, tally.total)
        assertEquals("none", tally.describe())
    }
}
