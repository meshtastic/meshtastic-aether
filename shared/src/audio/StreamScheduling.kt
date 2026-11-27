package org.meshtastic.aether.audio

const val WRITE_FAILED = -1

/**
 * New offset after a blocking `write` of `total - offset` units returned [result]: a negative result
 * is an error code and yields [WRITE_FAILED]; zero keeps the offset; the result is capped at [total].
 */
fun advanceWrite(offset: Int, total: Int, result: Int): Int =
    if (result < 0) WRITE_FAILED else minOf(total, offset + result)
