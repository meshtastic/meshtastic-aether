package org.meshtastic.aether.midi

private const val TICKS_PER_QUARTER = 960
private const val MICROS_PER_QUARTER = 500_000
private const val MICROS_PER_SECOND = 1_000_000L
private const val BYTE_MASK = 0xFF
private const val SEVEN_BITS = 0x7F
private const val CONTINUE = 0x80
private const val BITS_PER_GROUP = 7
private const val META = 0xFF
private const val META_TEMPO = 0x51
private const val META_END = 0x2F
private const val TEMPO_BYTES = 3
private const val HEADER_LENGTH = 6
private const val INT_BYTES = 4

/** A growable byte buffer: the common standard library has no stream to write into. */
private class Bytes {
    private var data = ByteArray(INITIAL)
    var size = 0
        private set

    fun put(b: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = b.toByte()
    }

    fun put(bytes: ByteArray) = bytes.forEach { put(it.toInt()) }

    fun int(v: Int, bytes: Int) {
        for (i in bytes - 1 downTo 0) put((v ushr (Byte.SIZE_BITS * i)) and BYTE_MASK)
    }

    fun toByteArray(): ByteArray = data.copyOf(size)

    private companion object {
        const val INITIAL = 256
    }
}

/**
 * A Standard MIDI File of the mapper's output: format 0 for one part, format 1 with one track per part. The file runs
 * at 960 ticks per quarter at 120 bpm, so a tick is 1/1920 s, exactly 25 frames at 48 kHz.
 */
class StandardMidiFile(private val sampleRate: Int, private val parts: Int) {
    private val tracks = List(parts) { Bytes() }
    private val lastTick = LongArray(parts)

    init {
        tracks[0].apply {
            put(0)
            put(META)
            put(META_TEMPO)
            put(TEMPO_BYTES)
            int(MICROS_PER_QUARTER, TEMPO_BYTES)
        }
    }

    /** Appends [event]; events must come in non-decreasing frame order, and a frame before 0 is written at 0. */
    fun add(event: MidiEvent) {
        val tick = maxOf(0L, tickOf(event.frame))
        val track = tracks[event.part]
        varLength(track, tick - lastTick[event.part])
        lastTick[event.part] = tick
        track.put(event.data)
    }

    private fun tickOf(frame: Long): Long =
        (frame * TICKS_PER_SECOND + sampleRate / 2) / sampleRate

    /** The file: the header chunk, then each track chunk closed by its end-of-track event. */
    fun bytes(): ByteArray {
        val out = Bytes()
        out.put("MThd".encodeToByteArray())
        out.int(HEADER_LENGTH, INT_BYTES)
        out.int(if (parts == 1) 0 else 1, 2)
        out.int(parts, 2)
        out.int(TICKS_PER_QUARTER, 2)
        for (t in tracks) {
            val body = t.toByteArray()
            out.put("MTrk".encodeToByteArray())
            out.int(body.size + END_OF_TRACK.size, INT_BYTES)
            out.put(body)
            out.put(END_OF_TRACK)
        }
        return out.toByteArray()
    }

    companion object {
        const val TICKS_PER_SECOND = TICKS_PER_QUARTER * MICROS_PER_SECOND / MICROS_PER_QUARTER
        private val END_OF_TRACK = byteArrayOf(0, META.toByte(), META_END.toByte(), 0)

        /** [value] as a variable-length quantity: seven bits a byte, most significant first, high bit for more. */
        fun varLength(value: Long): ByteArray = Bytes().also { varLength(it, value) }.toByteArray()

        private fun varLength(out: Bytes, value: Long) {
            require(value in 0..MAX_VAR_LENGTH) { "a delta of $value ticks does not fit a variable-length quantity" }
            var shift = BITS_PER_GROUP * (VAR_LENGTH_BYTES - 1)
            while (shift > 0 && (value ushr shift) == 0L) shift -= BITS_PER_GROUP
            while (shift > 0) {
                out.put((((value ushr shift) and SEVEN_BITS.toLong()).toInt()) or CONTINUE)
                shift -= BITS_PER_GROUP
            }
            out.put((value and SEVEN_BITS.toLong()).toInt())
        }

        private const val VAR_LENGTH_BYTES = 4
        private const val MAX_VAR_LENGTH = 0x0FFF_FFFFL
    }
}
