package org.meshtastic.aether.midi

import org.meshtastic.aether.music.NoteEvent
import org.meshtastic.aether.music.Voice
import kotlin.math.abs

private const val NOTE_ON = 0x90
private const val NOTE_OFF = 0x80
private const val CONTROL = 0xB0
private const val PITCH_BEND = 0xE0
private const val OFF_VELOCITY = 0x40
private const val SEVEN_BITS = 0x7F
private const val BITS_PER_BYTE = 7

private const val RPN_MSB = 0x65
private const val RPN_LSB = 0x64
private const val DATA_MSB = 0x06
private const val DATA_LSB = 0x26
private const val RPN_NULL = 0x7F
private const val RPN_BEND_RANGE = 0x00
private const val RPN_MPE_CONFIG = 0x06
private const val SUSTAIN = 0x40

/** MPE's lower zone: the master is channel 1 and the members are channels 2 to 16. */
private const val MPE_MASTER = 0
private const val MPE_MEMBERS = 15

/** General MIDI plays channel 10 as drums, so a plain layout leaves it out. */
private const val GM_DRUMS = 9
private const val CHANNELS = 16

/** 3 dB as an amplitude ratio: a carried bed note is struck again when its layer moves further. */
private const val RESTRIKE_RATIO = 1.4125375f

/** The bed's voices: a unison of one extends the note instead of striking it again. */
internal val BED_VOICES = setOf(Voice.Pad, Voice.Drone, Voice.Shimmer, Voice.Choir)

/** The voices the synth carries on through the bed's tick (its `continues`). */
private val CARRIED_VOICES = setOf(Voice.Pad, Voice.Drone, Voice.Shimmer)

/** One of the composer's notes, tuned, with its MIDI velocity and the level of its layer. */
internal class Strike(val note: NoteEvent, val tuned: TunedNote, val velocity: Int, val level: Float) {
    val at: Long get() = note.atFrame
    val end: Long get() = note.atFrame + note.durationFrames
    val key: Int get() = tuned.key
}

/** One of the composer's notes as it sounds on a channel. */
internal class Sounding(val note: NoteEvent, var end: Long, var level: Float)

internal class MidiChannel(val number: Int) {
    var bend = MidiPitch.CENTER_BEND
    var bendSet = false
    val notes = HashMap<Int, Sounding>()

    /** The latest end of any note this channel carried: the channel may be re-bent only well after it. */
    var lastEnd = Long.MIN_VALUE / 2

    fun sounding(key: Int, at: Long): Sounding? = notes[key]?.takeIf { it.end > at }

    fun extend(s: Sounding, end: Long) {
        s.end = maxOf(s.end, end)
        lastEnd = maxOf(lastEnd, s.end)
    }
}

private fun memberChannels(config: MidiConfig): List<Int> =
    if (config.mpe) (1..MPE_MEMBERS).toList() else (0 until CHANNELS).filter { it != GM_DRUMS }

/** A registered parameter set on [channel], then the null RPN so a stray data entry changes nothing. */
private fun EventQueue.rpn(frame: Long, part: Int, channel: Int, parameter: Int, value: Int) {
    val status = CONTROL or channel
    add(frame, part, status, RPN_MSB, 0)
    add(frame, part, status, RPN_LSB, parameter)
    add(frame, part, status, DATA_MSB, value)
    if (parameter == RPN_BEND_RANGE) add(frame, part, status, DATA_LSB, 0)
    add(frame, part, status, RPN_MSB, RPN_NULL)
    add(frame, part, status, RPN_LSB, RPN_NULL)
}

/**
 * The channels of one output port. A channel holds one pitch bend, so every note on it shares one offset from equal
 * temperament; a note takes a channel already at its bend, else a channel silent for the release allowance, else
 * (counted as a fallback) the channel with the nearest bend. A sounding channel is never re-bent.
 */
internal class MidiPart(
    private val index: Int,
    private val config: MidiConfig,
    private val queue: EventQueue,
    private val stats: MidiStats,
    sampleRate: Int,
) {
    private val allowance = (config.releaseAllowanceSeconds * sampleRate).toLong()
    private val lead = (config.bendLeadSeconds * sampleRate).toLong()
    val channels: List<MidiChannel> = memberChannels(config).map { MidiChannel(it) }

    /**
     * MPE configuration on the master channel, each channel's bend range, then each bend centred: a receiver keeps the
     * bend another app or a crashed session left, and a note at a 12-TET pitch takes a channel without bending it.
     */
    fun preamble(frame: Long) {
        if (config.mpe) queue.rpn(frame, index, MPE_MASTER, RPN_MPE_CONFIG, MPE_MEMBERS)
        for (c in channels) queue.rpn(frame, index, c.number, RPN_BEND_RANGE, config.bendRange)
        for (c in channels) {
            c.bend = MidiPitch.CENTER_BEND
            c.bendSet = true
            queue.add(frame, index, PITCH_BEND or c.number, 0, MidiPitch.CENTER_BEND shr BITS_PER_BYTE)
        }
    }

    fun play(s: Strike) {
        if (carry(s)) return
        val holder = channels.firstOrNull { it.bend == s.tuned.bend && it.sounding(s.key, s.at) != null }
        if (holder != null && s.note.voice in BED_VOICES) {
            holder.extend(holder.notes.getValue(s.key), s.end)
            stats.held++
            return
        }
        val target = channels.filter { it !== holder && it.bend == s.tuned.bend && it.sounding(s.key, s.at) == null }
            .maxByOrNull { c -> c.notes.values.count { it.end > s.at } }
            ?: free(s.at)?.also { bend(it, s.tuned.bend, s.at) }
            ?: holder
            ?: fallback(s.tuned, s.at)
        start(target, s)
    }

    /**
     * The synth's carry-on rule: a bed note with the voice, pitch, pan and velocity of one whose note-off is still
     * pending extends it instead of striking again, unless the layer has moved more than 3 dB since its note-on.
     */
    private fun carry(s: Strike): Boolean {
        val c = carrier(s) ?: return false
        val held = c.notes.getValue(s.key)
        c.extend(held, s.end)
        val ratio = if (held.level > 0f) s.level / held.level else Float.MAX_VALUE
        if (ratio > RESTRIKE_RATIO || ratio < 1f / RESTRIKE_RATIO) {
            queue.add(s.at, index, NOTE_OFF or c.number, s.key, OFF_VELOCITY)
            queue.add(s.at, index, NOTE_ON or c.number, s.key, s.velocity)
            held.level = s.level
            stats.restruck++
        } else {
            stats.carried++
        }
        return true
    }

    /** The channel holding a note that [s] carries on, if [s] is a bed note the synth would carry. */
    private fun carrier(s: Strike): MidiChannel? {
        val n = s.note
        if (n.node != null || n.voice !in CARRIED_VOICES) return null
        return channels.firstOrNull { c ->
            val o = c.notes[s.key]?.note
            o != null && o.node == null && c.bend == s.tuned.bend && o.voice == n.voice && o.pitchHz == n.pitchHz &&
                o.pan == n.pan && o.velocity == n.velocity
        }
    }

    /** The silent channel whose last note ended longest ago, if it has been silent for the release allowance. */
    private fun free(at: Long): MidiChannel? =
        channels.filter { it.lastEnd + allowance <= at - lead }.minByOrNull { it.lastEnd }

    private fun bend(channel: MidiChannel, bend: Int, at: Long) {
        if (channel.bendSet && channel.bend == bend) return
        channel.bend = bend
        channel.bendSet = true
        queue.add(at - lead, index, PITCH_BEND or channel.number, bend and SEVEN_BITS, bend shr BITS_PER_BYTE)
    }

    /** No channel at the note's bend and none free: the nearest bend, mistuning the note by a counted amount. */
    private fun fallback(tuned: TunedNote, at: Long): MidiChannel {
        val open = channels.filter { it.sounding(tuned.key, at) == null }
        val pick = open.ifEmpty { channels }.minBy { abs(it.bend - tuned.bend) }
        val cents = MidiPitch.bendCents(pick.bend, tuned.bend, config.bendRange)
        stats.fallbacks++
        stats.maxFallbackCents = maxOf(stats.maxFallbackCents, cents)
        return pick
    }

    private fun start(channel: MidiChannel, s: Strike) {
        channel.notes[s.key]?.let { old ->
            // A note at this key on this channel ends first: by its own end if that has passed, else now.
            queue.add(minOf(old.end, s.at), index, NOTE_OFF or channel.number, s.key, OFF_VELOCITY)
            if (old.end > s.at) stats.retriggered++
        }
        queue.add(s.at, index, NOTE_ON or channel.number, s.key, s.velocity)
        channel.notes[s.key] = Sounding(s.note, s.end, s.level)
        channel.lastEnd = maxOf(channel.lastEnd, s.end)
    }

    /** Queues the note-off of every note that has ended by [upTo]. */
    fun endDue(upTo: Long) {
        for (c in channels) {
            val it = c.notes.entries.iterator()
            while (it.hasNext()) {
                val (key, s) = it.next()
                if (s.end > upTo) continue
                queue.add(s.end, index, NOTE_OFF or c.number, key, OFF_VELOCITY)
                it.remove()
            }
        }
    }

    /** Forgets every note and, at [at], sends sustain off and centres every bend; the mapper has ended the notes. */
    fun close(at: Long) {
        for (c in channels) {
            c.notes.clear()
            c.bend = MidiPitch.CENTER_BEND
            c.bendSet = false
            c.lastEnd = Long.MIN_VALUE / 2
            queue.add(at, index, PITCH_BEND or c.number, 0, MidiPitch.CENTER_BEND shr BITS_PER_BYTE)
        }
        val sustainChannels = if (config.mpe) listOf(MPE_MASTER) else channels.map { it.number }
        for (ch in sustainChannels) queue.add(at, index, CONTROL or ch, SUSTAIN, 0)
    }
}
