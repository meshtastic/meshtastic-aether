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
        exp(ln(0.001) / (maxOf(seconds, 0.001f) * sampleRate)).toFloat()

    fun trigger(attackSec: Float, decaySec: Float, sustainLevel: Float, releaseSec: Float) {
        attackInc = 1.0f / (maxOf(attackSec, 0.001f) * sampleRate)
        decayCoef = coefFor(decaySec)
        releaseCoef = coefFor(releaseSec)
        sustain = sustainLevel
        level = 0f
        stage = ATTACK
    }

    fun release() {
        if (stage != IDLE) stage = RELEASE
    }

    fun kill() {
        level = 0f
        stage = IDLE
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
                if (level - sustain < 1.0e-4f) {
                    level = sustain
                    stage = if (sustain < 1.0e-4f) IDLE else SUSTAIN
                }
            }
            RELEASE -> {
                level *= releaseCoef
                if (level < 1.0e-5f) {
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
    }
}
