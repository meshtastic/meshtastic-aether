package org.meshtastic.aether.visual

import org.meshtastic.aether.mesh.GeoPoint
import org.meshtastic.aether.mesh.LinkState
import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.mesh.PacketKind
import org.meshtastic.aether.mood.ParamValues
import org.meshtastic.aether.mood.RotationState
import org.meshtastic.aether.music.NoteEvent
import org.meshtastic.aether.music.Ornaments
import org.meshtastic.aether.music.Rng
import org.meshtastic.aether.music.Texture
import org.meshtastic.aether.music.Voice
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** The weight of each new RSSI reading in an orb's smoothed one. */
private const val RSSI_SMOOTHING = 0.3

/** One node on screen. [x] and [y] are laid out in the unit disc around our node, x right, y down. */
class Orb internal constructor(val node: Long, var name: String?, internal var position: GeoPoint?) {
    var x = 0.0
        internal set
    var y = 0.0
        internal set

    /** Depth -1..1 across the cloud, from the node number and the radius in the plane; see [SceneModel.depthFor]. */
    var z = 0.0
        internal set

    /**
     * Where the orb is drawn: eased toward the laid-out [x], [y], [z] so a relayout glides; a new orb starts at its
     * place.
     */
    internal var shownX = 0.0
    internal var shownY = 0.0
    internal var shownZ = 0.0
    internal var placed = false

    /** Physics offset from the laid-out place and its speed, in unit-disc units; see [OrbitalField]. */
    internal var phOx = 0.0
    internal var phOy = 0.0
    internal var phVx = 0.0
    internal var phVy = 0.0
    internal var phAx = 0.0
    internal var phAy = 0.0
    internal var phPhase = 0.0
    internal var phKicks = 0

    internal fun resetPhysics() {
        phOx = 0.0
        phOy = 0.0
        phVx = 0.0
        phVy = 0.0
    }

    internal fun physicsFinite(): Boolean = phOx.isFinite() && phOy.isFinite() && phVx.isFinite() && phVy.isFinite()

    /** The last projected screen offset, scale and depth, written by the scene each frame. */
    internal var projX = 0.0
    internal var projY = 0.0
    internal var projScale = 1.0
    internal var projDepth = 0.0
    internal var lastActiveFrame = NEVER
    internal var seenFrame = 0L
    internal var notePulseFrame = NEVER
    internal var notePulseStrength = 0f

    /** Device role and hardware names from the last [MeshEvent.NodeSeen] that carried them. */
    var role: String? = null
        internal set
    var hardware: String? = null
        internal set

    /** Health from [MeshEvent.Health]; null until reported. */
    var batteryPct: Int? = null
        internal set
    var voltage: Float? = null
        internal set
    var uptimeSeconds: Long? = null
        internal set

    /** Smoothed RSSI of the packets heard from this node in dBm; null until one carried it. */
    var rssi: Double? = null
        internal set

    /** Core radius and opacity multipliers from the role (1.0 when unknown). */
    var coreSize = 1.0
        internal set
    var coreAlpha = 1.0
        internal set

    /** Halo tint offset in degrees from the hardware model (0 when unknown). */
    var haloHueDegrees = 0
        internal set

    /** Halo size multiplier from uptime and brightness multiplier from voltage (1.0 when unknown). */
    var haloScale = 1.0
        internal set
    var brightness = 1.0
        internal set

    /**
     * Brightness multiplier of the low-battery flicker now, 1.0 when not flickering; see
     * [BatteryFlicker.flickerFactor].
     */
    var flicker = 1.0
        internal set

    /** Eased layout z offset from signal strength and altitude; negative is toward the camera. */
    var zOffset = 0.0
        internal set
    internal var flickerPhase = 0.0

    /** Takes the name, place, role and hardware a [MeshEvent.NodeSeen] carries. */
    internal fun see(e: MeshEvent.NodeSeen) {
        name = e.name
        position = e.position
        e.role?.let { r ->
            role = r
            coreSize = EventLook.coreSize(r)
            coreAlpha = EventLook.coreAlpha(r)
        }
        e.hardware?.let { h ->
            hardware = h
            haloHueDegrees = EventLook.hardwareHueDegrees(h)
        }
    }

    /** Takes the health a [MeshEvent.Health] reports; a field it leaves out keeps its last value. */
    internal fun report(e: MeshEvent.Health) {
        if (e.batteryPct != null) batteryPct = e.batteryPct
        if (e.voltage != null) voltage = e.voltage
        if (e.uptimeSeconds != null) uptimeSeconds = e.uptimeSeconds
        brightness = EventLook.brightness(voltage, batteryPct)
        haloScale = EventLook.haloScale(uptimeSeconds)
    }

    /** Folds a packet's RSSI into the smoothed [rssi]. */
    internal fun hear(rssiDbm: Int) {
        rssi = rssi?.let { prev -> prev + (rssiDbm - prev) * RSSI_SMOOTHING } ?: rssiDbm.toDouble()
    }

    /**
     * Eases the drawn place toward the laid-out one by [glide], the depth offset toward signal and altitude by [ease],
     * and runs the low-battery flicker over [dtSeconds]; [refAltitude] is our own altitude.
     */
    internal fun easeLook(dtSeconds: Double, ease: Double, glide: Double, refAltitude: Int?) {
        shownX += (x - shownX) * glide
        shownY += (y - shownY) * glide
        shownZ += (z - shownZ) * glide
        val target = EventLook.rssiDepthBias(rssi) + EventLook.altitudeLift(position?.altitudeM, refAltitude)
        zOffset += (target - zOffset) * ease
        val hz = BatteryFlicker.flickerHz(batteryPct)
        if (hz > 0.0) {
            flickerPhase = BatteryFlicker.advanceFlicker(flickerPhase, hz, dtSeconds)
            flicker = BatteryFlicker.flickerFactor(batteryPct, flickerPhase)
        } else {
            flicker = 1.0
        }
    }

    companion object {
        internal const val NEVER = Long.MIN_VALUE
    }
}

/** The attributes of a heard packet that shape its ripple; each is neutral when absent. */
internal data class PacketTraits(
    val viaMqtt: Boolean = false,
    val payloadBytes: Int? = null,
    val textLength: Int? = null,
    val channel: Int? = null,
) {
    companion object {
        val NONE = PacketTraits()
    }
}

/**
 * A ring from an orb. [echo] 0 is the packet itself, 1.. are the hop echoes, fainter and later. The packet's own
 * [traits] are already reduced to bounded drawing values by [RippleLook]; each is neutral when absent.
 */
class Ripple internal constructor(
    val node: Long,
    val startFrame: Long,
    val kind: PacketKind,
    val strength: Float,
    val echo: Int,
    traits: PacketTraits = PacketTraits.NONE,
) {
    val viaMqtt: Boolean = traits.viaMqtt
    val widthTrim: Double = RippleLook.payloadWidthTrim(traits.payloadBytes)
    val ringCount: Int = RippleLook.ringCount(kind, traits.textLength)
    val hueDegrees: Int = RippleLook.channelHueDegrees(traits.channel)
    val style: RingStyle = RippleLook.ringStyle(kind)
    val coolMix: Float = RippleLook.mqttMix(viaMqtt)
}

/**
 * A clock that advances by `dt / scale`: changing [scale] changes the rate and never moves the value, so no phase read
 * from it jumps.
 */
internal class PhaseClock {
    var scale = 1.0
        set(value) {
            field = if (value.isFinite()) value.coerceIn(MIN_SCALE, MAX_SCALE) else 1.0
        }
    var seconds = 0.0
        private set

    fun advance(dtSeconds: Double) {
        seconds += dtSeconds / scale
    }

    companion object {
        const val MIN_SCALE = 0.25
        const val MAX_SCALE = 4.0
    }
}

/** Our own node: fixed at the scene centre, never an orb, and never named on screen. */
class Anchor internal constructor(val node: Long) {
    val x = 0.0
    val y = 0.0
}

/** A pulse travelling along one leg: orb [fromNode] to orb [toNode], or to the anchor when [toNode] is null. */
class Pulse internal constructor(
    val fromNode: Long,
    val toNode: Long?,
    val startFrame: Long,
    val endFrame: Long,
    /** Whose color the pulse wears. */
    val colorNode: Long,
    val strength: Float,
)

/** A faint line between two nodes that fades unless it is reported again. */
class Edge internal constructor(val a: Long, val b: Long, internal var refreshedFrame: Long)

private class PendingNote(val dueFrame: Long, val node: Long, val strength: Float)

private class Pending(
    val dueFrame: Long,
    val node: Long,
    val kind: PacketKind,
    val strength: Float,
    val echo: Int,
    val traits: PacketTraits = PacketTraits.NONE,
)

/**
 * The scene as pure state on the audio clock: nothing here reads a wall clock or a canvas. The caller feeds events
 * with the frame their note will sound at ([onEvent]) and calls [update] with the sink's playback frame; a ripple
 * exists only once that frame has been reached, so sight and sound agree. What the scene looks like at that frame
 * (glow, breath, drift, progress) is read through the functions in `SceneReadouts.kt`.
 */
class SceneModel(internal val sampleRate: Int) {
    /** Frame timing from the render loop; the engine reads and resets it once a minute. */
    val frameStats = FrameStats()

    private val roster = OrbRoster()
    internal val edgeSet = EdgeSet(sampleRate)
    private val pulseQueue = PulseQueue(sampleRate)
    private val turning = SceneRotation()

    /** Our own node's position when known; else the layout centres on the centroid of positioned nodes. */
    var origin: GeoPoint? = null
        set(value) {
            field = value
            roster.dirty = true
        }

    /** Local minute of day, for the night dimming. */
    var minuteOfDay: Int = NOON_MINUTE

    /** Screen width over height; the layout fills an ellipse of this aspect and keeps orbs apart in screen terms. */
    var aspect: Double = 1.0
        set(value) {
            val v = value.coerceIn(MIN_ASPECT, MAX_ASPECT)
            if (abs(v - field) > ASPECT_RELAYOUT_STEP) {
                field = v
                roster.dirty = true
            }
        }

    /**
     * Whether the radio link is up; the engine sets it. While false the field desaturates and the anchor pulses
     * slowly.
     */
    var linked: Boolean = true

    /** 1.0 fully colored, easing toward [UNLINKED_SATURATION] while not [linked]. */
    var saturation = 1.0
        private set

    /** Degrees the background hue is shifted by the sounding key and brightness, easing slowly toward its target. */
    var hueShift = 0.0
        private set

    /** The pad layer's level 0..1, eased; it breathes the field. */
    var padLevel = 0.0
        private set

    private var hueTarget = 0.0
    private var padTarget = 0.0
    private var anchorPhase = 0.0

    /** The mesh-driven parameters the scene is easing toward; the engine sets them with [onParams]. */
    var params: ParamValues = ParamValues.NEUTRAL
        private set

    /** The integrated rotation as of the last [update]: yaw is the unwrapped angle, pitch is tilt plus wobble. */
    val rotation: RotationState get() = turning.rotation

    // The background gradient sways around the centre, faster with a rougher texture; the rate eases and the phase
    // integrates, so a texture change bends the motion and never jumps it.
    private var swayRate = NEUTRAL_SWAY_MOVE
    private var swayTarget = NEUTRAL_SWAY_MOVE
    private var swayPhase = 0.0

    /** The gradient's horizontal sway, -[SWAY_WIDTH]..[SWAY_WIDTH] of the screen width. */
    val sway: Double get() = sin(swayPhase) * SWAY_WIDTH

    private val cloudClock = PhaseClock()
    internal val driftClock = PhaseClock()
    internal val orbBreathClock = PhaseClock()

    /**
     * Multipliers on the period of the cloud breathing, the orb drift and the orb breath; 1.0 is neutral. Changing one
     * never jumps its phase.
     */
    var cloudBreathPeriodScale: Double
        get() = cloudClock.scale
        set(value) { cloudClock.scale = value }
    var driftPeriodScale: Double
        get() = driftClock.scale
        set(value) { driftClock.scale = value }
    var orbBreathPeriodScale: Double
        get() = orbBreathClock.scale
        set(value) { orbBreathClock.scale = value }

    /** Anchor ring breath period scale; the linked and unlinked periods are multiplied by it. */
    var anchorPeriodScale = 1.0
        set(value) {
            field = if (value.isFinite()) value.coerceIn(PhaseClock.MIN_SCALE, PhaseClock.MAX_SCALE) else 1.0
        }

    private val pending = ArrayList<Pending>()
    private val active = ArrayList<Ripple>()
    private val pendingNotes = ArrayList<PendingNote>()
    private var warming = false

    /** The playback frame of the last [update]. */
    internal var lastFrame = 0L
        private set

    /** Encrypted traffic as a faint haze, 0..1. */
    var haze = 0.0
        private set

    val orbs: Collection<Orb> get() = roster.orbs
    val pulses: List<Pulse> get() = pulseQueue.pulses
    val edges: Collection<Edge> get() = edgeSet.values

    /** Our node's anchor at the scene centre; null until a [MeshEvent.Self] arrives. */
    var anchor: Anchor? = null
        private set
    val ripples: List<Ripple> get() = active
    val dim: Double get() = nightDim(minuteOfDay)

    fun orb(node: Long): Orb? = roster.orb(node)

    /** Queues [e]; for a packet, [dueFrame] is when its note sounds. Packets during warm-up are state, not sound. */
    fun onEvent(e: MeshEvent, dueFrame: Long) {
        when (e) {
            is MeshEvent.Link -> warming = e.state == LinkState.WarmingUp
            is MeshEvent.NodeSeen -> roster.seen(e, lastFrame)
            is MeshEvent.Health -> roster.health(e, lastFrame)
            is MeshEvent.NodeGone -> roster.remove(e.node)
            is MeshEvent.PacketHeard -> if (!warming) enqueue(e, dueFrame)
            is MeshEvent.Environment, is MeshEvent.Vitals, is MeshEvent.Stats -> Unit
            is MeshEvent.Self -> {
                roster.self(e.node)
                anchor = Anchor(e.node)
                e.position?.let { origin = it }
            }
            is MeshEvent.Neighbors -> for (n in e.neighbors) edgeSet.add(e.node, n, lastFrame, roster)
            is MeshEvent.Route -> pulseQueue.route(e, dueFrame, lastFrame, roster)
        }
    }

    /**
     * Queues a pulse on the orb of [note]'s node for when the note sounds at [dueFrame]; notes without a known node do
     * nothing.
     */
    fun onNote(note: NoteEvent, dueFrame: Long) {
        val node = note.node ?: return
        pendingNotes += PendingNote(dueFrame, node, note.velocity.coerceIn(0f, 1f))
        if (pendingNotes.size > MAX_PENDING) pendingNotes.removeAt(0)
    }

    /** Sets the parameters the rotation eases toward; the picture never jumps to them. */
    fun onParams(p: ParamValues) {
        params = p
    }

    /**
     * Shifts the background hue toward the texture's key and brightness and the field's breathing toward its pad
     * level.
     */
    fun onTexture(texture: Texture) {
        val octaves = if (texture.rootHz > 0.0) ln(texture.rootHz / MIDDLE_C_HZ) / ln(2.0) else 0.0
        val pitchClass = (octaves * SEMITONES_PER_OCTAVE).mod(SEMITONES_PER_OCTAVE)
        hueTarget = HUE_SPAN * sin(2 * PI * pitchClass / SEMITONES_PER_OCTAVE) +
            (texture.brightness - NEUTRAL_BRIGHTNESS) * BRIGHTNESS_HUE
        padTarget = (texture.layerLevels[Voice.Pad] ?: 0f).toDouble().coerceIn(0.0, 1.0)
        swayTarget = SWAY_MOVE + SWAY_MOVE_PER_TEXTURE * (texture.detune + texture.noise)
    }

    private fun enqueue(e: MeshEvent.PacketHeard, dueFrame: Long) {
        val strength = Ornaments.velocityFor(e.snr)
        if (e.kind == PacketKind.Encrypted) {
            add(Pending(dueFrame, e.from, e.kind, strength, 0))
            return
        }
        roster.ensure(e.from, lastFrame)
        e.rssi?.let { r -> roster.orb(e.from)?.hear(r) }
        val traits = PacketTraits(e.viaMqtt, e.payloadBytes, e.textLength, e.channel)
        add(Pending(dueFrame, e.from, e.kind, strength, 0, traits))
        if (e.from != roster.selfNode) pulseQueue.packet(e, dueFrame, strength, roster)
        for (k in 1..Ornaments.echoesFor(e.hopsTaken, e.viaMqtt)) {
            val at = dueFrame + (Ornaments.ECHO_DELAY_SECONDS * k * sampleRate).toLong()
            add(Pending(at, e.from, e.kind, strength * ECHO_STRENGTH, k, traits))
        }
    }

    private fun add(p: Pending) {
        pending += p
        if (pending.size > MAX_PENDING) pending.removeAt(0)
    }

    /** Advances to [playbackFrame]: spawns the ripples whose frame has arrived, then ages the rest. */
    fun update(playbackFrame: Long) {
        val dtSeconds = ((playbackFrame - lastFrame).coerceAtLeast(0L)).toDouble() / sampleRate
        lastFrame = playbackFrame
        haze *= exp(-dtSeconds / HAZE_TAU_SECONDS)
        hueShift += (hueTarget - hueShift) * (1 - exp(-dtSeconds / HUE_TAU_SECONDS))
        padLevel += (padTarget - padLevel) * (1 - exp(-dtSeconds / PAD_TAU_SECONDS))
        swayRate += (swayTarget - swayRate) * (1 - exp(-dtSeconds / HUE_TAU_SECONDS))
        swayPhase = (swayPhase + SWAY_RATE * swayRate * dtSeconds).mod(2 * PI)
        val satTarget = if (linked) 1.0 else UNLINKED_SATURATION
        saturation += (satTarget - saturation) * (1 - exp(-dtSeconds / SATURATION_TAU_SECONDS))
        val anchorPeriod = if (linked) ANCHOR_PERIOD_SECONDS else UNLINKED_ANCHOR_PERIOD_SECONDS
        anchorPhase = (anchorPhase + 2 * PI * dtSeconds / (anchorPeriod * anchorPeriodScale)).mod(2 * PI)
        cloudClock.advance(dtSeconds)
        driftClock.advance(dtSeconds)
        orbBreathClock.advance(dtSeconds)
        turning.step(dtSeconds, params)
        val ease = 1 - exp(-dtSeconds / DEPTH_EASE_SECONDS)
        val glide = 1 - exp(-dtSeconds / RELAYOUT_GLIDE_SECONDS)
        val refAltitude = origin?.altitudeM
        for (o in roster.orbs) o.easeLook(dtSeconds, ease, glide, refAltitude)
        turning.easeGravity(dtSeconds, params.gravity)
        if (OrbitalField.canStep(dtSeconds)) {
            val g = turning.gravity
            OrbitalField.pullAlongEdges(edgeSet.values, { edgeSet.fade(it, lastFrame) }, roster::orb, anchor?.node, g)
            OrbitalField.advance(dtSeconds, roster.orbs, rotation.direction, g)
        }

        var i = 0
        while (i < pending.size) {
            val p = pending[i]
            if (p.dueFrame <= playbackFrame) {
                pending.removeAt(i)
                spawn(p)
            } else {
                i++
            }
        }
        var n = 0
        while (n < pendingNotes.size) {
            val pn = pendingNotes[n]
            if (pn.dueFrame <= playbackFrame) {
                pendingNotes.removeAt(n)
                roster.orb(pn.node)?.let {
                    it.notePulseFrame = pn.dueFrame
                    it.notePulseStrength = pn.strength
                }
            } else {
                n++
            }
        }
        active.removeAll { (playbackFrame - it.startFrame).toDouble() / sampleRate > RIPPLE_LIFE_SECONDS }
        pulseQueue.advance(playbackFrame)
        edgeSet.expire(playbackFrame)
        if (roster.dirty) roster.relayout(origin, aspect)
    }

    private fun spawn(p: Pending) {
        if (p.kind == PacketKind.Encrypted) {
            haze = (haze + HAZE_STEP).coerceAtMost(1.0)
            return
        }
        roster.orb(p.node)?.let {
            it.lastActiveFrame = p.dueFrame
            if (p.echo == 0) OrbitalField.kick(it, p.strength)
        }
        if (p.echo == 0) turning.kick()
        active += Ripple(p.node, p.dueFrame, p.kind, p.strength, p.echo, p.traits)
        if (active.size > MAX_RIPPLES) active.removeAt(0)
    }

    /**
     * Multiplier near 1.0 for every coordinate of the cloud: 3 percent over a minute, from its own phase accumulator.
     */
    val cloudBreath: Double
        get() = CloudMotion.breathing(2 * PI * cloudClock.seconds / CloudMotion.BREATH_PERIOD_SECONDS)

    /** The anchor ring's breath 0..1: a calm 8 s cycle while linked, a slower, longer one while not. */
    val anchorBreath: Double get() = (1 + sin(anchorPhase)) / 2

    companion object {
        const val RIPPLE_LIFE_SECONDS = 6.0
        const val MAX_PENDING = 256
        const val MAX_RIPPLES = 96
        const val MAX_PULSES = 96
        const val MAX_EDGES = 256
        const val EDGE_FADE_SECONDS = 60.0
        const val HOP_SECONDS = 0.4
        const val TRAVEL_SECONDS = 0.8

        /** Each packet adds this much (of 1.0) to the rotation impulse, which decays over ten seconds. */
        const val PACKET_IMPULSE = 0.2
        private const val DEPTH_EASE_SECONDS = 5.0

        /** Time constant of an orb gliding to a new laid-out place when another node joins or leaves. */
        private const val RELAYOUT_GLIDE_SECONDS = 2.5

        private const val HAZE_TAU_SECONDS = 8.0
        private const val HAZE_STEP = 0.12

        /** A hop echo's ripple is this fraction of the packet's strength. */
        private const val ECHO_STRENGTH = 0.5f

        /** Glow of an orb just seen, fading toward [IDLE_FLOOR]: every known node stays visible on a sparse mesh. */
        const val IDLE_CORE = 0.35

        /** The glow no orb ever drops below. */
        const val IDLE_FLOOR = 0.2

        /** Smallest distance between two orb centers, as a fraction of the vertical half-height. */
        const val MIN_SEPARATION = 0.05

        /** The least [nightDim] returns, from 00:00 to 05:00. */
        const val NIGHT_DIM_MIN = 0.65

        private const val ANCHOR_PERIOD_SECONDS = 8.0
        private const val UNLINKED_ANCHOR_PERIOD_SECONDS = 16.0
        private const val UNLINKED_SATURATION = 0.2
        private const val SATURATION_TAU_SECONDS = 3.0
        private const val HUE_TAU_SECONDS = 40.0

        /** Sway speed: [SWAY_RATE] times [SWAY_MOVE] plus [SWAY_MOVE_PER_TEXTURE] per unit of detune and noise. */
        const val SWAY_WIDTH = 0.15
        private const val SWAY_RATE = 0.021
        private const val SWAY_MOVE = 0.4
        private const val SWAY_MOVE_PER_TEXTURE = 1.2

        /** The move at the synth's neutral texture (detune 0.2, noise 0.1), before the first texture arrives. */
        private const val NEUTRAL_SWAY_MOVE = SWAY_MOVE + SWAY_MOVE_PER_TEXTURE * (0.2 + 0.1)
        private const val PAD_TAU_SECONDS = 6.0
        private const val HUE_SPAN = 24.0
        private const val BRIGHTNESS_HUE = 20.0
        private const val NEUTRAL_BRIGHTNESS = 0.5
        private const val MIDDLE_C_HZ = 261.6255653005986
        private const val SEMITONES_PER_OCTAVE = 12.0

        private const val NOON_MINUTE = 12 * 60
        private const val MIN_ASPECT = 0.25
        private const val MAX_ASPECT = 4.0

        /** An aspect change smaller than this does not relayout. */
        private const val ASPECT_RELAYOUT_STEP = 0.01

        /** Fraction of the screen radius the percentile fit reaches; the band beyond it is for outliers. */
        private const val FIT_EDGE = 0.9

        /** The distance percentile that maps to [FIT_EDGE]; the farthest orbs beyond it are outliers. */
        private const val FIT_PERCENTILE = 0.85

        /** Smallest fit radius in meters, so a lone or tightly clustered mesh does not zoom without bound. */
        private const val MIN_FIT_METERS = 50.0

        /** The radius in meters that maps to [FIT_EDGE]: a percentile of the distances, never the maximum. */
        fun fitRadius(distances: List<Double>): Double {
            if (distances.isEmpty()) return MIN_FIT_METERS
            val sorted = distances.sorted()
            val idx = ((sorted.size - 1) * FIT_PERCENTILE).toInt()
            return maxOf(sorted[idx], MIN_FIT_METERS)
        }

        /** Maps a distance to a unit-disc radius: linear to [FIT_EDGE] at [fit], saturating at 1.0 beyond it. */
        fun screenRadius(distance: Double, fit: Double): Double =
            if (distance <= fit) {
                FIT_EDGE * distance / fit
            } else {
                FIT_EDGE + (1.0 - FIT_EDGE) * (1.0 - exp(-(distance - fit) / fit))
            }

        /**
         * Fraction of the full depth reach an orb at the cloud's edge keeps, so the cloud is an ellipsoid with a lumpy
         * rim.
         */
        const val ELLIPSOID_EDGE_DEPTH = 0.15

        // Salts that give each per-node hash its own independent stream.
        private const val DEPTH_SALT_A = 0x2545F4914F6CDD1DL
        private const val DEPTH_SALT_B = 0x6A09E667F3BCC909L
        private const val SEED_ANGLE_SALT = 0x5bd1e995L
        private const val SEED_RADIUS_SALT = 0x1b873593L

        /** A node with no position sits between these fractions of the unit disc's radius. */
        private const val SEED_RADIUS_MIN = 0.25
        private const val SEED_RADIUS_SPREAD = 0.6

        /**
         * Depth -1..1 of [node] for an orb whose planar radius squared is [radiusSquared]: a stable hash of the node
         * number (two uniform draws summed, so most land near the middle) scaled by an ellipsoid profile that shrinks
         * to [ELLIPSOID_EDGE_DEPTH] at the edge of the unit disc.
         */
        fun depthFor(node: Long, radiusSquared: Double): Double {
            val h = unit(Rng.mix(node xor DEPTH_SALT_A)) + unit(Rng.mix(node xor DEPTH_SALT_B)) - 1.0
            val profile = ELLIPSOID_EDGE_DEPTH +
                (1.0 - ELLIPSOID_EDGE_DEPTH) * sqrt((1.0 - radiusSquared).coerceIn(0.0, 1.0))
            return h * profile
        }

        /** Where a node with no position sits: a pure function of the node number, so it never moves between runs. */
        fun seededPosition(node: Long): Pair<Double, Double> {
            val angle = unit(Rng.mix(node xor SEED_ANGLE_SALT)) * 2 * PI
            val radius = SEED_RADIUS_MIN + SEED_RADIUS_SPREAD * unit(Rng.mix(node xor SEED_RADIUS_SALT))
            return Pair(radius * cos(angle), radius * sin(angle))
        }

        private const val MINUTES_PER_DAY = 24 * 60
        private const val DUSK_START_MINUTE = 22 * 60
        private const val NIGHT_END_MINUTE = 5 * 60
        private const val DAWN_END_MINUTE = 7 * 60
        private const val DIM_FADE_MINUTES = 120.0

        /**
         * 1.0 from 07:00 to 22:00, a smoothstep down to [NIGHT_DIM_MIN] by 00:00, held to 05:00, and a smoothstep back
         * up by 07:00, so OLED screens and dark rooms are not held at full light but an evening is not dim.
         */
        fun nightDim(minuteOfDay: Int): Double {
            val m = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
            val day = when {
                m >= DUSK_START_MINUTE -> 1.0 - smoothstep((m - DUSK_START_MINUTE) / DIM_FADE_MINUTES)
                m < NIGHT_END_MINUTE -> 0.0
                m < DAWN_END_MINUTE -> smoothstep((m - NIGHT_END_MINUTE) / DIM_FADE_MINUTES)
                else -> 1.0
            }
            return NIGHT_DIM_MIN + (1.0 - NIGHT_DIM_MIN) * day
        }

        /** The Hermite smoothstep `3t^2 - 2t^3` of [x] clamped to 0..1. */
        private fun smoothstep(x: Double): Double {
            val t = x.coerceIn(0.0, 1.0)
            return t * t * (SMOOTHSTEP_SQUARE_COEFFICIENT - 2 * t)
        }

        private const val SMOOTHSTEP_SQUARE_COEFFICIENT = 3

        /** Bits of a uniform double in 0..1: the top 53 of a 64-bit hash, as `Rng.nextDouble` takes them. */
        private const val UNIT_BITS = 53

        internal fun unit(bits: Long): Double = (bits ushr (Long.SIZE_BITS - UNIT_BITS)) * (1.0 / (1L shl UNIT_BITS))
    }
}
