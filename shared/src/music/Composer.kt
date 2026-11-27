package org.meshtastic.aether.music

import org.meshtastic.aether.mesh.GeoPoint
import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.mesh.PacketKind
import org.meshtastic.aether.mood.MeshMood
import org.meshtastic.aether.mood.MoodSnapshot
import org.meshtastic.aether.mood.ParamValues
import kotlin.math.max

/** The composer's own random stream; [Harmony] draws stream 1 and [Form] stream 2. */
private const val RNG_STREAM = 3

private const val SECTION_SILENCE_BELOW = 0.2
private const val SECTION_GATHERING_BELOW = 0.5
private const val SECTION_FULL_BELOW = 0.8

/**
 * The autonomous piece plus the mesh's perturbations. Pure and deterministic given (events, calls, seed).
 * Call [onEvent] as events arrive and [advance] on a timer; each call fills frames up to `nowFrame + lookahead`.
 * [pinFormFull] holds the long form at its full level, for the demo.
 *
 * Every part draws from one random stream in a fixed order: the loop periods at construction, then per window the
 * loops, chord lengths, packet thinning and grid jitter, and the answers.
 */
class Composer(seed: Long, sampleRate: Int, pinFormFull: Boolean = false) {
    private val rng = Rng.stream(seed, RNG_STREAM)
    private val harmony = Harmony(seed)
    private val score = Score(sampleRate, Form(seed, pinFormFull), harmony)
    private val mood = MeshMood()
    private var snapshot = MoodSnapshot.NEUTRAL
    private var started = false
    private val nodes = NodeRegistry()
    private val traits = TraitBook()
    private val loops = Loops(score, rng, nodes)
    private val weather = WeatherGestures(score, mood, nodes)
    private val sessionVoices = SessionVoices(score, mood, nodes)
    private val bed = Bed(score, rng, sessionVoices, TextureMixer(score, weather))
    private val packets = PacketPhrases(
        score, rng, nodes,
        PacketVoicing(score, traits, nodes) { node, role, hardware -> voicePicker(node, role, hardware) },
    )
    private val meshPhrases = MeshPhrases(score, mood, nodes)
    private val dayEvents = DayEvents(score)
    private val farewells = Farewells(score, mood, nodes)

    /** Our own node's position when known; pans are relative to it, else to the centroid of known nodes. */
    var origin: GeoPoint?
        get() = nodes.origin
        set(value) {
            nodes.origin = value
        }

    val chord: Chord get() = harmony.current

    /** The mesh mood as of the last [advance]; neutral before the first. */
    fun moodSnapshot(): MoodSnapshot = snapshot

    /** The mesh-driven parameters as of the last [advance]: what the engine hands to the scene and overlay. */
    fun params(): ParamValues = paramOverride ?: snapshot.params

    /** Test seam: when set, [params] returns this instead of the mood's. */
    internal var paramOverride: ParamValues? = null

    /** Test seam: the voice a node's packets play, by node, role and hardware; the palette in production. */
    internal var voicePicker: (Long, String?, String?) -> Voice =
        { node, role, hardware -> Palette.voiceFor(role, hardware, node) }

    fun onEvent(e: MeshEvent) {
        mood.onEvent(e)
        when (e) {
            is MeshEvent.Link -> Unit
            is MeshEvent.NodeSeen -> onNodeSeen(e)
            is MeshEvent.NodeGone -> onNodeGone(e.node)
            is MeshEvent.Environment -> weather.onEnvironment(e)
            is MeshEvent.Self -> onSelf(e)
            is MeshEvent.Neighbors -> meshPhrases.onNeighbors(e)
            is MeshEvent.Route -> meshPhrases.onRoute(e)
            is MeshEvent.PacketHeard -> onPacket(e)
            is MeshEvent.Health -> traits.health(e)
            is MeshEvent.Stats, is MeshEvent.Vitals -> Unit
        }
    }

    private fun onNodeSeen(e: MeshEvent.NodeSeen) {
        nodes.place(e.node, e.position)
        traits.seen(e)
        if (e.firstThisSession && !mood.warmingUp) sessionVoices.welcome(e.node)
    }

    private fun onNodeGone(node: Long) {
        nodes.remove(node)
        traits.remove(node)
        weather.forget(node)
        sessionVoices.forget(node)
        farewells.forget(node)
    }

    private fun onSelf(e: MeshEvent.Self) {
        nodes.selfNode = e.node
        e.position?.let { nodes.origin = it }
    }

    private fun onPacket(e: MeshEvent.PacketHeard) {
        if (mood.warmingUp) return
        nodes.heard(e.from, e.atMillis)
        farewells.forget(e.from)
        if (e.kind == PacketKind.Text) bed.chordRequested = true
        packets.enqueue(e, mood.fastRate)
    }

    fun advance(nowFrame: Long, lookaheadFrames: Long, moment: LocalMoment): ComposerOutput {
        val windowEnd = nowFrame + lookaheadFrames
        val day = Day.at(moment)
        snapshot = mood.advance(moment.epochMillis)
        score.begin(snapshot, params())
        if (!started) {
            started = true
            bed.start(nowFrame)
            loops.start(nowFrame)
        }
        while (score.nextTickFrame < windowEnd) {
            val at = max(score.nextTickFrame, nowFrame)
            loops.run(nowFrame, at, day)
            bed.tick(at, day, moment)
        }
        bed.refreshTexture(nowFrame, windowEnd, day, moment)
        loops.run(nowFrame, windowEnd, day)
        packets.run(nowFrame, windowEnd, moment)
        meshPhrases.run(nowFrame)
        dayEvents.run(nowFrame, moment, day)
        weather.run(nowFrame, moment)
        farewells.run(nowFrame, moment)
        sessionVoices.swell(nowFrame)
        return score.output()
    }

    /** Label of the form section at [tSeconds], for example `2 - gathering`. */
    fun sectionAt(tSeconds: Double): String {
        var end = 0.0
        var index = 0
        for (minutes in score.form.sectionMinutes(tSeconds)) {
            end += minutes * Form.SECONDS_PER_MINUTE
            if (tSeconds < end) break
            index++
        }
        val energy = score.form.energy(tSeconds)
        val word = when {
            energy < SECTION_SILENCE_BELOW -> "near silence"
            energy < SECTION_GATHERING_BELOW -> "gathering"
            energy < SECTION_FULL_BELOW -> "full"
            else -> "peak"
        }
        return "${index + 1} - $word"
    }
}
