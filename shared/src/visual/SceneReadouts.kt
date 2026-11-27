package org.meshtastic.aether.visual

import org.meshtastic.aether.music.Rng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

// How the scene looks at its current frame: pure reads of its clocks and state for the renderer, changing nothing.

private const val IDLE_FADE_SECONDS = 3600.0
private const val FLASH_TAU_SECONDS = 12.0
private const val NOTE_PULSE = 0.45
private const val NOTE_PULSE_TAU_SECONDS = 2.5

/** An orb breathes between 0.85 and 1.0 over 7 to 13 s. */
private const val BREATH_MIN_PERIOD = 7.0
private const val BREATH_PERIOD_SPREAD = 6.0
private const val BREATH_REST = 0.925
private const val BREATH_DEPTH = 0.075

private const val FIELD_BREATH = 0.25
private const val FIELD_BREATH_PERIOD = 11.0

/** How far an orb drifts from its place, in unit-disc units, and the drift's angular rate on each axis. */
private const val DRIFT = 0.012
private const val DRIFT_X_RATE = 0.037
private const val DRIFT_Y_RATE = 0.029
private const val DRIFT_Z_RATE = 0.033

// Salts that give each per-node phase its own stream.
private const val DRIFT_X_SALT = 1
private const val DRIFT_Y_SALT = 2
private const val BREATH_PERIOD_SALT = 3
private const val BREATH_PHASE_SALT = 4
private const val DRIFT_Z_SALT = 5

/**
 * Glow 0..1 of [orb] now: an idle core that eases from [SceneModel.IDLE_CORE] toward [SceneModel.IDLE_FLOOR] and never
 * below it, plus a flash at a packet decaying over seconds and a smaller pulse for each of its notes.
 */
fun SceneModel.glow(orb: Orb): Double {
    val reference = if (orb.lastActiveFrame == Orb.NEVER) orb.seenFrame else orb.lastActiveFrame
    val since = (lastFrame - reference).coerceAtLeast(0L).toDouble() / sampleRate
    val idle = SceneModel.IDLE_FLOOR + (SceneModel.IDLE_CORE - SceneModel.IDLE_FLOOR) * exp(-since / IDLE_FADE_SECONDS)
    var g = idle
    if (orb.lastActiveFrame != Orb.NEVER) g += (1.0 - idle) * exp(-since / FLASH_TAU_SECONDS)
    if (orb.notePulseFrame != Orb.NEVER) {
        val noteSince = (lastFrame - orb.notePulseFrame).coerceAtLeast(0L).toDouble() / sampleRate
        g += NOTE_PULSE * orb.notePulseStrength * exp(-noteSince / NOTE_PULSE_TAU_SECONDS)
    }
    return g.coerceAtMost(1.0)
}

/** Slow breathing of an orb, 0.85..1.0, each at its own period and phase from the node number. */
fun SceneModel.breath(node: Long): Double {
    val period = BREATH_MIN_PERIOD + BREATH_PERIOD_SPREAD * SceneModel.unit(Rng.mix(node + BREATH_PERIOD_SALT))
    return BREATH_REST + BREATH_DEPTH * sin(2 * PI * orbBreathClock.seconds / period + phase(node, BREATH_PHASE_SALT))
}

/** Multiplier near 1.0 that the pad level breathes the field with: swells and recedes over about 11 s. */
fun SceneModel.fieldBreath(tSeconds: Double): Double =
    1.0 + FIELD_BREATH * padLevel * sin(2 * PI * tSeconds / FIELD_BREATH_PERIOD)

/** 0..1 progress of [r] through its life. */
fun SceneModel.progress(r: Ripple): Double =
    ((lastFrame - r.startFrame).toDouble() / sampleRate / SceneModel.RIPPLE_LIFE_SECONDS).coerceIn(0.0, 1.0)

/** 0..1 progress of [p] along its leg; 1.0 is arrival. */
fun SceneModel.progress(p: Pulse): Double =
    ((lastFrame - p.startFrame).toDouble() / (p.endFrame - p.startFrame).coerceAtLeast(1L)).coerceIn(0.0, 1.0)

/** 1.0 when reported now, fading linearly to 0 over [SceneModel.EDGE_FADE_SECONDS]. */
fun SceneModel.edgeFade(edge: Edge): Double = edgeSet.fade(edge, lastFrame)

/** Slow drift of an orb around its laid-out place, in unit-disc units; the whole field wanders against burn-in. */
fun SceneModel.driftX(node: Long): Double =
    DRIFT * sin(driftClock.seconds * DRIFT_X_RATE + phase(node, DRIFT_X_SALT))

fun SceneModel.driftY(node: Long): Double =
    DRIFT * cos(driftClock.seconds * DRIFT_Y_RATE + phase(node, DRIFT_Y_SALT))

fun SceneModel.driftZ(node: Long): Double =
    DRIFT * sin(driftClock.seconds * DRIFT_Z_RATE + phase(node, DRIFT_Z_SALT))

private fun phase(node: Long, salt: Int): Double = SceneModel.unit(Rng.mix(node + salt)) * 2 * PI
