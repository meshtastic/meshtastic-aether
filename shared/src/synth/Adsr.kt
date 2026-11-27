package org.meshtastic.aether.synth

import kotlin.math.exp
import kotlin.math.ln

/** Linear attack, exponential decay and release; the decay and release times are the time to fall 60 dB. */
internal class Adsr(private val sampleRate: Int) {
    var level = 0f
        private set
    private var stage = IDLE
    private var attackInc = 0f
    private var decayCoef = 0f
    private var releaseCoef = 0f
    private var sustain = 0f

    val isIdle: Boolean get() = stage == IDLE
    val isReleasing: Boolean get() = stage == RELEASE
    val isAttacking: Boolean get() = stage == ATTACK

    private fun coefFor(seconds: Float): Float =
        exp(ln(MINUS_60_DB) / (maxOf(seconds, MIN_STAGE_SECONDS) * sampleRate)).toFloat()

    fun trigger(attackSec: Float, decaySec: Float, sustainLevel: Float, releaseSec: Float) {
        attackInc = 1.0f / (maxOf(attackSec, MIN_STAGE_SECONDS) * sampleRate)
        decayCoef = coefFor(decaySec)
        releaseCoef = coefFor(releaseSec)
        sustain = sustainLevel
        level = 0f
        stage = ATTACK
    }

    /** A held envelope: attack to full level, stay there until [release]. */
    fun gate(attackSec: Float, releaseSec: Float) {
        trigger(attackSec, HELD_DECAY_SECONDS, 1f, releaseSec)
    }

    /**
     * Holds again from the current level, as [gate] does from silence: a continued bed note rises back to full level
     * over what is left of [attackSec] instead of restarting at zero, so a re-struck tone never dips.
     */
    fun regate(attackSec: Float, releaseSec: Float) {
        attackInc = 1.0f / (maxOf(attackSec, MIN_STAGE_SECONDS) * sampleRate)
        decayCoef = coefFor(HELD_DECAY_SECONDS)
        releaseCoef = coefFor(releaseSec)
        sustain = 1f
        stage = ATTACK
    }

    fun release() {
        if (stage != IDLE) stage = RELEASE
    }

    fun next(): Float {
        when (stage) {
            ATTACK -> {
                level += attackInc
                if (level >= 1f) {
                    level = 1f
                    stage = DECAY
                }
            }
            DECAY -> {
                level = sustain + (level - sustain) * decayCoef
                if (level - sustain < SETTLED) {
                    level = sustain
                    stage = if (sustain < SETTLED) IDLE else SUSTAIN
                }
            }
            RELEASE -> {
                level *= releaseCoef
                if (level < SILENT) {
                    level = 0f
                    stage = IDLE
                }
            }
            else -> Unit
        }
        return level
    }

    private companion object {
        const val IDLE = 0
        const val ATTACK = 1
        const val DECAY = 2
        const val SUSTAIN = 3
        const val RELEASE = 4

        /** -60 dB as an amplitude ratio: the decay and release times are measured to it. */
        const val MINUS_60_DB = 0.001
        const val MIN_STAGE_SECONDS = 0.001f

        /** Unheard at full sustain, where the decay has nothing to fall to. */
        const val HELD_DECAY_SECONDS = 0.1f

        /** Distance from the sustain level at which the decay snaps to it. */
        const val SETTLED = 1.0e-4f

        /** Level at which the release ends and the voice goes idle. */
        const val SILENT = 1.0e-5f
    }
}
