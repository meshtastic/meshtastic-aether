package org.meshtastic.ambient.music

import org.meshtastic.ambient.mesh.GeoPoint
import org.meshtastic.ambient.mesh.MeshEvent
import org.meshtastic.ambient.mesh.PacketKind
import org.meshtastic.ambient.mesh.resolveRelay
import org.meshtastic.ambient.mood.MeshMood
import org.meshtastic.ambient.mood.MoodSnapshot
import org.meshtastic.ambient.mood.ParamValues
import kotlin.math.max
import kotlin.math.min
import kotlin.math.hypot
import kotlin.math.cos
import kotlin.math.pow

/**
 * The autonomous piece plus the mesh's perturbations. Pure and deterministic given (events, calls, seed).
 * Call [onEvent] as events arrive and [advance] on a timer; each call fills frames up to `nowFrame + lookahead`.
 * [pinFormFull] holds the long form at its full level, for the demo.
 */
class Composer(seed: Long, private val sampleRate: Int, pinFormFull: Boolean = false) {
    /** Our own node's position when known; pans are relative to it, else to the centroid of known nodes. */
    var origin: GeoPoint? = null

    val chord: Chord get() = harmony.current

    /** The mesh mood as of the last [advance]; neutral before the first. */
    fun moodSnapshot(): MoodSnapshot = snapshot

    /** The mesh-driven parameters as of the last [advance]: what the engine hands to the scene and overlay. */
    fun params(): ParamValues = paramOverride ?: snapshot.params

    /** Test seam: when set, [params] returns this instead of the mood's. */
    internal var paramOverride: ParamValues? = null

    /** Test seam: the voice a node's packets play, by node, role and hardware; the palette in production. */
    internal var voicePicker: (Long, String?, String?) -> Voice = { node, role, hardware -> Palette.voiceFor(role, hardware, node) }

    private class NodeInfo(var position: GeoPoint?, var lastHeard: Long)

    /** What the mesh has told us about one node besides its position; every field is null until reported. */
    private class NodeTraits {
        var role: String? = null
        var hardware: String? = null
        var batteryPct: Int? = null
        var voltage: Float? = null
        var uptimeSeconds: Long? = null
        var altitudeM: Int? = null
    }
    private class Loop(var nextFrame: Long, val baseSeconds: Double, val index: Int)

    private val rng = Rng.stream(seed, 3)
    private val harmony = Harmony(seed)
    private val form = Form(seed, pinFormFull)
    private val mood = MeshMood()
    private val scales = ScalePicker()
    private var snapshot = MoodSnapshot.NEUTRAL
    private val traits = HashMap<Long, NodeTraits>()
    private var altitudeSum = 0.0
    private var altitudeCount = 0
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
    private var registerShift = 0
    private var lastAnswerFrame = Long.MIN_VALUE / 2
    private var lastGustMs = Long.MIN_VALUE / 2
    private val pendingGusts = ArrayList<Pair<Long, Float>>()
    private val pendingColors = ArrayList<Int>()
    private var lowSwellUntilMs = Long.MIN_VALUE
    private val pressure = PressureWatch()
    private val temperature = Crossing()
    private var nextGestureMs = Long.MIN_VALUE
    private val sessionVoices = ArrayList<Long>()
    private val farewelled = HashSet<Long>()
    private var nextFarewellMs = Long.MIN_VALUE
    private var lastHour = -1
    private var lastMinute = -1
    private var chimeHour = -1
    private var chimeUntilFrame = 0L
    private var keyHz = Theory.C3_HZ
    private val periods = PERIODS.toMutableList().also { list ->
        for (i in list.lastIndex downTo 1) list.swap(i, rng.nextInt(i + 1))
    }
    private val loops = ArrayList<Loop>()
    private val memory = ArrayList<Loop>()

    fun onEvent(e: MeshEvent) {
        mood.onEvent(e)
        when (e) {
            is MeshEvent.Link -> Unit
            is MeshEvent.NodeSeen -> {
                val info = nodes.getOrPut(e.node) { NodeInfo(null, Long.MIN_VALUE) }
                info.position?.let { sumLat -= it.lat; sumLon -= it.lon; positioned-- }
                info.position = e.position
                e.position?.let { sumLat += it.lat; sumLon += it.lon; positioned++ }
                onNodeTraits(e)
                if (e.firstThisSession && !mood.warmingUp) {
                    swells++
                    sessionVoices.remove(e.node)
                    sessionVoices += e.node
                    if (sessionVoices.size > MAX_SESSION_VOICES) sessionVoices.removeAt(0)
                }
            }
            is MeshEvent.NodeGone -> {
                nodes.remove(e.node)?.position?.let { sumLat -= it.lat; sumLon -= it.lon; positioned-- }
                traits.remove(e.node)?.altitudeM?.let { altitudeSum -= it; altitudeCount-- }
                readings.remove(e.node)
                sessionVoices.remove(e.node)
                farewelled.remove(e.node)
            }
            is MeshEvent.Environment -> {
                observe(e.node, Quantity.Temperature, e.temperatureC, e.atMillis)
                observe(e.node, Quantity.Humidity, e.humidityPct, e.atMillis)
                observe(e.node, Quantity.Pressure, e.pressureHpa, e.atMillis)
                observe(e.node, Quantity.Wind, e.windMps, e.atMillis)
                observe(e.node, Quantity.Lux, e.lux, e.atMillis)
            }
            is MeshEvent.Self -> {
                selfNode = e.node
                e.position?.let { origin = it }
            }
            is MeshEvent.Neighbors -> onNeighbors(e)
            is MeshEvent.Route -> if (!mood.warmingUp && e.nodes.isNotEmpty()) {
                pendingRoutes += e.nodes.take(MAX_ROUTE_NODES)
            }
            is MeshEvent.PacketHeard -> onPacket(e)
            is MeshEvent.Health -> traitsOf(e.node).let {
                it.batteryPct = e.batteryPct ?: it.batteryPct
                it.voltage = e.voltage ?: it.voltage
                it.uptimeSeconds = e.uptimeSeconds ?: it.uptimeSeconds
            }
            is MeshEvent.Stats, is MeshEvent.Vitals -> Unit
        }
    }

    private fun traitsOf(node: Long): NodeTraits = traits.getOrPut(node) { NodeTraits() }

    private fun onNodeTraits(e: MeshEvent.NodeSeen) {
        val t = traitsOf(e.node)
        t.role = e.role ?: t.role
        t.hardware = e.hardware ?: t.hardware
        val alt = e.position?.altitudeM
        if (alt != null && alt != t.altitudeM) {
            t.altitudeM?.let { altitudeSum -= it; altitudeCount-- }
            t.altitudeM = alt
            altitudeSum += alt
            altitudeCount++
        }
    }

    private fun meanAltitude(): Double? = if (altitudeCount > 0) altitudeSum / altitudeCount else null

    /** Phrase tempo band 0.5, 0.75, 1 or 1.5: divides motif step length, the answer delay and gap, and the loop period. */
    private fun phraseSpeed(): Double = Tempo.clampSpeed(params().phraseSpeed)

    private fun observe(node: Long, q: Quantity, v: Float?, at: Long) {
        if (v == null || !v.isFinite()) return
        weather.observe(q, v, at)
        if (q == Quantity.Wind && !mood.warmingUp) {
            val prev = readings[node]?.get(q)
            if (prev != null && at - prev.atMillis <= WEATHER_MS && v - prev.value >= GUST_DELTA_MPS) {
                pendingGusts += node to v - prev.value
            }
        }
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
        if (mood.warmingUp) return
        if (lastDyadMs != Long.MIN_VALUE && e.atMillis - lastDyadMs < DYAD_MS) return
        val other = e.neighbors.firstOrNull { it != e.node && it in nodes } ?: return
        lastDyadMs = e.atMillis
        pendingDyads += e.node to other
    }

    private fun onPacket(e: MeshEvent.PacketHeard) {
        if (mood.warmingUp) return
        nodes.getOrPut(e.from) { NodeInfo(null, Long.MIN_VALUE) }.lastHeard = e.atMillis
        farewelled.remove(e.from)
        if (e.kind == PacketKind.Text) forceChord = true
        val fastRate = mood.fastRate
        val keep = fastRate <= Ornaments.THIN_RATE || rng.nextDouble() < Ornaments.THIN_RATE / fastRate
        if (!keep && e.kind != PacketKind.Text) return
        pending.addLast(e)
        if (pending.size > MAX_PENDING) pending.removeFirst()
    }

    fun advance(nowFrame: Long, lookaheadFrames: Long, moment: LocalMoment): ComposerOutput {
        val windowEnd = nowFrame + lookaheadFrames
        val day = Day.at(moment)
        snapshot = mood.advance(moment.epochMillis)
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
        runMeshPhrases(nowFrame, notes)
        runDayEvents(nowFrame, moment, day, notes)
        runGestures(nowFrame, moment, notes)
        runFarewells(nowFrame, moment, notes)
        if (swells > 0 && !mood.warmingUp) {
            swells = 0
            val level = form.levels(nowFrame / sampleRate.toDouble()).getValue(Voice.Pad)
            val length = clip(nowFrame, sec(NEW_NODE_SWELL_SECONDS))
            if (level > 0.02f && length > 0) {
                notes += NoteEvent(
                    nowFrame, Voice.Pad, keyHz * Theory.justRatio(chord.rootSemitone) * 2, NEW_NODE_SWELL_VELOCITY, 0f, 0.8f,
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
            registerShift = nextRegister(day.registerOctaves, chordStarted)
            val p = params()
            harmony.next(scales.pick(day.mode, p.modeLean, p.scalePentatonicBias))
            chordStarted = true
            forceChord = false
            lastChordFrame = at
            nextChordFrame = at + sec(rng.range(CHORD_MIN_SECONDS, CHORD_MAX_SECONDS))
            nextTextureFrame = at + sec(TEXTURE_SECONDS)
            controls += ControlEvent.SetTexture(at, texture(day, at / sampleRate.toDouble(), moment.epochMillis))
        }
        nextTickFrame = at + sec(SWELL_SECONDS)
        val chord = harmony.current
        val levels = form.levels(at / sampleRate.toDouble())
        if (levels.getValue(Voice.Drone) > 0.02f) {
            notes += NoteEvent(
                at, Voice.Drone, keyHz * Theory.justRatio(chord.rootSemitone) / 2, DRONE_VELOCITY, 0f, DRONE_SEND,
                clip(at, sec(SWELL_SECONDS)), DRONE_BRIGHTNESS,
            )
        }
        if (levels.getValue(Voice.Pad) > 0.02f) {
            chord.voiceSemitones.forEachIndexed { i, s ->
                val start = at + sec(PAD_STAGGER_SECONDS * i)
                notes += NoteEvent(
                    start, Voice.Pad, padHz(s), PAD_VELOCITY, PAD_PANS[i], PAD_SEND,
                    clip(start, sec(SWELL_SECONDS)), day.brightness.toFloat(),
                )
            }
            sessionVoices.forEachIndexed { i, node ->
                val start = at + sec(SESSION_STAGGER_SECONDS * (i + 1))
                val length = clip(start, sec(SWELL_SECONDS))
                if (length <= 0) return@forEachIndexed
                notes += NoteEvent(
                    start, Voice.Pad, padHz(chord.roleSemitone(Ornaments.role(node)) + 12), SESSION_VELOCITY,
                    Ornaments.panFor(nodes[node]?.position, reference()), PAD_SEND, length, SESSION_BRIGHTNESS, node,
                )
            }
        }
    }

    private var chordStarted = false

    /** Pads sit whole octaves from the key so they stay in tune with the drone and bells at every minute of the day. */
    private fun padHz(semitone: Int): Double = keyHz * Theory.justRatio(semitone) * 2.0.pow(registerShift)

    /** Whole-octave register for [target] (0 by day to -1 at night): it only flips past [REGISTER_ENTER_LOW] or [REGISTER_ENTER_HIGH]. */
    private fun nextRegister(target: Double, running: Boolean): Int = when {
        !running -> if (target < REGISTER_FIRST_SPLIT) -1 else 0
        target < REGISTER_ENTER_LOW -> -1
        target > REGISTER_ENTER_HIGH -> 0
        else -> registerShift
    }

    /** Label of the form section at [tSeconds], for example `2 - gathering`. */
    fun sectionAt(tSeconds: Double): String {
        var end = 0.0
        var index = 0
        for (minutes in form.sectionMinutes(tSeconds)) {
            end += minutes * 60.0
            if (tSeconds < end) break
            index++
        }
        val energy = form.energy(tSeconds)
        val word = when {
            energy < SECTION_SILENCE_BELOW -> "near silence"
            energy < SECTION_GATHERING_BELOW -> "gathering"
            energy < SECTION_FULL_BELOW -> "full"
            else -> "peak"
        }
        return "${index + 1} - $word"
    }

    private fun runLoops(nowFrame: Long, to: Long, day: DayState, moment: LocalMoment, notes: MutableList<NoteEvent>) {
        val chord = harmony.current
        val tonesPerLoop = chord.voiceSemitones
        val active = (LOOP_DAY_WEIGHT * day.density + (1 - LOOP_DAY_WEIGHT) * snapshot.density).coerceIn(0.0, 1.0)
        val quiet = snapshot.quiet
        for (loop in loops) {
            while (loop.nextFrame < to) {
                val at = max(loop.nextFrame, nowFrame)
                val levels = form.levels(at / sampleRate.toDouble())
                val fires = rng.nextDouble() < LOOP_FIRE_BASE + (1 - LOOP_FIRE_BASE) * active
                if (loop.index < 4) {
                    val length = clip(at, sec(loop.baseSeconds * PAD_LOOP_HOLD))
                    if (fires && levels.getValue(Voice.Pad) > 0.03f && length >= sec(MIN_LOOP_NOTE_SECONDS)) {
                        notes += NoteEvent(
                            at, Voice.Pad, padHz(tonesPerLoop[loop.index]), PAD_LOOP_VELOCITY,
                            (loop.index - 1.5f) * LOOP_PAN_SPREAD, LOOP_SEND, length, day.brightness.toFloat(),
                        )
                    }
                } else {
                    val length = clip(at, sec(loop.baseSeconds * SHIMMER_LOOP_HOLD))
                    if (fires && levels.getValue(Voice.Shimmer) > 0.05f && length >= sec(MIN_LOOP_NOTE_SECONDS)) {
                        notes += NoteEvent(
                            at, Voice.Shimmer, padHz(tonesPerLoop[3]), SHIMMER_LOOP_VELOCITY, 0f, SHIMMER_SEND, length, SHIMMER_BRIGHTNESS,
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
                    val motif = Motifs.of(id)
                    val from = loop.index % (motif.size - FRAGMENT_NOTES + 1)
                    val band = Ornaments.band(id)
                    val pan = Ornaments.panFor(info.position, reference())
                    for (j in 0 until FRAGMENT_NOTES) {
                        val k = from + j
                        val intended = at + sec(if (j == 0) 0.0 else motif.gaps[k])
                        emit(
                            notes, intended, Voice.Bell, Ornaments.pitchHz(motif.roles[k], band, chord, keyHz),
                            MEMORY_VELOCITY, pan, LOOP_SEND, MEMORY_SECONDS, MEMORY_BRIGHTNESS, id,
                        )
                    }
                }
                loop.nextFrame = at + sec(period(loop.baseSeconds, day))
            }
        }
    }

    private fun period(base: Double, day: DayState): Double = Tempo.loopPeriodSeconds(base, day.density, phraseSpeed())

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

    private fun runMeshPhrases(nowFrame: Long, notes: MutableList<NoteEvent>) {
        val chord = harmony.current
        val padLevel = form.levels(nowFrame / sampleRate.toDouble()).getValue(Voice.Pad)
        for ((a, b) in pendingDyads) {
            val length = clip(nowFrame, sec(DYAD_SECONDS))
            if (padLevel <= 0.02f || length <= 0) continue
            for (node in listOf(a, b)) {
                notes += NoteEvent(
                    nowFrame, Voice.Pad, padHz(chord.roleSemitone(Ornaments.role(node))), DYAD_VELOCITY,
                    Ornaments.panFor(nodes[node]?.position, reference()), LOOP_SEND, length, MEMORY_BRIGHTNESS, node,
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
                    Ornaments.panFor(nodes[node]?.position, reference()), ROUTE_SEND, duration, ROUTE_BRIGHTNESS, node,
                )
            }
        }
        pendingRoutes.clear()
    }

    /** Starts a note on a free slot at or after [intended] and within the TTL; false when it was dropped. */
    private fun emit(
        notes: MutableList<NoteEvent>, intended: Long, voice: Voice, pitchHz: Double, velocity: Float, pan: Float,
        send: Float, seconds: Double, brightness: Float, node: Long?,
    ): Boolean {
        val duration = sec(seconds)
        val at = slots.allocate(intended, duration, intended + sec(Ornaments.TTL_SECONDS)) ?: return false
        notes += NoteEvent(at, voice, pitchHz, velocity, pan, send, duration, brightness, node)
        return true
    }

    private fun ornament(e: MeshEvent.PacketHeard, start: Long, deadline: Long, day: DayState, notes: MutableList<NoteEvent>) {
        val art = Ornaments.articulation(e.kind)
        val tr = traits[e.from]
        val breath = e.kind == PacketKind.Encrypted
        val voice = if (breath) Voice.Breath else voicePicker(e.from, tr?.role, tr?.hardware)
        val call = e.toUs
        val lengthScale = Traits.uptimeLengthScale(tr?.uptimeSeconds)
        val duration = sec(art.seconds * lengthScale * (if (call) Ornaments.CALL_LENGTH_SCALE else 1.0))
        val baseVelocity = Ornaments.velocityFor(e.snr)
        val velocity = min(1f, (if (call) min(1f, baseVelocity + Ornaments.CALL_VELOCITY_LIFT) else baseVelocity) * Traits.payloadVelocityTrim(e.payloadBytes))
        val baseSend = Ornaments.reverbSendFor(e.hopsTaken, e.viaMqtt)
        val send = when {
            call -> max(0f, baseSend - Ornaments.CALL_DRYNESS)
            e.to == Ornaments.BROADCAST -> min(1f, baseSend + Ornaments.BROADCAST_WIDEN)
            else -> baseSend
        }.let { (it + Traits.rssiSendDelta(e.rssi)).coerceIn(0f, 1f) }
        val baseBrightness = Ornaments.brightnessFor(e.kind, e.hopsTaken, e.viaMqtt)
        val brightness = ((if (call) min(1f, baseBrightness + Ornaments.CALL_BRIGHTNESS_LIFT) else baseBrightness) +
            Traits.rssiBrightnessDelta(e.rssi) + Traits.powerBrightnessDelta(tr?.voltage, tr?.batteryPct)).coerceIn(0f, 1f)
        val pan = (Ornaments.panFor(nodes[e.from]?.position, reference()) + Traits.channelPanOffset(e.channel)).coerceIn(-Ornaments.MAX_PAN, Ornaments.MAX_PAN)
        val chord = harmony.current
        val band = (Ornaments.band(e.from) + Traits.registerOffset(tr?.altitudeM, meanAltitude())).coerceIn(0, MAX_BAND)
        val wobble = Traits.batteryDetuneCents(tr?.batteryPct)
        val speed = phraseSpeed()

        /** One ornament note plus its battery twin; false when the first found no slot. */
        fun put(intended: Long, hz: Double, vel: Float, intendedDeadline: Long): Long? {
            val at = slots.allocate(intended, duration, intendedDeadline) ?: return null
            notes += NoteEvent(at, voice, hz, vel, pan, send, duration, brightness, e.from)
            if (wobble >= MIN_TWIN_CENTS) {
                slots.allocate(at, duration, at)?.let {
                    notes += NoteEvent(it, voice, hz * Traits.centsRatio(wobble), vel * Traits.TWIN_GAIN, pan, send, duration, brightness, e.from)
                }
            }
            return at
        }

        if (e.kind == PacketKind.Text) {
            var first: Long? = null
            for (i in 0 until Traits.textPhraseNotes(e.textLength)) {
                val intended = start + sec(TEXT_STEP_SECONDS * i)
                val hz = Ornaments.pitchHz(i % 5, 1 + i / 5, chord, keyHz)
                val at = put(intended, hz, velocity, intended + sec(Ornaments.TTL_SECONDS)) ?: continue
                if (first == null) first = at
            }
            first?.let { mqttShimmer(e, it, Ornaments.pitchHz(0, 1, chord, keyHz), velocity, duration, notes) }
            return
        }
        val pitch = if (breath) Ornaments.BREATH_HZ else Ornaments.pitchHz(Ornaments.role(e.from), band, chord, keyHz)
        var anchor = start
        var slack = 0L
        var firstAt = -1L
        if (!breath && snapshot.quiet) {
            val motif = Motifs.of(e.from)
            var offset = 0.0
            var played = 0
            for (i in 0 until motif.size) {
                offset += Tempo.phraseSeconds(motif.gaps[i], speed)
                val intended = start + sec(offset)
                val hz = Ornaments.pitchHz(motif.roles[i], band, chord, keyHz)
                val at = put(intended, hz, velocity * MOTIF_FALLOFF.pow(played), if (i == 0) deadline else intended + sec(Ornaments.TTL_SECONDS))
                if (at == null) {
                    if (i == 0) return
                    continue
                }
                if (firstAt < 0) firstAt = at
                played++
                anchor = at
            }
            slack = anchor - start
        } else {
            val first = put(start, pitch, velocity, deadline) ?: return
            firstAt = first
            anchor = first
        }
        if (breath) return
        mqttShimmer(e, firstAt, pitch, velocity, duration, notes)
        val relay = resolveRelay(nodes.keys, e.relayByte, e.from, selfNode)
        for (k in 1..Ornaments.echoesFor(e.hopsTaken, e.viaMqtt)) {
            val at = slots.allocate(anchor + sec(Ornaments.ECHO_DELAY_SECONDS * k), duration, deadline + slack) ?: break
            val viaRelay = relay != null && k % 2 == 1
            notes += NoteEvent(
                at, voice, if (viaRelay) Ornaments.pitchHz(relay, chord, keyHz) else pitch, velocity * ECHO_DECAY.pow(k),
                if (viaRelay) Ornaments.panFor(nodes[relay]?.position, reference()) else pan,
                min(1f, send + ECHO_SEND_STEP * k), duration, brightness * ECHO_BRIGHTNESS, if (viaRelay) relay else e.from,
            )
        }
        answer(e, start, chord, notes)
    }

    /** A packet that came through MQTT carries a quiet shimmer layer under its first note. */
    private fun mqttShimmer(e: MeshEvent.PacketHeard, at: Long, pitch: Double, velocity: Float, duration: Long, notes: MutableList<NoteEvent>) {
        if (!e.viaMqtt || at < 0) return
        val length = duration * MQTT_SHIMMER_LENGTH_SCALE
        val slot = slots.allocate(at, length, at) ?: return
        notes += NoteEvent(slot, Voice.Shimmer, pitch, velocity * Traits.MQTT_SHIMMER_GAIN, 0f, MQTT_SHIMMER_SEND, length, MQTT_SHIMMER_BRIGHTNESS, e.from)
    }

    /** The nearest known node, else the resolved relay, that answers [e]. */
    private fun responder(e: MeshEvent.PacketHeard): Long? {
        val here = nodes[e.from]?.position
        if (here != null) {
            val k = cos(here.lat * kotlin.math.PI / 180.0)
            var best: Long? = null
            var bestD = Double.MAX_VALUE
            for ((id, info) in nodes) {
                val p = info.position ?: continue
                if (id == e.from || id == selfNode) continue
                val d = hypot((p.lon - here.lon) * k, p.lat - here.lat)
                if (d < bestD || (d == bestD && best != null && id < best)) {
                    bestD = d
                    best = id
                }
            }
            if (best != null) return best
        }
        return resolveRelay(nodes.keys, e.relayByte, e.from, selfNode)
    }

    /** 2-4 s after a packet its neighbor answers a chord-stack third or fifth above it, at most once per [ANSWER_GAP_SECONDS]. */
    private fun answer(e: MeshEvent.PacketHeard, start: Long, chord: Chord, notes: MutableList<NoteEvent>) {
        val speed = phraseSpeed()
        val intended = start + sec(Tempo.phraseSeconds(rng.range(ANSWER_MIN_SECONDS, ANSWER_MAX_SECONDS), speed))
        if (intended - lastAnswerFrame < sec(Tempo.phraseSeconds(ANSWER_GAP_SECONDS, speed))) return
        val who = responder(e) ?: return
        val base = Ornaments.pitchHz(e.from, chord, keyHz)
        val step = if (rng.nextDouble() < 0.5) 1 else 2
        val role = (Ornaments.role(e.from) + step).mod(5)
        var hz = Ornaments.pitchHz(role, Ornaments.band(e.from), chord, keyHz)
        while (hz <= base) hz *= 2
        val pan = Ornaments.panFor(nodes[who]?.position, reference())
        if (emit(notes, intended, Voice.Bell, hz, ANSWER_VELOCITY, pan, ANSWER_SEND, ANSWER_SECONDS, ANSWER_BRIGHTNESS, who)) {
            lastAnswerFrame = intended
        }
    }

    private fun bedVisible(nowFrame: Long): Boolean =
        Form.visible(form.levels(nowFrame / sampleRate.toDouble())) > Form.FLOOR

    /** The hour chime and the dawn and dusk shimmer swells, all on the key's root and fifth and only while the form is audible. */
    private fun runDayEvents(nowFrame: Long, moment: LocalMoment, day: DayState, notes: MutableList<NoteEvent>) {
        val minute = moment.minuteOfDay.coerceIn(0, 1439)
        val hour = minute / 60
        val prevHour = lastHour
        val prevMinute = lastMinute
        lastHour = hour
        lastMinute = minute
        if (prevHour >= 0 && hour != prevHour) {
            chimeHour = hour
            chimeUntilFrame = nowFrame + sec(CHIME_WAIT_SECONDS)
        }
        if (chimeHour >= 0) {
            if (nowFrame > chimeUntilFrame) {
                chimeHour = -1
            } else if (day.rootSemitones == keySemitones) {
                val count = if (chimeHour % 12 == 0) 12 else chimeHour % 12
                chimeHour = -1
                if (bedVisible(nowFrame)) {
                    for (i in 0 until count) {
                        val ratio = if (i % 2 == 0) 2.0 else 3.0
                        emit(
                            notes, nowFrame + sec(CHIME_STEP_SECONDS * i), Voice.Bell, keyHz * ratio, CHIME_VELOCITY, 0f,
                            CHIME_SEND, CHIME_SECONDS, CHIME_BRIGHTNESS, null,
                        )
                    }
                }
            }
        }
        if (prevMinute < 0 || !bedVisible(nowFrame)) return
        val dawn = prevMinute < DAWN_MINUTE && minute >= DAWN_MINUTE
        val dusk = prevMinute < DUSK_MINUTE && minute >= DUSK_MINUTE
        if (!dawn && !dusk) return
        val ratios = if (dawn) doubleArrayOf(2.0, 3.0) else doubleArrayOf(3.0, 2.0)
        ratios.forEachIndexed { i, ratio ->
            val at = nowFrame + sec(SHIMMER_SWELL_STEP_SECONDS * i)
            val length = clip(at, sec(SHIMMER_SWELL_SECONDS))
            if (length > 0) {
                notes += NoteEvent(
                    at, Voice.Shimmer, keyHz * ratio, SHIMMER_SWELL_VELOCITY, if (i == 0) -SHIMMER_SWELL_PAN else SHIMMER_SWELL_PAN,
                    SHIMMER_SEND, length, SHIMMER_BRIGHTNESS,
                )
            }
        }
    }

    /** A pressure drop is a low pad swell, a wind gust a breath of noise, a temperature crossing a bell on the new mode color. */
    private fun runGestures(nowFrame: Long, moment: LocalMoment, notes: MutableList<NoteEvent>) {
        val ms = moment.epochMillis
        if (!mood.warmingUp && ms >= nextGestureMs) {
            nextGestureMs = ms + GESTURE_SAMPLE_MS
            weatherNow(Quantity.Pressure, ms)?.let { if (pressure.sample(ms, it)) lowSwellUntilMs = ms + LOW_SWELL_WAIT_MS }
            weatherNow(Quantity.Temperature, ms)?.let {
                val dir = temperature.update(weather.scale(Quantity.Temperature, it))
                if (dir != 0) pendingColors += dir
            }
        }
        val chord = harmony.current
        if (lowSwellUntilMs >= ms && form.levels(nowFrame / sampleRate.toDouble()).getValue(Voice.Pad) > 0.02f) {
            val length = clip(nowFrame, sec(LOW_SWELL_SECONDS))
            if (length > 0) {
                lowSwellUntilMs = Long.MIN_VALUE
                notes += NoteEvent(
                    nowFrame, Voice.Pad, keyHz * Theory.justRatio(chord.rootSemitone) / 2, LOW_SWELL_VELOCITY, 0f,
                    LOW_SWELL_SEND, length, LOW_SWELL_BRIGHTNESS,
                )
            }
        }
        for ((node, delta) in pendingGusts) {
            if (ms - lastGustMs < GUST_GAP_MS) continue
            val velocity = min(GUST_MAX_VELOCITY, GUST_VELOCITY + GUST_VELOCITY_PER_MPS * delta)
            val pan = Ornaments.panFor(nodes[node]?.position, reference())
            if (emit(notes, nowFrame, Voice.Breath, Ornaments.BREATH_HZ, velocity, pan, GUST_SEND, GUST_SECONDS, GUST_BRIGHTNESS, node)) {
                lastGustMs = ms
            }
        }
        pendingGusts.clear()
        for (dir in pendingColors) {
            val semitone = if (dir > 0) LYDIAN_COLOR_SEMITONE else AEOLIAN_COLOR_SEMITONE
            emit(
                notes, nowFrame, Voice.Bell, keyHz * Theory.justRatio(semitone) * 2, COLOR_VELOCITY, 0f, COLOR_SEND,
                COLOR_SECONDS, COLOR_BRIGHTNESS, null,
            )
        }
        pendingColors.clear()
    }

    /** One farewell at a time for a node silent over an hour: its pitch and a chord tone below it, falling. */
    private fun runFarewells(nowFrame: Long, moment: LocalMoment, notes: MutableList<NoteEvent>) {
        val ms = moment.epochMillis
        if (mood.warmingUp || ms < nextFarewellMs || !bedVisible(nowFrame)) return
        nextFarewellMs = ms + FAREWELL_CHECK_MS
        var gone: Long? = null
        var oldest = Long.MAX_VALUE
        for ((id, info) in nodes) {
            if (info.lastHeard == Long.MIN_VALUE || id in farewelled || ms - info.lastHeard < SILENT_MS) continue
            if (info.lastHeard < oldest || (info.lastHeard == oldest && id < gone!!)) {
                oldest = info.lastHeard
                gone = id
            }
        }
        val id = gone ?: return
        farewelled += id
        val chord = harmony.current
        val band = Ornaments.band(id)
        val high = Ornaments.pitchHz(id, chord, keyHz)
        var low = Ornaments.pitchHz((Ornaments.role(id) + 4).mod(5), band, chord, keyHz)
        while (low >= high) low /= 2
        val pan = Ornaments.panFor(nodes[id]?.position, reference())
        emit(notes, nowFrame, Voice.Bell, high, FAREWELL_VELOCITY, pan, FAREWELL_SEND, FAREWELL_SECONDS, FAREWELL_BRIGHTNESS, id)
        emit(
            notes, nowFrame + sec(FAREWELL_STEP_SECONDS), Voice.Bell, low, FAREWELL_VELOCITY, pan, FAREWELL_SEND,
            FAREWELL_SECONDS, FAREWELL_BRIGHTNESS, id,
        )
    }

    private fun texture(day: DayState, tSec: Double, nowMs: Long): Texture {
        fun scaled(q: Quantity): Double = weatherNow(q, nowMs)?.let { weather.scale(q, it).toDouble() } ?: 0.5
        val levels = form.levels(tSec)
        val density = snapshot.density
        val pad = min(1f, levels.getValue(Voice.Pad) * (1f + 0.4f * density.toFloat()))
        val decayN = (day.decayScale - 1.0) / 0.6
        val humidity = scaled(Quantity.Humidity)
        return Texture(
            rootHz = keyHz,
            brightness = unit(0.3 * scaled(Quantity.Temperature) + 0.4 * day.brightness + 0.3 * snapshot.brightness),
            reverbSize = unit(0.3 + 0.4 * humidity + 0.3 * decayN),
            reverbWet = unit(0.3 + 0.4 * humidity + 0.2 * decayN),
            detune = unit(0.1 + 0.5 * (1 - scaled(Quantity.Pressure))),
            shimmer = unit(0.5 * scaled(Quantity.Lux) + 0.5 * levels.getValue(Voice.Shimmer)),
            noise = unit(0.1 + 0.5 * scaled(Quantity.Wind) + 0.2 * snapshot.tension),
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
        val PAD_PANS = floatArrayOf(-0.5f, -0.17f, 0.17f, 0.5f)
        const val TEXTURE_SECONDS = 5.0
        const val MAX_PENDING = 512
        const val DAY_MS = 24L * 3600 * 1000
        const val WEATHER_MS = 3600L * 1000
        const val SWELL_SECONDS = 20.0
        const val MIN_FORCED_SECONDS = 45.0
        const val MIN_LOOP_NOTE_SECONDS = 1.0
        const val CHORD_MIN_SECONDS = 120.0
        const val CHORD_MAX_SECONDS = 480.0

        const val REGISTER_FIRST_SPLIT = -0.5
        const val REGISTER_ENTER_LOW = -0.65
        const val REGISTER_ENTER_HIGH = -0.35
        const val SECTION_SILENCE_BELOW = 0.2
        const val SECTION_GATHERING_BELOW = 0.5
        const val SECTION_FULL_BELOW = 0.8

        const val DRONE_VELOCITY = 0.7f
        const val DRONE_SEND = 0.5f
        const val DRONE_BRIGHTNESS = 0.2f
        const val PAD_VELOCITY = 0.5f
        const val PAD_SEND = 0.6f
        const val PAD_STAGGER_SECONDS = 0.3
        const val NEW_NODE_SWELL_SECONDS = 6.0
        const val NEW_NODE_SWELL_VELOCITY = 0.18f

        const val LOOP_DAY_WEIGHT = 0.6
        const val LOOP_FIRE_BASE = 0.35
        const val PAD_LOOP_HOLD = 0.4
        const val SHIMMER_LOOP_HOLD = 0.3
        const val PAD_LOOP_VELOCITY = 0.4f
        const val SHIMMER_LOOP_VELOCITY = 0.3f
        const val LOOP_PAN_SPREAD = 0.4f
        const val LOOP_SEND = 0.7f
        const val SHIMMER_SEND = 0.9f
        const val SHIMMER_BRIGHTNESS = 0.9f

        const val MEMORY_VELOCITY = 0.15f
        const val MEMORY_SECONDS = 3.0
        const val MEMORY_BRIGHTNESS = 0.3f
        const val FRAGMENT_NOTES = 2
        const val MOTIF_FALLOFF = 0.85f
        const val MAX_BAND = 2
        const val MIN_TWIN_CENTS = 1.0
        const val MQTT_SHIMMER_LENGTH_SCALE = 2L
        const val MQTT_SHIMMER_SEND = 0.9f
        const val MQTT_SHIMMER_BRIGHTNESS = 0.7f
        const val TEXT_STEP_SECONDS = 0.35
        const val ECHO_DECAY = 0.55f
        const val ECHO_SEND_STEP = 0.1f
        const val ECHO_BRIGHTNESS = 0.8f

        const val ANSWER_MIN_SECONDS = 2.0
        const val ANSWER_MAX_SECONDS = 4.0
        const val ANSWER_GAP_SECONDS = 20.0
        const val ANSWER_VELOCITY = 0.3f
        const val ANSWER_SEND = 0.5f
        const val ANSWER_SECONDS = 1.4
        const val ANSWER_BRIGHTNESS = 0.45f

        const val DYAD_MS = 60_000L
        const val DYAD_SECONDS = 4.0
        const val DYAD_VELOCITY = 0.25f
        const val MAX_ROUTE_NODES = 8
        const val ROUTE_STEP_SECONDS = 0.3
        const val ROUTE_NOTE_SECONDS = 0.8
        const val ROUTE_VELOCITY = 0.45f
        const val ROUTE_SEND = 0.5f
        const val ROUTE_BRIGHTNESS = 0.5f

        const val CHIME_WAIT_SECONDS = 60.0
        const val CHIME_STEP_SECONDS = 1.5
        const val CHIME_SECONDS = 3.0
        const val CHIME_VELOCITY = 0.2f
        const val CHIME_SEND = 0.8f
        const val CHIME_BRIGHTNESS = 0.3f
        const val DAWN_MINUTE = 7 * 60
        const val DUSK_MINUTE = 19 * 60
        const val SHIMMER_SWELL_SECONDS = 16.0
        const val SHIMMER_SWELL_STEP_SECONDS = 1.5
        const val SHIMMER_SWELL_VELOCITY = 0.3f
        const val SHIMMER_SWELL_PAN = 0.3f

        const val GESTURE_SAMPLE_MS = 60_000L
        const val LOW_SWELL_WAIT_MS = 10L * 60 * 1000
        const val LOW_SWELL_SECONDS = 14.0
        const val LOW_SWELL_VELOCITY = 0.4f
        const val LOW_SWELL_SEND = 0.7f
        const val LOW_SWELL_BRIGHTNESS = 0.2f
        const val GUST_DELTA_MPS = 3f
        const val GUST_GAP_MS = 30_000L
        const val GUST_VELOCITY = 0.2f
        const val GUST_VELOCITY_PER_MPS = 0.03f
        const val GUST_MAX_VELOCITY = 0.5f
        const val GUST_SECONDS = 3.0
        const val GUST_SEND = 0.6f
        const val GUST_BRIGHTNESS = 0.3f
        const val LYDIAN_COLOR_SEMITONE = 6
        const val AEOLIAN_COLOR_SEMITONE = 8
        const val COLOR_VELOCITY = 0.25f
        const val COLOR_SEND = 0.8f
        const val COLOR_SECONDS = 3.0
        const val COLOR_BRIGHTNESS = 0.5f

        const val MAX_SESSION_VOICES = 3
        const val SESSION_STAGGER_SECONDS = 0.4
        const val SESSION_VELOCITY = 0.12f
        const val SESSION_BRIGHTNESS = 0.3f
        const val SILENT_MS = 3600L * 1000
        const val FAREWELL_CHECK_MS = 60_000L
        const val FAREWELL_STEP_SECONDS = 0.7
        const val FAREWELL_SECONDS = 2.5
        const val FAREWELL_VELOCITY = 0.25f
        const val FAREWELL_SEND = 0.8f
        const val FAREWELL_BRIGHTNESS = 0.3f
    }
}
