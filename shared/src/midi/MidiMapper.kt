package org.meshtastic.aether.midi

import org.meshtastic.aether.music.ComposerOutput
import org.meshtastic.aether.music.ControlEvent
import org.meshtastic.aether.music.NoteEvent
import org.meshtastic.aether.music.NoteSink
import org.meshtastic.aether.music.Voice
import kotlin.math.roundToInt

/** How notes spread over output ports: one part for everything, or the bed and the ornaments on two. */
enum class MidiLayout { OnePart, BedAndOrnaments }

/** One MIDI message for output [part] at [frame] on the audio sample clock. */
class MidiEvent(val frame: Long, val part: Int, val data: ByteArray)

/**
 * [bendRange] is 48 semitones for MPE (members on channels 2 to 16) or 2 for a plain multi-channel receiver (channels
 * 1 to 16 without 10). A channel is re-bent only after [releaseAllowanceSeconds] of silence. A note is placed
 * [dispatchAheadSeconds] before it sounds, and its channel's bend goes out [bendLeadSeconds] before it.
 */
class MidiConfig(
    val bendRange: Int = MPE_BEND_RANGE,
    val layout: MidiLayout = MidiLayout.OnePart,
    val releaseAllowanceSeconds: Double = 2.0,
    val dispatchAheadSeconds: Double = 0.3,
    val bendLeadSeconds: Double = 0.05,
) {
    init {
        require(bendRange == MPE_BEND_RANGE || bendRange == PLAIN_BEND_RANGE) { "bend range is 48 (MPE) or 2" }
        require(bendLeadSeconds < dispatchAheadSeconds) { "a bend must be placed before it is due" }
    }

    val mpe: Boolean get() = bendRange == MPE_BEND_RANGE
    val parts: Int get() = if (layout == MidiLayout.OnePart) 1 else 2

    companion object {
        const val MPE_BEND_RANGE = 48
        const val PLAIN_BEND_RANGE = 2
    }
}

/**
 * What the mapper did with the notes it was given. A fallback is a note on a channel at another bend; a late message
 * was due before one already sent and went out at that one's frame, so a drain ran behind its notes; a shifted note
 * arrived after a later note was placed and starts at that note's frame.
 */
class MidiStats {
    var late = 0
    var shifted = 0
    var notes = 0
    var skipped = 0
    var carried = 0
    var restruck = 0
    var held = 0
    var retriggered = 0
    var fallbacks = 0
    var maxFallbackCents = 0.0
}

/** Events held in frame order; events at one frame keep the order they were added in. */
internal class EventQueue {
    private val items = ArrayList<MidiEvent>()

    fun add(frame: Long, part: Int, vararg bytes: Int) {
        val event = MidiEvent(frame, part, ByteArray(bytes.size) { bytes[it].toByte() })
        var i = items.size
        while (i > 0 && items[i - 1].frame > frame) i--
        items.add(i, event)
    }

    fun popUpTo(upTo: Long, emit: (MidiEvent) -> Unit) {
        var n = 0
        while (n < items.size && items[n].frame <= upTo) emit(items[n++])
        items.subList(0, n).clear()
    }

    fun dropAfter(frame: Long) {
        items.removeAll { it.frame > frame }
    }
}

/** The notes a receiver is sounding, read from the messages as they are sent: what a close has to end. */
internal class SentNotes {
    private val on = HashSet<Int>()

    fun track(e: MidiEvent) {
        val status = e.data[0].toInt() and STATUS_MASK
        if (status != ON && status != OFF) return
        val channel = e.data[0].toInt() and CHANNEL_MASK
        val id = (e.part shl PART_SHIFT) or (channel shl CHANNEL_SHIFT) or e.data[1].toInt()
        if (status == ON && e.data[2] > 0) on.add(id) else on.remove(id)
    }

    fun endAll(off: (part: Int, channel: Int, key: Int) -> Unit) {
        for (id in on.sorted()) off(id shr PART_SHIFT, (id shr CHANNEL_SHIFT) and CHANNEL_MASK, id and KEY_MASK)
        on.clear()
    }

    private companion object {
        const val STATUS_MASK = 0xF0
        const val CHANNEL_MASK = 0x0F
        const val KEY_MASK = 0x7F
        const val ON = 0x90
        const val OFF = 0x80
        const val CHANNEL_SHIFT = 8
        const val PART_SHIFT = 16
    }
}

/**
 * The composer's notes as MIDI with exact tuning: each note is the nearest key plus a pitch bend on a channel that
 * carries only its offset. Notes are buffered in frame order, because a later composer window can bring an earlier
 * note, and placed when [drain] comes within [MidiConfig.dispatchAheadSeconds] of them. A bed note's velocity is
 * scaled by its layer's level from the composer's textures, as the synth's gain is. Breath, which has no pitch, is
 * not sent; Shimmer goes out an octave up, where its lowest partial sounds.
 */
class MidiMapper(sampleRate: Int, val config: MidiConfig = MidiConfig()) : NoteSink {
    val stats = MidiStats()
    private val queue = EventQueue()
    private val parts = List(config.parts) { MidiPart(it, config, queue, stats, sampleRate) }
    private val pending = ArrayList<NoteEvent>()
    private val textures = ArrayList<ControlEvent.SetTexture>()
    private val ahead = (config.dispatchAheadSeconds * sampleRate).toLong()
    private val lead = (config.bendLeadSeconds * sampleRate).toLong()
    private val sent = SentNotes()
    private var placedUpTo = Long.MIN_VALUE
    private var lastFrame = Long.MIN_VALUE
    private var open = false

    override fun schedule(output: ComposerOutput) {
        for (n in output.notes) insert(pending, n) { it.atFrame }
        for (c in output.controls) if (c is ControlEvent.SetTexture) insert(textures, c) { it.atFrame }
    }

    /**
     * Places every note due within the dispatch horizon of [upTo] and hands [emit] every message due by [upTo], in
     * non-decreasing frame order. The first call sends each part's preamble first.
     */
    fun drain(upTo: Long, emit: (MidiEvent) -> Unit) {
        if (!open) {
            val start = minOf(upTo, (pending.firstOrNull()?.atFrame ?: upTo) - lead)
            for (p in parts) p.preamble(start)
            open = true
        }
        while (pending.isNotEmpty() && pending[0].atFrame <= upTo + ahead) dispatch(pending.removeAt(0))
        for (p in parts) p.endDue(upTo)
        queue.popUpTo(upTo) { e ->
            if (e.frame < lastFrame) stats.late++
            val frame = maxOf(e.frame, lastFrame)
            lastFrame = frame
            sent.track(e)
            emit(if (frame == e.frame) e else MidiEvent(frame, e.part, e.data))
        }
        while (textures.size > 1 && textures[1].atFrame <= upTo) textures.removeAt(0)
    }

    /**
     * Silences every part at [at]: what is due goes out, later messages and notes are dropped, every note sent and not
     * yet ended gets its note-off, then sustain off and centred bends. The next [drain] sends the preamble again.
     */
    fun close(at: Long, emit: (MidiEvent) -> Unit) {
        if (!open) return
        drain(at, emit)
        queue.dropAfter(at)
        pending.clear()
        sent.endAll { part, channel, key -> queue.add(at, part, NOTE_OFF or channel, key, OFF_VELOCITY) }
        for (p in parts) p.close(at)
        drain(at, emit)
        placedUpTo = Long.MIN_VALUE
        open = false
    }

    /**
     * Places [arrived]. A note that sounds before one already placed arrived inside the dispatch horizon; placing it
     * at its own frame could strike over or re-bend under the later note, so it starts at that note's frame instead.
     */
    private fun dispatch(arrived: NoteEvent) {
        val end = arrived.atFrame + arrived.durationFrames
        if (arrived.atFrame < placedUpTo) stats.shifted++
        val note = if (arrived.atFrame >= placedUpTo) {
            arrived
        } else {
            arrived.copy(atFrame = placedUpTo, durationFrames = end - placedUpTo)
        }
        if (note.durationFrames <= 0) {
            stats.skipped++
            return
        }
        placedUpTo = note.atFrame
        val hz = if (note.voice == Voice.Shimmer) note.pitchHz * 2 else note.pitchHz
        val tuned = if (note.voice == Voice.Breath) null else MidiPitch.tune(hz, config.bendRange)
        val meshBed = note.node != null && (note.voice == Voice.Drone || note.voice == Voice.Shimmer)
        val level = if (meshBed) 1f else levelAt(note.voice, note.atFrame)
        val velocity = (note.velocity * level * MAX_VELOCITY).roundToInt()
        if (tuned == null || velocity < 1) {
            stats.skipped++
            return
        }
        stats.notes++
        partOf(note.voice).play(Strike(note, tuned, velocity.coerceAtMost(MAX_VELOCITY), level))
    }

    private fun partOf(voice: Voice): MidiPart =
        if (config.layout == MidiLayout.OnePart || voice in BED_VOICES) parts[0] else parts[1]

    /** The layer level the synth applies to [voice] at [frame]: the latest texture's, or 1 before any texture. */
    private fun levelAt(voice: Voice, frame: Long): Float {
        val t = textures.lastOrNull { it.atFrame <= frame } ?: return 1f
        return t.texture.layerLevels[voice] ?: 1f
    }

    private companion object {
        const val MAX_VELOCITY = 127
        const val NOTE_OFF = 0x80
        const val OFF_VELOCITY = 0x40

        fun <T> insert(list: ArrayList<T>, item: T, frame: (T) -> Long) {
            var i = list.size
            while (i > 0 && frame(list[i - 1]) > frame(item)) i--
            list.add(i, item)
        }
    }
}
