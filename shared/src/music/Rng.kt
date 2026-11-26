package org.meshtastic.aether.music

/** SplitMix64: small, seedable and identical on every target. */
class Rng(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += GOLDEN
        return mix(state)
    }

    /** Uniform in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    fun nextInt(bound: Int): Int = (nextDouble() * bound).toInt().coerceAtMost(bound - 1)

    fun range(lo: Double, hi: Double): Double = lo + (hi - lo) * nextDouble()

    companion object {
        private val GOLDEN = 0x9E3779B97F4A7C15UL.toLong()
        private val M1 = 0xBF58476D1CE4E5B9UL.toLong()
        private val M2 = 0x94D049BB133111EBUL.toLong()

        fun mix(x: Long): Long {
            var z = x
            z = (z xor (z ushr 30)) * M1
            z = (z xor (z ushr 27)) * M2
            return z xor (z ushr 31)
        }

        /** An independent stream derived from [seed] and a stream number. */
        fun stream(seed: Long, n: Int): Rng = Rng(mix(seed + GOLDEN * (n + 1)))
    }
}
