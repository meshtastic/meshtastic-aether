package org.meshtastic.aether.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmRecorderTest {
    @Test
    fun `the recorded file is a wav whose sizes match the samples it holds and whose bytes are the blocks in order`() {
        val dir = kotlin.io.path.createTempDirectory("aether-rec").toFile()
        val file = File(dir, "out.wav")
        val rec = PcmRecorder(file, 48_000).also(PcmRecorder::start)
        val a = ByteArray(8) { it.toByte() }
        val b = ByteArray(8) { (100 + it).toByte() }
        rec.offer(a, a.size)
        rec.offer(b, b.size)
        rec.close()
        val bytes = file.readBytes()
        val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(36 + 16, h.getInt(4))
        assertEquals(48_000, h.getInt(24))
        assertEquals(16, h.getInt(40))
        assertEquals(44 + 16, bytes.size)
        assertContentEquals(a + b, bytes.copyOfRange(44, 60))
        assertTrue(File(file.path + ".start").readText().toLong() > 0)
        dir.deleteRecursively()
    }
}
