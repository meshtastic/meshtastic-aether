package org.meshtastic.ambient.ui

/** Delays that double from [initialMillis] up to [maxMillis]; [reset] starts over after a success. */
internal class Backoff(private val initialMillis: Long = 2_000, private val maxMillis: Long = 60_000) {
    private var next = initialMillis

    /** The delay to wait now; the one after it is twice as long, capped. */
    fun next(): Long {
        val current = next
        next = (next * 2).coerceAtMost(maxMillis)
        return current
    }

    fun reset() {
        next = initialMillis
    }
}
