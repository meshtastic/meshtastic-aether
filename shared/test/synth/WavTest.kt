package org.meshtastic.aether.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WavTest {
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    private fun s16(b: ByteArray, o: Int) = u16(b, o).toShort().toInt()

    private fun tag(b: ByteArray, o: Int) = b.decodeToString(o, o + 4)

    // Regression: a header field written in the wrong byte order or width makes players refuse or mis-speed the file.
    @Test
    fun `the header carries RIFF WAVE and the PCM format fields from the spec`() {
        val wav = wavBytes(FloatArray(2 * 100), 44100)
        assertEquals("RIFF", tag(wav, 0))
        assertEquals("WAVE", tag(wav, 8))
        assertEquals("fmt ", tag(wav, 12))
        assertEquals(16, u32(wav, 16))
        assertEquals(1, u16(wav, 20))
        assertEquals(2, u16(wav, 22))
        assertEquals(44100, u32(wav, 24))
        assertEquals(44100 * 2 * 2, u32(wav, 28))
        assertEquals(4, u16(wav, 32))
        assertEquals(16, u16(wav, 34))
        assertEquals("data", tag(wav, 36))
    }

    @Test
    fun `the size fields match the amount of audio`() {
        val wav = wavBytes(FloatArray(2 * 100), 48000)
        assertEquals(44 + 400, wav.size)
        assertEquals(400, u32(wav, 40))
        assertEquals(36 + 400, u32(wav, 4))
    }

    @Test
    fun `samples are little-endian 16-bit and clamp to the range`() {
        val wav = wavBytes(floatArrayOf(0f, 0f, 1.5f, -1.5f, 0.5f, -0.5f), 8000)
        assertEquals(0, s16(wav, 44))
        assertEquals(32767, s16(wav, 48))
        assertEquals(-32767, s16(wav, 50))
        assertTrue(kotlin.math.abs(s16(wav, 52) - 16384) <= 1)
        assertTrue(kotlin.math.abs(s16(wav, 54) + 16384) <= 1)
    }

    @Test
    fun `left and right stay interleaved in order`() {
        val wav = wavBytes(floatArrayOf(0.25f, -0.25f), 8000)
        assertTrue(s16(wav, 44) > 0)
        assertTrue(s16(wav, 46) < 0)
    }

    @Test
    fun `an odd trailing sample is dropped and NaN becomes silence`() {
        val wav = wavBytes(floatArrayOf(Float.NaN, 0.1f, 0.9f), 8000)
        assertEquals(44 + 4, wav.size)
        assertEquals(0, s16(wav, 44))
    }
}
