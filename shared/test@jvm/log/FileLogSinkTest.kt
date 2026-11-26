package org.meshtastic.aether.log

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FileLogSinkTest {
    private fun dir() = createTempDirectory("ambientlog").toFile()

    @Test
    fun `lines are appended one per row and the parent directory is created`() {
        val file = File(dir(), "nested/aether.log")
        val sink = FileLogSink(file)
        sink.write(Level.Info, "first")
        sink.write(Level.Info, "second")
        assertEquals(listOf("first", "second"), file.readLines())
    }

    // Regression: an unattended install that never rotates fills the disk.
    @Test
    fun `a file over the limit at startup moves to dot one and a new file starts`() {
        val d = dir()
        val file = File(d, "aether.log")
        file.writeText("x".repeat(100))
        val sink = FileLogSink(file, maxBytes = 50)
        sink.write(Level.Info, "fresh")
        assertEquals("x".repeat(100), File(d, "aether.log.1").readText())
        assertEquals(listOf("fresh"), file.readLines())
    }

    @Test
    fun `a file under the limit is kept and appended to`() {
        val file = File(dir(), "aether.log")
        file.writeText("old\n")
        val sink = FileLogSink(file, maxBytes = 1_000)
        sink.write(Level.Info, "new")
        assertEquals(listOf("old", "new"), file.readLines())
        assertFalse(File(file.path + ".1").exists())
    }

    // Regression: a read-only log directory must not take the app down.
    @Test
    fun `an unwritable location is swallowed`() {
        val sink = FileLogSink(File("/proc/nope/aether.log"))
        sink.write(Level.Error, "still fine")
        sink.write(Level.Error, "and again")
    }
}
