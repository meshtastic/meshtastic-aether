package org.meshtastic.aether.synth

private const val HEADER_BYTES = 44
private const val CHANNELS = 2
private const val BITS_PER_SAMPLE = 16
private const val BYTES_PER_FRAME = CHANNELS * BITS_PER_SAMPLE / Byte.SIZE_BITS

/** The `fmt ` chunk's body: format, channels, rate, byte rate, block align and bits. */
private const val FMT_CHUNK_BYTES = 16
private const val FORMAT_PCM = 1

/** The RIFF chunk's size counts everything after its own 8-byte header. */
private const val RIFF_HEADER_BYTES = 8
private const val PCM_FULL_SCALE = 32767f

/**
 * Encodes interleaved stereo [samples] (left, right, left, ...) as a 16-bit PCM WAV; an odd trailing sample is
 * dropped.
 */
fun wavBytes(samples: FloatArray, sampleRate: Int): ByteArray {
    val frames = samples.size / CHANNELS
    val dataBytes = frames * BYTES_PER_FRAME
    val out = ByteArray(HEADER_BYTES + dataBytes)
    var p = 0

    fun ascii(s: String) {
        for (c in s) out[p++] = c.code.toByte()
    }

    fun u16(v: Int) {
        out[p++] = v.toByte()
        out[p++] = (v ushr Byte.SIZE_BITS).toByte()
    }

    fun u32(v: Int) {
        u16(v)
        u16(v ushr Short.SIZE_BITS)
    }

    ascii("RIFF")
    u32(HEADER_BYTES - RIFF_HEADER_BYTES + dataBytes)
    ascii("WAVE")
    ascii("fmt ")
    u32(FMT_CHUNK_BYTES)
    u16(FORMAT_PCM)
    u16(CHANNELS)
    u32(sampleRate)
    u32(sampleRate * BYTES_PER_FRAME)
    u16(BYTES_PER_FRAME)
    u16(BITS_PER_SAMPLE)
    ascii("data")
    u32(dataBytes)
    for (i in 0 until frames * CHANNELS) {
        val x = samples[i]
        val clamped = if (x > 1f) 1f else if (x < -1f) -1f else if (x != x) 0f else x
        val v = kotlin.math.round(clamped * PCM_FULL_SCALE).toInt()
        u16(v)
    }
    return out
}
