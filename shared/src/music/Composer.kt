package org.meshtastic.ambient.music

import org.meshtastic.ambient.mesh.GeoPoint
import org.meshtastic.ambient.mesh.LinkState
import org.meshtastic.ambient.mesh.MeshEvent
import org.meshtastic.ambient.mesh.PacketKind
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
    private val latest = HashMap<Quantity, Float>()
    private val pending = ArrayDeque<MeshEvent.PacketHeard>()
    private var sumLat = 0.0
    private var sumLon = 0.0
    private var positioned = 0
    private var swells = 0
    private var forceChord = false
    private var started = false
    private var nextChordFrame = 0L
    private var nextTextureFrame = 0L
    private var lastDay: DayState? = null
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
            }
            is MeshEvent.Environment -> {
                observe(Quantity.Temperature, e.temperatureC, e.atMillis)
                observe(Quantity.Humidity, e.humidityPct, e.atMillis)
                observe(Quantity.Pressure, e.pressureHpa, e.atMillis)
                observe(Quantity.Wind, e.windMps, e.atMillis)
                observe(Quantity.Lux, e.lux, e.atMillis)
            }
            is MeshEvent.Vitals -> crowd.onVitals(e.channelUtilPct)
            is MeshEvent.PacketHeard -> onPacket(e)
        }
    }

    private fun observe(q: Quantity, v: Float?, at: Long) {
        if (v == null || !v.isFinite()) return
        weather.observe(q, v, at)
        latest[q] = v
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
        lastDay = day
        crowd.advanceTo(moment.epochMillis)
        val notes = ArrayList<NoteEvent>()
        val controls = ArrayList<ControlEvent>()
        if (!started) start(nowFrame)

        if (forceChord) {
            nextChordFrame = min(nextChordFrame, nowFrame)
            forceChord = false
        }
        while (nextChordFrame < windowEnd) {
            val at = max(nextChordFrame, nowFrame)
            changeChord(at, day, notes, controls)
            nextChordFrame = at + sec(rng.range(120.0, 480.0))
            nextTextureFrame = at + sec(TEXTURE_SECONDS)
        }
        if (nextTextureFrame < windowEnd) {
            val at = max(nextTextureFrame, nowFrame)
            controls += ControlEvent.SetTexture(at, texture(day, at / sampleRate.toDouble(), moment.epochMillis))
            nextTextureFrame = at + sec(TEXTURE_SECONDS)
        }
        runLoops(nowFrame, windowEnd, day, moment, notes)
        runOrnaments(nowFrame, windowEnd, moment, day, notes)
        if (swells > 0 && !crowd.warmingUp) {
            swells = 0
            val level = form.levels(nowFrame / sampleRate.toDouble()).getValue(Voice.Pad)
            if (level > 0.02f) {
                notes += NoteEvent(
                    nowFrame, Voice.Pad, day.rootHz * Theory.justRatio(chord.rootSemitone) * 2, 0.18f, 0f, 0.8f,
                    sec(6.0), 0.3f,
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
        nextTextureFrame = nowFrame
        for (i in 0..4) loops += Loop(nowFrame + sec(rng.range(1.0, periods[i])), periods[i], i)
        for (i in 5..7) memory += Loop(nowFrame + sec(rng.range(5.0, periods[i])), periods[i], i)
    }

    private fun sec(s: Double): Long = (s * sampleRate).toLong()

    private fun changeChord(at: Long, day: DayState, notes: MutableList<NoteEvent>, controls: MutableList<ControlEvent>) {
        val chord = harmony.next(day.mode)
        val levels = form.levels(at / sampleRate.toDouble())
        val lengthFrames = nextChordFrame.let { sec(rng.range(120.0, 480.0)) }
        controls += ControlEvent.SetTexture(at, texture(day, at / sampleRate.toDouble(), lastMillis()))
        val drone = levels.getValue(Voice.Drone)
        if (drone > 0.02f) {
            notes += NoteEvent(
                at, Voice.Drone, day.rootHz * Theory.justRatio(chord.rootSemitone) / 2, drone * 0.7f, 0f, 0.5f,
                lengthFrames + sec(4.0), 0.2f,
            )
        }
        val pad = levels.getValue(Voice.Pad)
        if (pad > 0.02f) {
            val pans = floatArrayOf(-0.5f, -0.17f, 0.17f, 0.5f)
            chord.voiceSemitones.forEachIndexed { i, s ->
                notes += NoteEvent(
                    at + sec(0.3 * i), Voice.Pad, padHz(day, s), pad * 0.5f, pans[i], 0.6f,
                    lengthFrames + sec(3.0), day.brightness.toFloat(),
                )
            }
        }
    }

    private fun padHz(day: DayState, semitone: Int): Double =
        day.rootHz * Theory.justRatio(semitone) * 2.0.pow(day.registerOctaves)

    private var lastMs = 0L
    private fun lastMillis() = lastMs

    private fun runLoops(nowFrame: Long, windowEnd: Long, day: DayState, moment: LocalMoment, notes: MutableList<NoteEvent>) {
        lastMs = moment.epochMillis
        val chord = harmony.current
        val tonesPerLoop = chord.voiceSemitones
        val crowdDensity = crowd.density(moment.epochMillis)
        val active = (0.6 * day.density + 0.4 * crowdDensity).coerceIn(0.0, 1.0)
        val quiet = crowd.rate < QUIET_RATE && !crowd.warmingUp
        for (loop in loops) {
            while (loop.nextFrame < windowEnd) {
                val at = max(loop.nextFrame, nowFrame)
                val levels = form.levels(at / sampleRate.toDouble())
                val fires = rng.nextDouble() < 0.35 + 0.65 * active
                if (loop.index < 4) {
                    val level = levels.getValue(Voice.Pad)
                    if (fires && level > 0.03f) {
                        val seconds = loop.baseSeconds * 0.4
                        notes += NoteEvent(
                            at, Voice.Pad, padHz(day, tonesPerLoop[loop.index]), level * 0.4f,
                            (loop.index - 1.5f) * 0.4f, 0.7f, sec(seconds), day.brightness.toFloat(),
                        )
                    }
                } else {
                    val level = levels.getValue(Voice.Shimmer)
                    if (fires && level > 0.05f) {
                        notes += NoteEvent(
                            at, Voice.Shimmer, padHz(day, tonesPerLoop[3]) * 4, level * 0.3f, 0f, 0.9f,
                            sec(loop.baseSeconds * 0.3), 0.9f,
                        )
                    }
                }
                loop.nextFrame = at + sec(period(loop.baseSeconds, day))
            }
        }
        for (loop in memory) {
            while (loop.nextFrame < windowEnd) {
                val at = max(loop.nextFrame, nowFrame)
                val level = form.levels(at / sampleRate.toDouble()).getValue(Voice.Bell)
                val recent = nodes.entries.filter { it.value.lastHeard != Long.MIN_VALUE }
                    .sortedByDescending { it.value.lastHeard }.take(3)
                if (quiet && level > 0.05f && recent.isNotEmpty()) {
                    val (id, info) = recent[loop.index % recent.size]
                    notes += NoteEvent(
                        at, Voice.Bell, Ornaments.pitchHz(id, chord, day.rootHz), 0.12f + 0.1f * level,
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

    private fun ornament(e: MeshEvent.PacketHeard, start: Long, deadline: Long, day: DayState, notes: MutableList<NoteEvent>) {
        val art = Ornaments.articulation(e.kind)
        val duration = sec(art.seconds)
        val velocity = Ornaments.velocityFor(e.snr)
        val send = Ornaments.reverbSendFor(e.hopsTaken, e.viaMqtt)
        val brightness = Ornaments.brightnessFor(e.kind, e.hopsTaken, e.viaMqtt)
        val pan = Ornaments.panFor(nodes[e.from]?.position, reference())
        val chord = harmony.current
        if (e.kind == PacketKind.Text) {
            for (i in 0..3) {
                val intended = start + sec(0.35 * i)
                val at = slots.allocate(intended, duration, intended + sec(Ornaments.TTL_SECONDS)) ?: continue
                notes += NoteEvent(at, Voice.Bell, Ornaments.pitchHz(i, 1, chord, day.rootHz), velocity, pan, send, duration, brightness)
            }
            return
        }
        val pitch = if (e.kind == PacketKind.Encrypted) Ornaments.BREATH_HZ else Ornaments.pitchHz(e.from, chord, day.rootHz)
        val first = slots.allocate(start, duration, deadline) ?: return
        notes += NoteEvent(first, art.voice, pitch, velocity, pan, send, duration, brightness)
        if (e.kind == PacketKind.Encrypted) return
        for (k in 1..Ornaments.echoesFor(e.hopsTaken, e.viaMqtt)) {
            val at = slots.allocate(first + sec(Ornaments.ECHO_DELAY_SECONDS * k), duration, deadline) ?: break
            notes += NoteEvent(
                at, art.voice, pitch, velocity * 0.55f.pow(k), pan, min(1f, send + 0.1f * k), duration, brightness * 0.8f,
            )
        }
    }

    private fun texture(day: DayState, tSec: Double, nowMs: Long): Texture {
        fun scaled(q: Quantity): Double = latest[q]?.let { weather.scale(q, it).toDouble() } ?: 0.5
        val levels = form.levels(tSec).toMutableMap()
        val density = crowd.density(nowMs)
        levels[Voice.Pad] = min(1f, levels.getValue(Voice.Pad) * (1f + 0.4f * density.toFloat()))
        val decayN = (day.decayScale - 1.0) / 0.6
        val humidity = scaled(Quantity.Humidity)
        return Texture(
            rootHz = day.rootHz,
            brightness = unit(0.3 * scaled(Quantity.Temperature) + 0.4 * day.brightness + 0.3 * crowd.brightness),
            reverbSize = unit(0.3 + 0.4 * humidity + 0.3 * decayN),
            reverbWet = unit(0.3 + 0.4 * humidity + 0.2 * decayN),
            detune = unit(0.1 + 0.5 * (1 - scaled(Quantity.Pressure))),
            shimmer = unit(0.5 * scaled(Quantity.Lux) + 0.5 * levels.getValue(Voice.Shimmer)),
            noise = unit(0.1 + 0.5 * scaled(Quantity.Wind) + 0.2 * crowd.tension),
            layerLevels = levels,
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
    }
}
