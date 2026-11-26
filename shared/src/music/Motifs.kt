package org.meshtastic.aether.music

/** A node's signature: [roles] are chord roles 0..4 and [gaps] the seconds before each note (the first is 0). */
class Motif(val roles: IntArray, val gaps: DoubleArray) {
    val size: Int get() = roles.size
}

object Motifs {
    /** Inter-onset gaps a motif draws from, in seconds. */
    val GAPS_SECONDS = doubleArrayOf(0.5, 0.75, 1.0, 1.5)
    const val MIN_NOTES = 3
    private const val SALT = 0x4D4F544946L

    /**
     * 3 or 4 notes derived only from [node]. The first role is the node's own [Ornaments.role], each next role moves
     * 1..4 steps up the chord stack (mod 5), so neighbors always differ and a node keeps its motif across chords.
     */
    fun of(node: Long): Motif {
        var h = Rng.mix(node xor SALT)
        fun draw(bound: Int): Int {
            val v = (h ushr 33).mod(bound.toLong()).toInt()
            h = Rng.mix(h)
            return v
        }
        val size = MIN_NOTES + draw(2)
        val roles = IntArray(size)
        val gaps = DoubleArray(size)
        roles[0] = Ornaments.role(node)
        for (i in 1 until size) {
            roles[i] = (roles[i - 1] + 1 + draw(4)).mod(5)
            gaps[i] = GAPS_SECONDS[draw(GAPS_SECONDS.size)]
        }
        return Motif(roles, gaps)
    }
}
