package org.meshtastic.aether.visual

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A small physics layer over the laid-out places, in unit-disc units. It never moves an orb far from its place, so the
 * map stays readable; it only gives the field a pulse:
 *
 * - each orb circles its place on a ring (an epicycle) whose period falls with its distance from the anchor like
 *   Kepler's third law, the way the cloud turns (`direction`), with a ring that shrinks as the node is better heard and
 *   as the mesh's [gravity] rises, so a loosely bound node wanders wider;
 * - every fresh edge pulls its two nodes toward each other with a force that scales with [gravity] and the edge's
 *   freshness, so linked nodes lean together;
 * - a packet kicks its node (see [kick]) and the spring to its ring eases it back.
 *
 * Per-orb state lives on [Orb]; the field itself is stateless, so a step is a pure function of that state and its
 * inputs and is the same on every target.
 */
internal object OrbitalField {
    /** Largest displacement of an orb from its place. */
    const val MAX_OFFSET = 0.16

    /** The ring radius of an orb that is heard weakly in a loose mesh; it shrinks toward [ORBIT_TIGHTEN] of this. */
    const val ORBIT_RADIUS = 0.07

    /** Fraction of the ring that full gravity and full binding remove. */
    const val ORBIT_TIGHTEN = 0.7

    /** Angular speed in radians per second at [REFERENCE_RADIUS] from the anchor. */
    const val ORBIT_RATE = 0.35
    const val REFERENCE_RADIUS = 0.5
    private const val MIN_RADIUS = 0.15
    private const val MAX_RATE = 1.0

    private const val SPRING = 0.8
    private const val DAMPING = 1.4
    private const val EDGE_PULL = 0.6
    private const val EDGE_REACH = 0.5

    /** Largest step integrated at once; a longer gap (the first frame, a stalled app) is cut to this. */
    const val MAX_STEP_SECONDS = 0.05

    /** Speed in unit-disc units per second that a full-strength packet adds. */
    const val KICK_SPEED = 0.12

    /** RSSI in dBm that counts as unheard (0) and as strong (1). */
    private const val RSSI_WEAK = -120.0
    private const val RSSI_STRONG = -40.0
    private const val BINDING_UNKNOWN = 0.4

    /** 0..1 how well this orb is heard: its smoothed RSSI mapped between [RSSI_WEAK] and [RSSI_STRONG]. */
    fun binding(rssi: Double?): Double =
        if (rssi == null) BINDING_UNKNOWN else ((rssi - RSSI_WEAK) / (RSSI_STRONG - RSSI_WEAK)).coerceIn(0.0, 1.0)

    /** Radius of the ring for an orb with [binding] under [gravity], both 0..1. */
    fun ringRadius(binding: Double, gravity: Double): Double = ORBIT_RADIUS * (1.0 - ORBIT_TIGHTEN * gravity.coerceIn(0.0, 1.0) * binding.coerceIn(0.0, 1.0))

    /** Angular speed of an orb whose place is [distance] from the anchor: Kepler's `r^-1.5`, capped. */
    fun angularRate(distance: Double): Double = min(MAX_RATE, ORBIT_RATE * (REFERENCE_RADIUS / max(distance, MIN_RADIUS)).pow(1.5))

    /** Gives [orb] a speed of [strength] (0..1) of [KICK_SPEED] in a direction that is a fixed function of its node and kick count. */
    fun kick(orb: Orb, strength: Float) {
        val s = strength.coerceIn(0f, 1f).toDouble()
        if (s <= 0.0) return
        orb.phKicks++
        val angle = (orb.node * 0.6180339887498949 + orb.phKicks * GOLDEN_ANGLE).mod(2 * PI)
        orb.phVx += cos(angle) * KICK_SPEED * s
        orb.phVy += sin(angle) * KICK_SPEED * s
    }

    /**
     * Advances every orb in [orbs] by [dtSeconds]. [direction] is +1 or -1 for the way the rings turn, [gravity] 0..1 the
     * pull of edges and the tightness of the rings. [edgeFade] is each edge's freshness 0..1; [orbOf] finds an edge's
     * ends and [anchorNode] is the one end that is fixed at the origin.
     */
    fun step(
        dtSeconds: Double,
        orbs: Collection<Orb>,
        edges: Collection<Edge>,
        edgeFade: (Edge) -> Double,
        orbOf: (Long) -> Orb?,
        anchorNode: Long?,
        direction: Int,
        gravity: Double,
    ) {
        if (!(dtSeconds > 0.0) || !dtSeconds.isFinite()) return
        val dt = min(dtSeconds, MAX_STEP_SECONDS)
        val g = if (gravity.isFinite()) gravity.coerceIn(0.0, 1.0) else 0.0
        val sign = if (direction >= 0) 1.0 else -1.0

        for (o in orbs) {
            o.phAx = 0.0
            o.phAy = 0.0
        }
        if (g > 0.0) {
            for (e in edges) {
                val fade = edgeFade(e)
                if (fade <= 0.0) continue
                val a = orbOf(e.a)
                val b = orbOf(e.b)
                val aIsAnchor = a == null && e.a == anchorNode
                val bIsAnchor = b == null && e.b == anchorNode
                if ((a == null && !aIsAnchor) || (b == null && !bIsAnchor)) continue
                val ax = a?.let { it.x + it.phOx } ?: 0.0
                val ay = a?.let { it.y + it.phOy } ?: 0.0
                val bx = b?.let { it.x + it.phOx } ?: 0.0
                val by = b?.let { it.y + it.phOy } ?: 0.0
                val dx = bx - ax
                val dy = by - ay
                val d = hypot(dx, dy)
                if (d < 1e-9) continue
                val pull = EDGE_PULL * g * fade * min(d, EDGE_REACH) / d
                if (a != null) {
                    a.phAx += dx * pull
                    a.phAy += dy * pull
                }
                if (b != null) {
                    b.phAx -= dx * pull
                    b.phAy -= dy * pull
                }
            }
        }

        for (o in orbs) {
            val home = hypot(o.x, o.y)
            o.phPhase = (o.phPhase + sign * angularRate(home) * dt).mod(2 * PI)
            val ring = ringRadius(binding(o.rssi), g)
            val tx = ring * cos(o.phPhase + ringStart(o.node))
            val ty = ring * sin(o.phPhase + ringStart(o.node))
            val ax = SPRING * (tx - o.phOx) - DAMPING * o.phVx + o.phAx
            val ay = SPRING * (ty - o.phOy) - DAMPING * o.phVy + o.phAy
            o.phVx += ax * dt
            o.phVy += ay * dt
            o.phOx += o.phVx * dt
            o.phOy += o.phVy * dt
            val m = hypot(o.phOx, o.phOy)
            if (m > MAX_OFFSET) {
                val k = MAX_OFFSET / m
                o.phOx *= k
                o.phOy *= k
                o.phVx *= k
                o.phVy *= k
            }
            if (!o.phOx.isFinite() || !o.phOy.isFinite() || !o.phVx.isFinite() || !o.phVy.isFinite()) o.resetPhysics()
        }
    }

    /** Where on its ring an orb starts: a fixed function of the node number, so orbs do not move in lockstep. */
    private fun ringStart(node: Long): Double = (node * 0.7548776662466927).mod(1.0) * 2 * PI

    private const val GOLDEN_ANGLE = 2.399963229728653
}
