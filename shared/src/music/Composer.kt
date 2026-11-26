package org.meshtastic.ambient.music

import org.meshtastic.ambient.mesh.GeoPoint
import org.meshtastic.ambient.mesh.LinkState
import org.meshtastic.ambient.mesh.MeshEvent
import org.meshtastic.ambient.mesh.PacketKind
import org.meshtastic.ambient.mesh.resolveRelay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The autonomous piece plus the mesh's perturbations. Pure and deterministic given (events, calls, seed).
 * Call [onEvent] as events arrive and [advance] on a timer; each call fills frames up to `nowFrame + lookahead`.
 */
class Composer(seed: Long, private val sampleRate: Int) {
    /** Our own node's position when known; pans are relative to it, else to the centroid of known nodes. */
    var origin: GeoPoint? = null

    val chord: Chord get() = harmony.current

    private class NodeInfo(var position: GeoPoint?, var lastHeard: Long)
    private class Loop(var nextFrame: Long, val baseSeconds: Double, val index: Int)

    private val rng = Rng.stream(seed, 3)
    private val harmony = Harmony(seed)
    private val form = Form(seed)
    private val crowd = Crowd()
    private val weather = WeatherScaler()
    private val slots = SlotAllocator(Ornaments.SLOT_CAP)
    private val nodes = HashMap<Long, NodeInfo>()
    private class Reading(val value: Float, val atMillis: Long)
    private val readings = HashMap<Long, HashMap<Quantity, Reading>>()
    private val pending = ArrayDeque<MeshEvent.PacketHeard>()
    private var selfNode: Long? = null
    private var lastDyadMs = Long.MIN_VALUE
    private val pendingDyads = ArrayList<Pair<Long, Long>>()
    private val pendingRoutes = ArrayList<List<Long>>()
    private var sumLat = 0.0
    private var sumLon = 0.0
    private var positioned = 0
    private var swells = 0
    private var forceChord = false
    private var started = false
    private var nextChordFrame = 0L
    private var lastChordFrame = 0L
    private var nextTickFrame = 0L
    private var nextTextureFrame = 0L
    private var keySemitones = Double.NaN
    private var keyHz = Theory.C3_HZ
    private val periods = PERIODS.toMutableList().also { list ->
        for (i in list.lastIndex downTo 1) list.swap(i, rng.nextInt(i + 1))
    }
    private val loops = ArrayList<Loop>()
    private val memory = ArrayList<Loop>()

    fun onEvent(e: MeshEvent) {
        when (e) {
            is MeshEvent.Link -> crowd.warmingUp = e.state == LinkState.WarmingUp
            is MeshEvent.NodeSeen -> {
                val info = nodes.getOrPut(e.node) { NodeInfo(null, Long.MIN_VALUE) }
                info.position?.let { sumLat -= it.lat; sumLon -= it.lon; positioned-- }
                info.position = e.position
                e.position?.let { sumLat += it.lat; sumLon += it.lon; positioned++ }
                if (e.firstThisSession && !crowd.warmingUp) swells++
            }
            is MeshEvent.NodeGone -> {
                nodes.remove(e.node)?.position?.let { sumLat -= it.lat; sumLon -= it.lon; positioned-- }
                crowd.forget(e.node)
                readings.remove(e.node)
            }
            is MeshEvent.Environment -> {
                observe(e.node, Quantity.Temperature, e.temperatureC, e.atMillis)
                observe(e.node, Quantity.Humidity, e.humidityPct, e.atMillis)
                observe(e.node, Quantity.Pressure, e.pressureHpa, e.atMillis)
                observe(e.node, Quantity.Wind, e.windMps, e.atMillis)
                observe(e.node, Quantity.Lux, e.lux, e.atMillis)
            }
            is MeshEvent.Vitals -> crowd.onVitals(e.channelUtilPct)
            is MeshEvent.Self -> {
                selfNode = e.node
                e.position?.let { origin = it }
            }
            is MeshEvent.Neighbors -> onNeighbors(e)
            is MeshEvent.Route -> if (!crowd.warmingUp && e.nodes.isNotEmpty()) {
                pendingRoutes += e.nodes.take(MAX_ROUTE_NODES)
            }
            is MeshEvent.PacketHeard -> onPacket(e)
        }
    }

    private fun observe(node: Long, q: Quantity, v: Float?, at: Long) {
        if (v == null || !v.isFinite()) return
        weather.observe(q, v, at)
        readings.getOrPut(node) { HashMap() }[q] = Reading(v, at)
    }

    /** Median of each node's latest reading of [q], over the nodes that reported within the last hour. */
    private fun weatherNow(q: Quantity, nowMs: Long): Float? {
        val values = readings.values.mapNotNull { it[q] }.filter { nowMs - it.atMillis <= WEATHER_MS }
            .map { it.value }.sorted()
        if (values.isEmpty()) return null
        val mid = values.size / 2
        return if (values.size % 2 == 1) values[mid] else (values[mid - 1] + values[mid]) / 2f
    }

    /** At most one dyad a minute: the reporter and the first neighbor the composer already knows. */
    private fun onNeighbors(e: MeshEvent.Neighbors) {
        if (crowd.warmingUp) return
        if (lastDyadMs != Long.MIN_VALUE && e.atMillis - lastDyadMs < DYAD_MS) return
        val other = e.neighbors.firstOrNull { it != e.node && it in nodes } ?: return
        lastDyadMs = e.atMillis
        pendingDyads += e.node to other
    }

    private fun onPacket(e: MeshEvent.PacketHeard) {
        if (crowd.warmingUp) return
        crowd.onPacket(e.atMillis, e.from, e.snr)
        nodes.getOrPut(e.from) { NodeInfo(null, Long.MIN_VALUE) }.lastHeard = e.atMillis
        if (e.kind == PacketKind.Text) forceChord = true
        val keep = crowd.fastRate <= Ornaments.THIN_RATE || rng.nextDouble() < Ornaments.THIN_RATE / crowd.fastRate
        if (!keep && e.kind != PacketKind.Text) return
        pending.addLast(e)
        if (pending.size > MAX_PENDING) pending.removeFirst()
    }

    fun advance(nowFrame: Long, lookaheadFrames: Long, moment: LocalMoment): ComposerOutput {
        val windowEnd = nowFrame + lookaheadFrames
        val day = Day.at(moment)
        crowd.advanceTo(moment.epochMillis)
        val notes = ArrayList<NoteEvent>()
        val controls = ArrayList<ControlEvent>()
        if (!started) start(nowFrame)

        while (nextTickFrame < windowEnd) {
            val at = max(nextTickFrame, nowFrame)
            runLoops(nowFrame, at, day, moment, notes)
            tick(at, day, moment, notes, controls)
        }
        if (nextTextureFrame < windowEnd) {
            val at = max(nextTextureFrame, nowFrame)
            controls += ControlEvent.SetTexture(at, texture(day, at / sampleRate.toDouble(), moment.epochMillis))
            nextTextureFrame = at + sec(TEXTURE_SECONDS)
        }
        runLoops(nowFrame, windowEnd, day, moment, notes)
        runOrnaments(nowFrame, windowEnd, moment, day, notes)
        runMeshPhrases(nowFrame, day, notes)
        if (swells > 0 && !crowd.warmingUp) {
            swells = 0
            val level = form.levels(nowFrame / sampleRate.toDouble()).getValue(Voice.Pad)
            val length = clip(nowFrame, sec(6.0))
            if (level > 0.02f && length > 0) {
                notes += NoteEvent(
                    nowFrame, Voice.Pad, keyHz * Theory.justRatio(chord.rootSemitone) * 2, 0.18f, 0f, 0.8f,
                    length, 0.3f,
                )
            }
        }
        notes.sortBy { it.atFrame }
        controls.sortBy { it.atFrame }
        return ComposerOutput(notes, controls)
    }

    private fun start(nowFrame: Long) {
        started = true
        nextChordFrame = nowFrame
        nextTickFrame = nowFrame
        nextTextureFrame = nowFrame
        for (i in 0..4) loops += Loop(nowFrame + sec(rng.range(1.0, periods[i])), periods[i], i)
        for (i in 5..7) memory += Loop(nowFrame + sec(rng.range(5.0, periods[i])), periods[i], i)
    }

    private fun sec(s: Double): Long = (s * sampleRate).toLong()

    /** Length of a bed note starting at [start] cut at the next tick, the only place a chord or key can change. */
    private fun clip(start: Long, wanted: Long): Long = min(wanted, nextTickFrame - start)

    /**
     * Every [SWELL_SECONDS] the drone and pad are struck again in the current chord, and each bed note ends at the
     * next tick, so a chord or key change never sits on top of the one before. A chord changes here when its one
     * duration draw has run out, when the day's key has changed, or when a text asked for it and the last change was
     * at least [MIN_FORCED_SECONDS] ago.
     */
    private fun tick(at: Long, day: DayState, moment: LocalMoment, notes: MutableList<NoteEvent>, controls: MutableList<ControlEvent>) {
        val keyChanged = day.rootSemitones != keySemitones
        val textDue = forceChord && at - lastChordFrame >= sec(MIN_FORCED_SECONDS)
        if (!chordStarted || keyChanged || textDue || at >= nextChordFrame) {
            keySemitones = day.rootSemitones
            keyHz = day.rootHz
            harmony.next(day.mode)
            chordStarted = true
            forceChord = false
            lastChordFrame = at
            nextChordFrame = at + sec(rng.range(120.0, 480.0))
            nextTextureFrame = at + sec(TEXTURE_SECONDS)
            controls += ControlEvent.SetTexture(at, texture(day, at / sampleRate.toDouble(), moment.epochMillis))
        }
        nextTickFrame = at + sec(SWELL_SECONDS)
        val chord = harmony.current
        val levels = form.levels(at / sampleRate.toDouble())
        if (levels.getValue(Voice.Drone) > 0.02f) {
            notes += NoteEvent(
                at, Voice.Drone, keyHz * Theory.justRatio(chord.rootSemitone) / 2, DRONE_VELOCITY, 0f, 0.5f,
                clip(at, sec(SWELL_SECONDS)), 0.2f,
            )
        }
        if (levels.getValue(Voice.Pad) > 0.02f) {
            val pans = floatArrayOf(-0.5f, -0.17f, 0.17f, 0.5f)
            chord.voiceSemitones.forEachIndexed { i, s ->
                val start = at + sec(0.3 * i)
                notes += NoteEvent(
                    start, Voice.Pad, padHz(day, s), PAD_VELOCITY, pans[i], 0.6f,
                    clip(start, sec(SWELL_SECONDS)), day.brightness.toFloat(),
                )
            }
        }
    }

    private var chordStarted = false

    private fun padHz(day: DayState, semitone: Int): Double =
        keyHz * Theory.justRatio(semitone) * 2.0.pow(day.registerOctaves)

    private fun runLoops(nowFrame: Long, to: Long, day: DayState, moment: LocalMoment, notes: MutableList<NoteEvent>) {
        val chord = harmony.current
        val tonesPerLoop = chord.voiceSemitones
        val crowdDensity = crowd.density(moment.epochMillis)
        val active = (0.6 * day.density + 0.4 * crowdDensity).coerceIn(0.0, 1.0)
        val quiet = crowd.rate < QUIET_RATE && !crowd.warmingUp
        for (loop in loops) {
            while (loop.nextFrame < to) {
                val at = max(loop.nextFrame, nowFrame)
                val levels = form.levels(at / sampleRate.toDouble())
                val fires = rng.nextDouble() < 0.35 + 0.65 * active
                if (loop.index < 4) {
                    val length = clip(at, sec(loop.baseSeconds * 0.4))
                    if (fires && levels.getValue(Voice.Pad) > 0.03f && length >= sec(MIN_LOOP_NOTE_SECONDS)) {
                        notes += NoteEvent(
                            at, Voice.Pad, padHz(day, tonesPerLoop[loop.index]), 0.4f,
                            (loop.index - 1.5f) * 0.4f, 0.7f, length, day.brightness.toFloat(),
                        )
                    }
                } else {
                    val length = clip(at, sec(loop.baseSeconds * 0.3))
                    if (fires && levels.getValue(Voice.Shimmer) > 0.05f && length >= sec(MIN_LOOP_NOTE_SECONDS)) {
                        notes += NoteEvent(
                            at, Voice.Shimmer, padHz(day, tonesPerLoop[3]), 0.3f, 0f, 0.9f, length, 0.9f,
                        )
                    }
                }
                loop.nextFrame = at + sec(period(loop.baseSeconds, day))
            }
        }
        for (loop in memory) {
            while (loop.nextFrame < to) {
                val at = max(loop.nextFrame, nowFrame)
                val recent = nodes.entries.filter { it.value.lastHeard != Long.MIN_VALUE }
                    .sortedByDescending { it.value.lastHeard }.take(3)
                if (quiet && recent.isNotEmpty()) {
                    val (id, info) = recent[loop.index % recent.size]
                    notes += NoteEvent(
                        at, Voice.Bell, Ornaments.pitchHz(id, chord, keyHz), MEMORY_VELOCITY,
                        Ornaments.panFor(info.position, reference()), 0.7f, sec(3.0), 0.3f,
                    )
                }
                loop.nextFrame = at + sec(period(loop.baseSeconds, day))
            }
        }
    }

    private fun period(base: Double, day: DayState): Double = (base * (1.0 + 0.3 * (1 - day.density))).coerceIn(17.0, 60.0)

    private fun reference(): GeoPoint? =
        origin ?: if (positioned > 0) GeoPoint(sumLat / positioned, sumLon / positioned) else null

    private fun runOrnaments(nowFrame: Long, windowEnd: Long, moment: LocalMoment, day: DayState, notes: MutableList<NoteEvent>) {
        val ttl = sec(Ornaments.TTL_SECONDS)
        val grid = sec(Ornaments.GRID_SECONDS)
        val it = pending.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val ageMs = moment.epochMillis - e.atMillis
            if (ageMs > DAY_MS) {
                it.remove()
                continue
            }
            val arrival = nowFrame - ageMs.coerceAtLeast(-DAY_MS) * sampleRate / 1000
            if (arrival >= windowEnd) continue
            it.remove()
            val start = max(-((-arrival).floorDiv(grid)) * grid + ((rng.nextDouble() - 0.5) * 0.02 * sampleRate).toLong(), nowFrame)
            ornament(e, start, arrival + ttl, day, notes)
        }
    }

    private fun runMeshPhrases(nowFrame: Long, day: DayState, notes: MutableList<NoteEvent>) {
        val chord = harmony.current
        val padLevel = form.levels(nowFrame / sampleRate.toDouble()).getValue(Voice.Pad)
        for ((a, b) in pendingDyads) {
            val length = clip(nowFrame, sec(DYAD_SECONDS))
            if (padLevel <= 0.02f || length <= 0) continue
            for (node in listOf(a, b)) {
                notes += NoteEvent(
                    nowFrame, Voice.Pad, padHz(day, chord.roleSemitone(Ornaments.role(node))), DYAD_VELOCITY,
                    Ornaments.panFor(nodes[node]?.position, reference()), 0.7f, length, 0.3f,
                )
            }
        }
        pendingDyads.clear()
        val ttl = sec(Ornaments.TTL_SECONDS)
        val duration = sec(ROUTE_NOTE_SECONDS)
        for (route in pendingRoutes) {
            route.forEachIndexed { i, node ->
                val intended = nowFrame + sec(ROUTE_STEP_SECONDS * i)
                val at = slots.allocate(intended, duration, intended + ttl) ?: return@forEachIndexed
                notes += NoteEvent(
                    at, Voice.Bell, Ornaments.pitchHz(node, chord, keyHz), ROUTE_VELOCITY,
                    Ornaments.panFor(nodes[node]?.position, reference()), 0.5f, duration, 0.5f,
                )
            }
        }
        pendingRoutes.clear()
    }

    private fun ornament(e: MeshEvent.PacketHeard, start: Long, deadline: Long, day: DayState, notes: MutableList<NoteEvent>) {
        val art = Ornaments.articulation(e.kind)
        val call = e.toUs
        val duration = sec(if (call) art.seconds * Ornaments.CALL_LENGTH_SCALE else art.seconds)
        val baseVelocity = Ornaments.velocityFor(e.snr)
        val velocity = if (call) min(1f, baseVelocity + Ornaments.CALL_VELOCITY_LIFT) else baseVelocity
        val baseSend = Ornaments.reverbSendFor(e.hopsTaken, e.viaMqtt)
        val send = when {
            call -> max(0f, baseSend - Ornaments.CALL_DRYNESS)
            e.to == Ornaments.BROADCAST -> min(1f, baseSend + Ornaments.BROADCAST_WIDEN)
            else -> baseSend
        }
        val baseBrightness = Ornaments.brightnessFor(e.kind, e.hopsTaken, e.viaMqtt)
        val brightness = if (call) min(1f, baseBrightness + Ornaments.CALL_BRIGHTNESS_LIFT) else baseBrightness
        val pan = Ornaments.panFor(nodes[e.from]?.position, reference())
        val chord = harmony.current
        if (e.kind == PacketKind.Text) {
            for (i in 0..3) {
                val intended = start + sec(0.35 * i)
                val at = slots.allocate(intended, duration, intended + sec(Ornaments.TTL_SECONDS)) ?: continue
                notes += NoteEvent(at, Voice.Bell, Ornaments.pitchHz(i, 1, chord, keyHz), velocity, pan, send, duration, brightness)
            }
            return
        }
        val pitch = if (e.kind == PacketKind.Encrypted) Ornaments.BREATH_HZ else Ornaments.pitchHz(e.from, chord, keyHz)
        val first = slots.allocate(start, duration, deadline) ?: return
        notes += NoteEvent(first, art.voice, pitch, velocity, pan, send, duration, brightness)
        if (e.kind == PacketKind.Encrypted) return
        val relay = resolveRelay(nodes.keys, e.relayByte, e.from, selfNode)
        for (k in 1..Ornaments.echoesFor(e.hopsTaken, e.viaMqtt)) {
            val at = slots.allocate(first + sec(Ornaments.ECHO_DELAY_SECONDS * k), duration, deadline) ?: break
            val viaRelay = relay != null && k % 2 == 1
            notes += NoteEvent(
                at, art.voice, if (viaRelay) Ornaments.pitchHz(relay, chord, keyHz) else pitch, velocity * 0.55f.pow(k),
                if (viaRelay) Ornaments.panFor(nodes[relay]?.position, reference()) else pan,
                min(1f, send + 0.1f * k), duration, brightness * 0.8f,
            )
        }
    }

    private fun texture(day: DayState, tSec: Double, nowMs: Long): Texture {
        fun scaled(q: Quantity): Double = weatherNow(q, nowMs)?.let { weather.scale(q, it).toDouble() } ?: 0.5
        val levels = form.levels(tSec)
        val density = crowd.density(nowMs)
        val pad = min(1f, levels.getValue(Voice.Pad) * (1f + 0.4f * density.toFloat()))
        val decayN = (day.decayScale - 1.0) / 0.6
        val humidity = scaled(Quantity.Humidity)
        return Texture(
            rootHz = keyHz,
            brightness = unit(0.3 * scaled(Quantity.Temperature) + 0.4 * day.brightness + 0.3 * crowd.brightness),
            reverbSize = unit(0.3 + 0.4 * humidity + 0.3 * decayN),
            reverbWet = unit(0.3 + 0.4 * humidity + 0.2 * decayN),
            detune = unit(0.1 + 0.5 * (1 - scaled(Quantity.Pressure))),
            shimmer = unit(0.5 * scaled(Quantity.Lux) + 0.5 * levels.getValue(Voice.Shimmer)),
            noise = unit(0.1 + 0.5 * scaled(Quantity.Wind) + 0.2 * crowd.tension),
            layerLevels = mapOf(
                Voice.Drone to levels.getValue(Voice.Drone),
                Voice.Pad to pad,
                Voice.Shimmer to levels.getValue(Voice.Shimmer),
            ),
        )
    }

    private fun unit(x: Double): Float = x.coerceIn(0.0, 1.0).toFloat()

    private fun <T> MutableList<T>.swap(i: Int, j: Int) {
        val t = this[i]
        this[i] = this[j]
        this[j] = t
    }

    private companion object {
        val PERIODS = listOf(17.0, 19.0, 23.0, 29.0, 31.0, 37.0, 41.0, 43.0, 47.0, 53.0, 59.0)
        const val TEXTURE_SECONDS = 5.0
        const val QUIET_RATE = 0.1
        const val MAX_PENDING = 512
        const val DAY_MS = 24L * 3600 * 1000
        const val WEATHER_MS = 3600L * 1000
        const val SWELL_SECONDS = 20.0
        const val MIN_FORCED_SECONDS = 45.0
        const val MIN_LOOP_NOTE_SECONDS = 1.0
        const val DRONE_VELOCITY = 0.7f
        const val PAD_VELOCITY = 0.5f
        const val MEMORY_VELOCITY = 0.15f
        const val DYAD_MS = 60_000L
        const val DYAD_SECONDS = 4.0
        const val DYAD_VELOCITY = 0.25f
        const val MAX_ROUTE_NODES = 8
        const val ROUTE_STEP_SECONDS = 0.3
        const val ROUTE_NOTE_SECONDS = 0.8
        const val ROUTE_VELOCITY = 0.45f
    }
}
