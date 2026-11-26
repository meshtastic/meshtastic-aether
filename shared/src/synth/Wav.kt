package org.meshtastic.ambient.synth

/** Encodes interleaved stereo [samples] (left, right, left, ...) as a 16-bit PCM WAV; an odd trailing sample is dropped. */
fun wavBytes(samples: FloatArray, sampleRate: Int): ByteArray {
    val frames = samples.size / 2
    val dataBytes = frames * 4
    val out = ByteArray(44 + dataBytes)
    var p = 0

    fun ascii(s: String) {
        for (c in s) out[p++] = c.code.toByte()
    }

    fun u32(v: Int) {
        out[p++] = v.toByte()
        out[p++] = (v ushr 8).toByte()
        out[p++] = (v ushr 16).toByte()
        out[p++] = (v ushr 24).toByte()
    }

    fun u16(v: Int) {
        out[p++] = v.toByte()
        out[p++] = (v ushr 8).toByte()
    }

    ascii("RIFF")
    u32(36 + dataBytes)
    ascii("WAVE")
    ascii("fmt ")
    u32(16)
    u16(1)
    u16(2)
    u32(sampleRate)
    u32(sampleRate * 4)
    u16(4)
    u16(16)
    ascii("data")
    u32(dataBytes)
    for (i in 0 until frames * 2) {
        val x = samples[i]
        val clamped = if (x > 1f) 1f else if (x < -1f) -1f else if (x != x) 0f else x
        val v = kotlin.math.round(clamped * 32767f).toInt()
        u16(v)
    }
    return out
}
