package org.meshtastic.aether.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockPumpTest {
    private fun constant(v: Float, calls: MutableList<Int>? = null) = BlockRenderer { out, frames ->
        calls?.add(frames)
        for (i in 0 until frames * 2) out[i] = v
    }

    @Test
    fun `samples beyond full scale clamp to the 16-bit range instead of wrapping`() {
        val out = ShortArray(4)
        BlockPump(constant(3.5f)).renderPcm16(out, 2)
        assertTrue(out.all { it == Short.MAX_VALUE }, out.toList().toString())
        BlockPump(constant(-9f)).renderPcm16(out, 2)
        assertTrue(out.all { it.toInt() == -32767 }, out.toList().toString())
    }

    @Test
    fun `full scale float maps to 32767 and zero stays silent`() {
        val out = ShortArray(2)
        BlockPump(constant(1f)).renderPcm16(out, 1)
        assertEquals(32767, out[0].toInt())
        BlockPump(constant(0f)).renderPcm16(out, 1)
        assertEquals(0, out[0].toInt())
    }

    @Test
    fun `a NaN sample becomes silence rather than a click`() {
        val out = ShortArray(2) { 7 }
        BlockPump(constant(Float.NaN)).renderPcm16(out, 1)
        assertEquals(0, out[0].toInt())
    }

    @Test
    fun `a request larger than one block renders exactly the frames asked for`() {
        val calls = mutableListOf<Int>()
        val frames = 1300
        val out = ShortArray(frames * 2 + 8) { 99 }
        BlockPump(constant(0.25f, calls), blockFrames = 512).renderPcm16(out, frames)
        assertEquals(frames, calls.sum())
        assertTrue(calls.all { it in 1..512 })
        assertEquals(8192, out[frames * 2 - 1].toInt())
        assertEquals(99, out[frames * 2].toInt(), "wrote past the requested frames")
    }

    @Test
    fun `chunk boundaries keep stereo samples in order`() {
        var n = 0
        val ramp = BlockRenderer { out, frames ->
            for (i in 0 until frames) {
                out[i * 2] = (n) / 1000f
                out[i * 2 + 1] = -(n) / 1000f
                n++
            }
        }
        val out = ShortArray(10)
        BlockPump(ramp, blockFrames = 2).renderPcm16(out, 5)
        for (f in 0 until 5) {
            assertEquals((f / 1000f * 32767f).let { kotlin.math.round(it).toInt() }, out[f * 2].toInt())
            assertEquals(-(f / 1000f * 32767f).let { kotlin.math.round(it).toInt() }, out[f * 2 + 1].toInt())
        }
    }

    @Test
    fun `byte output is little-endian 16-bit`() {
        val out = ByteArray(4)
        // 0.25 * 32767 = 8191.75 -> 8192 = 0x2000: low byte 0x00, high byte 0x20
        BlockPump(constant(0.25f)).renderPcm16Bytes(out, 1)
        assertEquals(listOf<Byte>(0x00, 0x20, 0x00, 0x20), out.toList())
        // -0.25 -> -8192 = 0xE000
        BlockPump(constant(-0.25f)).renderPcm16Bytes(out, 1)
        assertEquals(listOf<Byte>(0x00, 0xE0.toByte(), 0x00, 0xE0.toByte()), out.toList())
    }

    @Test
    fun `float output passes samples through untouched across blocks`() {
        var n = 0
        val ramp = BlockRenderer { out, frames -> for (i in 0 until frames * 2) out[i] = (n++).toFloat() }
        val out = FloatArray(14)
        BlockPump(ramp, blockFrames = 3).renderFloat(out, 7)
        assertEquals((0 until 14).map { it.toFloat() }, out.toList())
    }

    @Test
    fun `a 32-bit head position that wraps keeps counting upward`() {
        val c = WrapSafeFrameCounter()
        assertEquals(Int.MAX_VALUE - 10L, c.update(Int.MAX_VALUE - 10))
        // head advances 16 frames and wraps to negative
        assertEquals(Int.MAX_VALUE - 10L + 16L, c.update(Int.MIN_VALUE + 5))
        assertEquals(Int.MAX_VALUE - 10L + 16L + 100L, c.update(Int.MIN_VALUE + 105))
    }

    @Test
    fun `the sample rate is the first one the mixer supports`() {
        assertEquals(44_100, negotiateSampleRate(intArrayOf(48_000, 44_100), 48_000) { it == 44_100 })
        assertEquals(96_000, negotiateSampleRate(intArrayOf(48_000, 44_100, 96_000), 48_000) { it == 96_000 })
    }

    @Test
    fun `no supported rate falls back to the given default`() {
        assertEquals(32_000, negotiateSampleRate(intArrayOf(48_000, 44_100), 32_000) { false })
    }
}
