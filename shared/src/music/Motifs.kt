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

    /** Each draw reads the hash's high bits, the best mixed. */
    private const val DRAW_SHIFT = 33

    /**
     * 3 or 4 notes derived only from [node]. The first role is the node's own [Ornaments.role], each next role moves
     * 1..4 steps up the chord stack (mod 5), so neighbors always differ and a node keeps its motif across chords.
     */
    fun of(node: Long): Motif {
        var h = Rng.mix(node xor SALT)
        fun draw(bound: Int): Int {
            val v = (h ushr DRAW_SHIFT).mod(bound.toLong()).toInt()
            h = Rng.mix(h)
            return v
        }
        val size = MIN_NOTES + draw(2)
        val roles = IntArray(size)
        val gaps = DoubleArray(size)
        roles[0] = Ornaments.role(node)
        for (i in 1 until size) {
            roles[i] = (roles[i - 1] + 1 + draw(Chord.ROLES - 1)).mod(Chord.ROLES)
            gaps[i] = GAPS_SECONDS[draw(GAPS_SECONDS.size)]
        }
        return Motif(roles, gaps)
    }
}

/**
 * Each node's motif as it develops, after Mutable Instruments Marbles' deja vu (MIT): the first time a node plays it
 * plays its original [Motifs.of]; after each play every step past the first may redraw its interval and gap, so the
 * phrase drifts while its opening note, the node's own chord role, never moves. Every [RETURN_EVERY] plays the
 * original comes back, a return the ear can hold on to. Draws are seeded by node and play count, not the composer's
 * stream, so they change no other choice.
 */
internal class MotifMemory {
    private class State(var motif: Motif, var plays: Int)

    private val states = HashMap<Long, State>()

    /** The motif [node] plays now. */
    fun current(node: Long): Motif = states[node]?.motif ?: Motifs.of(node)

    /** Records that [node] was heard playing [current]; the next play may hear it changed. */
    fun advance(node: Long) {
        val s = states.getOrPut(node) { State(Motifs.of(node), 0) }
        s.plays++
        s.motif = if (s.plays % RETURN_EVERY == 0) Motifs.of(node) else mutate(node, s.motif, s.plays)
    }

    fun forget(node: Long) {
        states.remove(node)
    }

    private fun mutate(node: Long, from: Motif, plays: Int): Motif {
        val rng = Rng(Rng.mix(node xor SALT xor plays.toLong() * PLAY_STRIDE))
        val roles = from.roles.copyOf()
        val gaps = from.gaps.copyOf()
        for (i in 1 until roles.size) {
            val step = (from.roles[i] - from.roles[i - 1]).mod(Chord.ROLES)
            val newStep = if (rng.nextDouble() < CHANGE) 1 + rng.nextInt(Chord.ROLES - 1) else step
            roles[i] = (roles[i - 1] + newStep).mod(Chord.ROLES)
            if (rng.nextDouble() < CHANGE) gaps[i] = Motifs.GAPS_SECONDS[rng.nextInt(Motifs.GAPS_SECONDS.size)]
        }
        return Motif(roles, gaps)
    }

    companion object {
        /** Chance per play that one step of the motif redraws its interval, and separately its gap. */
        const val CHANGE = 0.2

        /** The original motif returns on every this many plays. */
        const val RETURN_EVERY = 8
        private const val SALT = 0x44454A41L
        private const val PLAY_STRIDE = 0x7F4A7C15L
    }
}
