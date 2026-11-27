package org.meshtastic.aether.music

import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.mesh.PacketKind
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private const val MAX_PENDING = 512
private const val DAY_MS = 24L * 3600 * 1000
private const val MS_PER_SECOND = 1000

/** A packet's first note lands on the grid within this much random spread, so a burst does not strike as one. */
private const val GRID_JITTER_SECONDS = 0.02

private const val MOTIF_FALLOFF = 0.85f
private const val MIN_TWIN_CENTS = 1.0
private const val MQTT_SHIMMER_LENGTH_SCALE = 2L
private const val MQTT_SHIMMER_SEND = 0.9f
private const val MQTT_SHIMMER_BRIGHTNESS = 0.7f
private const val TEXT_STEP_SECONDS = 0.35
private const val ECHO_DECAY = 0.55f
private const val ECHO_SEND_STEP = 0.1f
private const val ECHO_BRIGHTNESS = 0.8f

private const val ANSWER_MIN_SECONDS = 2.0
private const val ANSWER_MAX_SECONDS = 4.0
private const val ANSWER_GAP_SECONDS = 20.0

/** Even odds that an answer is the next chord tone up (a third) rather than two up (a fifth). */
private const val ANSWER_THIRD_CHANCE = 0.5
private const val ANSWER_VELOCITY = 0.3f
private const val ANSWER_SEND = 0.5f
private const val ANSWER_SECONDS = 1.4
private const val ANSWER_BRIGHTNESS = 0.45f

/** Where a packet's notes fell: its first note, the last one its echoes follow, and how late that one ran. */
private data class Placed(val firstAt: Long, val anchor: Long, val slack: Long)

/**
 * Each heard packet as an ornament on the free slots: a text is a phrase up the chord stack, a packet in a quiet mesh
 * its node's motif, any other one note; then its MQTT shimmer, echoes by hop count (odd ones from the relay), and a
 * neighbor's answer. Encrypted traffic is one pitchless breath.
 */
internal class PacketPhrases(
    private val score: Score,
    private val rng: Rng,
    private val nodes: NodeRegistry,
    private val voicing: PacketVoicing,
) {
    private val pending = ArrayDeque<MeshEvent.PacketHeard>()
    private var lastAnswerFrame = Long.MIN_VALUE / 2

    /** Queues [e]; above [Ornaments.THIN_RATE] packets a second ([fastRate]) only a sample is kept, but every text. */
    fun enqueue(e: MeshEvent.PacketHeard, fastRate: Double) {
        val keep = fastRate <= Ornaments.THIN_RATE || rng.nextDouble() < Ornaments.THIN_RATE / fastRate
        if (!keep && e.kind != PacketKind.Text) return
        pending.addLast(e)
        if (pending.size > MAX_PENDING) pending.removeFirst()
    }

    /** Plays the queued packets that arrived before [windowEnd]; a packet older than a day is dropped. */
    fun run(nowFrame: Long, windowEnd: Long, moment: LocalMoment) {
        val ttl = score.sec(Ornaments.TTL_SECONDS)
        val grid = score.sec(Ornaments.GRID_SECONDS)
        val it = pending.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val ageMs = moment.epochMillis - e.atMillis
            val arrival = nowFrame - ageMs.coerceAtLeast(-DAY_MS) * score.sampleRate / MS_PER_SECOND
            if (ageMs > DAY_MS) {
                it.remove()
            } else if (arrival < windowEnd) {
                it.remove()
                val onGrid = -((-arrival).floorDiv(grid)) * grid
                val start = max(onGrid + (rng.centered() * GRID_JITTER_SECONDS * score.sampleRate).toLong(), nowFrame)
                ornament(e, start, arrival + ttl)
            }
        }
    }

    private fun ornament(e: MeshEvent.PacketHeard, start: Long, deadline: Long) {
        val sound = voicing.soundOf(e)
        when (e.kind) {
            PacketKind.Text -> textPhrase(sound, e.textLength, start)
            PacketKind.Encrypted -> place(sound, start, Ornaments.BREATH_HZ, sound.velocity, deadline)
            else -> tonal(e, sound, start, deadline)
        }
    }

    /** One ornament note plus its battery twin; the frame it starts at, or null when it found no slot. */
    private fun place(sound: PacketSound, intended: Long, hz: Double, velocity: Float, deadline: Long): Long? {
        val at = score.slots.allocate(intended, sound.durationFrames, deadline) ?: return null
        score.notes += NoteEvent(
            at, sound.voice, hz, velocity, sound.pan, sound.send, sound.durationFrames, sound.brightness, sound.node,
        )
        if (sound.twinCents >= MIN_TWIN_CENTS) {
            score.slots.allocate(at, sound.durationFrames, at)?.let {
                val twinHz = hz * Traits.centsRatio(twinCents(sound.twinCents, hz))
                score.notes += NoteEvent(
                    it, sound.voice, twinHz, velocity * Traits.TWIN_GAIN, sound.pan, sound.send, sound.durationFrames,
                    sound.brightness, sound.node,
                )
            }
        }
        return at
    }

    /** A text climbs the chord stack from the band above the key, one note per step, with no echoes or answer. */
    private fun textPhrase(sound: PacketSound, textLength: Int?, start: Long) {
        val chord = score.chord
        val ttl = score.sec(Ornaments.TTL_SECONDS)
        var first: Long? = null
        for (i in 0 until Traits.textPhraseNotes(textLength)) {
            val intended = start + score.sec(TEXT_STEP_SECONDS * i)
            val hz = Ornaments.pitchHz(i % Chord.ROLES, 1 + i / Chord.ROLES, chord, score.keyHz)
            val at = place(sound, intended, hz, sound.velocity, intended + ttl) ?: continue
            if (first == null) first = at
        }
        first?.let { mqttShimmer(sound, it, Ornaments.pitchHz(0, 1, chord, score.keyHz)) }
    }

    /** The node's motif in a quiet mesh, else its one note; then its shimmer, echoes and an answer. */
    private fun tonal(e: MeshEvent.PacketHeard, sound: PacketSound, start: Long, deadline: Long) {
        val chord = score.chord
        val pitch = Ornaments.pitchHz(Ornaments.role(e.from), sound.band, chord, score.keyHz)
        val placed = if (score.mood.quiet) {
            motifPhrase(sound, start, deadline)
        } else {
            place(sound, start, pitch, sound.velocity, deadline)?.let { Placed(it, it, 0L) }
        }
        if (placed == null) return
        mqttShimmer(sound, placed.firstAt, pitch)
        echoes(e, sound, pitch, placed, deadline)
        answer(e, start, chord)
    }

    /** The node's motif, each note softer than the last; null when its first note found no slot. */
    private fun motifPhrase(sound: PacketSound, start: Long, deadline: Long): Placed? {
        val motif = Motifs.of(sound.node)
        val chord = score.chord
        val speed = score.phraseSpeed()
        var offset = 0.0
        var played = 0
        var firstAt = -1L
        var anchor = start
        for (i in 0 until motif.size) {
            offset += Tempo.phraseSeconds(motif.gaps[i], speed)
            val intended = start + score.sec(offset)
            val hz = Ornaments.pitchHz(motif.roles[i], sound.band, chord, score.keyHz)
            val noteDeadline = if (i == 0) deadline else intended + score.sec(Ornaments.TTL_SECONDS)
            val at = place(sound, intended, hz, sound.velocity * MOTIF_FALLOFF.pow(played), noteDeadline)
            if (at != null) {
                if (firstAt < 0) firstAt = at
                played++
                anchor = at
            } else if (i == 0) {
                break
            }
        }
        return if (firstAt < 0) null else Placed(firstAt, anchor, anchor - start)
    }

    /** Echoes by hop count after the last note, each softer and wetter; odd ones sound from the relay when known. */
    private fun echoes(e: MeshEvent.PacketHeard, sound: PacketSound, pitch: Double, placed: Placed, deadline: Long) {
        val chord = score.chord
        val relay = nodes.relayFor(e)
        for (k in 1..Ornaments.echoesFor(e.hopsTaken, e.viaMqtt)) {
            val intended = placed.anchor + score.sec(Ornaments.ECHO_DELAY_SECONDS * k)
            val at = score.slots.allocate(intended, sound.durationFrames, deadline + placed.slack) ?: break
            val viaRelay = relay != null && k % 2 == 1
            score.notes += NoteEvent(
                at, sound.voice, if (viaRelay) Ornaments.pitchHz(relay, chord, score.keyHz) else pitch,
                sound.velocity * ECHO_DECAY.pow(k), if (viaRelay) nodes.panOf(relay) else sound.pan,
                min(1f, sound.send + ECHO_SEND_STEP * k), sound.durationFrames, sound.brightness * ECHO_BRIGHTNESS,
                if (viaRelay) relay else sound.node,
            )
        }
    }

    /** A packet that came through MQTT carries a quiet shimmer layer under its first note. */
    private fun mqttShimmer(sound: PacketSound, at: Long, pitch: Double) {
        if (!sound.viaMqtt || at < 0) return
        val length = sound.durationFrames * MQTT_SHIMMER_LENGTH_SCALE
        val slot = score.slots.allocate(at, length, at) ?: return
        score.notes += NoteEvent(
            slot, Voice.Shimmer, pitch, sound.velocity * Traits.MQTT_SHIMMER_GAIN, 0f, MQTT_SHIMMER_SEND, length,
            MQTT_SHIMMER_BRIGHTNESS, sound.node,
        )
    }

    /**
     * 2-4 s after a packet its neighbor answers a chord-stack third or fifth above it, at most once per
     * [ANSWER_GAP_SECONDS].
     */
    private fun answer(e: MeshEvent.PacketHeard, start: Long, chord: Chord) {
        val speed = score.phraseSpeed()
        val intended = start + score.sec(Tempo.phraseSeconds(rng.range(ANSWER_MIN_SECONDS, ANSWER_MAX_SECONDS), speed))
        if (intended - lastAnswerFrame < score.sec(Tempo.phraseSeconds(ANSWER_GAP_SECONDS, speed))) return
        val who = nodes.responder(e) ?: return
        val base = Ornaments.pitchHz(e.from, chord, score.keyHz)
        val step = if (rng.nextDouble() < ANSWER_THIRD_CHANCE) 1 else 2
        val role = (Ornaments.role(e.from) + step).mod(Chord.ROLES)
        var hz = Ornaments.pitchHz(role, Ornaments.band(e.from), chord, score.keyHz)
        while (hz <= base) hz *= 2
        val note = NoteEvent(
            intended, Voice.Bell, hz, ANSWER_VELOCITY, nodes.panOf(who), ANSWER_SEND, score.sec(ANSWER_SECONDS),
            ANSWER_BRIGHTNESS, who,
        )
        if (score.emit(note)) lastAnswerFrame = intended
    }
}
