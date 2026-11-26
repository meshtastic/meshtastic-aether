package org.meshtastic.ambient.visual

import org.meshtastic.ambient.mesh.GeoPoint
import org.meshtastic.ambient.mesh.LinkState
import org.meshtastic.ambient.mesh.MeshEvent
import org.meshtastic.ambient.mesh.PacketKind
import org.meshtastic.ambient.music.Ornaments
import org.meshtastic.ambient.music.Rng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/** One node on screen. [x] and [y] are laid out in the unit disc around our node, x right, y down. */
class Orb internal constructor(val node: Long, var name: String?, internal var position: GeoPoint?) {
    var x = 0.0
        internal set
    var y = 0.0
        internal set
    internal var lastActiveFrame = NEVER
    internal var seenFrame = 0L

    companion object {
        internal const val NEVER = Long.MIN_VALUE
    }
}

/** A ring from an orb. [echo] 0 is the packet itself, 1.. are the hop echoes, fainter and later. */
class Ripple internal constructor(
    val node: Long,
    val startFrame: Long,
    val kind: PacketKind,
    val strength: Float,
    val echo: Int,
)

private class Pending(val dueFrame: Long, val node: Long, val kind: PacketKind, val strength: Float, val echo: Int)

/**
 * The scene as pure state on the audio clock: nothing here reads a wall clock or a canvas. The caller feeds events
 * with the frame their note will sound at ([onEvent]) and calls [update] with the sink's playback frame; a ripple
 * exists only once that frame has been reached, so sight and sound agree.
 */
class SceneModel(private val sampleRate: Int) {
    /** Our own node's position when known; else the layout centres on the centroid of positioned nodes. */
    var origin: GeoPoint? = null
        set(value) {
            field = value
            dirty = true
        }

    /** Local minute of day, for the night dimming. */
    var minuteOfDay: Int = 12 * 60

    private val orbMap = LinkedHashMap<Long, Orb>()
    private val pending = ArrayList<Pending>()
    private val active = ArrayList<Ripple>()
    private var dirty = false
    private var warming = false
    private var lastFrame = 0L

    /** Encrypted traffic as a faint haze, 0..1. */
    var haze = 0.0
        private set

    val orbs: Collection<Orb> get() = orbMap.values
    val ripples: List<Ripple> get() = active
    val dim: Double get() = nightDim(minuteOfDay)

    fun orb(node: Long): Orb? = orbMap[node]

    /** Queues [e]; for a packet, [dueFrame] is when its note sounds. Packets during warm-up are state, not sound. */
    fun onEvent(e: MeshEvent, dueFrame: Long) {
        when (e) {
            is MeshEvent.Link -> warming = e.state == LinkState.WarmingUp
            is MeshEvent.NodeSeen -> {
                val orb = orbMap.getOrPut(e.node) { Orb(e.node, null, null).also { it.seenFrame = lastFrame } }
                orb.name = e.name
                orb.position = e.position
                dirty = true
            }
            is MeshEvent.NodeGone -> if (orbMap.remove(e.node) != null) dirty = true
            is MeshEvent.PacketHeard -> if (!warming) enqueue(e, dueFrame)
            is MeshEvent.Environment, is MeshEvent.Vitals -> Unit
        }
    }

    private fun enqueue(e: MeshEvent.PacketHeard, dueFrame: Long) {
        val strength = Ornaments.velocityFor(e.snr)
        if (e.kind == PacketKind.Encrypted) {
            add(Pending(dueFrame, e.from, e.kind, strength, 0))
            return
        }
        if (!orbMap.containsKey(e.from)) {
            orbMap[e.from] = Orb(e.from, null, null).also { it.seenFrame = lastFrame }
            dirty = true
        }
        add(Pending(dueFrame, e.from, e.kind, strength, 0))
        for (k in 1..Ornaments.echoesFor(e.hopsTaken, e.viaMqtt)) {
            val at = dueFrame + (Ornaments.ECHO_DELAY_SECONDS * k * sampleRate).toLong()
            add(Pending(at, e.from, e.kind, strength * 0.5f, k))
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
        active.removeAll { (playbackFrame - it.startFrame).toDouble() / sampleRate > RIPPLE_LIFE_SECONDS }
        if (dirty) layout()
    }

    private fun spawn(p: Pending) {
        if (p.kind == PacketKind.Encrypted) {
            haze = (haze + HAZE_STEP).coerceAtMost(1.0)
            return
        }
        orbMap[p.node]?.lastActiveFrame = p.dueFrame
        active += Ripple(p.node, p.dueFrame, p.kind, p.strength, p.echo)
        if (active.size > MAX_RIPPLES) active.removeAt(0)
    }

    /** Glow 0..1 of [orb] now: a flash that decays over seconds, over a floor that itself fades with long silence. */
    fun glow(orb: Orb): Double {
        val reference = if (orb.lastActiveFrame == Orb.NEVER) orb.seenFrame else orb.lastActiveFrame
        val since = (lastFrame - reference).coerceAtLeast(0L).toDouble() / sampleRate
        val floor = IDLE_GLOW * exp(-since / IDLE_FADE_SECONDS)
        if (orb.lastActiveFrame == Orb.NEVER) return floor
        return floor + (1.0 - IDLE_GLOW) * exp(-since / FLASH_TAU_SECONDS)
    }

    /** 0..1 progress of [r] through its life. */
    fun progress(r: Ripple): Double =
        ((lastFrame - r.startFrame).toDouble() / sampleRate / RIPPLE_LIFE_SECONDS).coerceIn(0.0, 1.0)

    /** Slow drift of an orb around its laid-out place, in unit-disc units; the whole field wanders against burn-in. */
    fun driftX(node: Long, tSeconds: Double): Double = DRIFT * sin(tSeconds * 0.037 + phase(node, 1))

    fun driftY(node: Long, tSeconds: Double): Double = DRIFT * cos(tSeconds * 0.029 + phase(node, 2))

    private fun layout() {
        dirty = false
        val ref = origin ?: centroid()
        val placed = ArrayList<Orb>()
        val radii = ArrayList<Double>()
        for (orb in orbMap.values) {
            val pos = orb.position
            if (pos == null || ref == null) {
                val seeded = seededPosition(orb.node)
                orb.x = seeded.first
                orb.y = seeded.second
            } else {
                placed += orb
                val east = (pos.lon - ref.lon) * cos(ref.lat * PI / 180.0) * METERS_PER_DEGREE
                val north = (pos.lat - ref.lat) * METERS_PER_DEGREE
                orb.x = east
                orb.y = -north
                radii += hypot(east, north)
            }
        }
        if (placed.isEmpty()) return
        val fit = fitRadius(radii)
        for (orb in placed) {
            val r = hypot(orb.x, orb.y)
            if (r < 1e-9) {
                orb.x = 0.0
                orb.y = 0.0
                continue
            }
            val s = screenRadius(r, fit) / r
            orb.x *= s
            orb.y *= s
        }
    }

    private fun centroid(): GeoPoint? {
        var lat = 0.0
        var lon = 0.0
        var n = 0
        for (orb in orbMap.values) {
            val p = orb.position ?: continue
            lat += p.lat
            lon += p.lon
            n++
        }
        return if (n == 0) null else GeoPoint(lat / n, lon / n)
    }

    companion object {
        const val RIPPLE_LIFE_SECONDS = 6.0
        const val MAX_PENDING = 256
        const val MAX_RIPPLES = 96

        private const val HAZE_TAU_SECONDS = 8.0
        private const val HAZE_STEP = 0.12
        private const val IDLE_GLOW = 0.14
        private const val IDLE_FADE_SECONDS = 900.0
        private const val FLASH_TAU_SECONDS = 12.0
        private const val DRIFT = 0.012
        private const val METERS_PER_DEGREE = 111_320.0

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

        /** Where a node with no position sits: a pure function of the node number, so it never moves between runs. */
        fun seededPosition(node: Long): Pair<Double, Double> {
            val angle = unit(Rng.mix(node xor 0x5bd1e995L)) * 2 * PI
            val radius = 0.25 + 0.6 * unit(Rng.mix(node xor 0x1b873593L))
            return Pair(radius * cos(angle), radius * sin(angle))
        }

        /** 1.0 in the afternoon, down to 0.45 at 02:00, so OLED screens and dark rooms are not held at full light. */
        fun nightDim(minuteOfDay: Int): Double {
            val phase = 2 * PI * ((minuteOfDay.coerceIn(0, 1439) / 1440.0) - 14.0 / 24.0)
            return 0.725 + 0.275 * cos(phase)
        }

        private fun unit(bits: Long): Double = (bits ushr 11) * (1.0 / (1L shl 53))

        private fun phase(node: Long, salt: Int): Double = unit(Rng.mix(node + salt)) * 2 * PI
    }
}
