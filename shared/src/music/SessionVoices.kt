package org.meshtastic.aether.music

import org.meshtastic.aether.mood.MeshMood

private const val MAX_SESSION_VOICES = 3
private const val SESSION_STAGGER_SECONDS = 0.4
private const val SESSION_VELOCITY = 0.12f
private const val SESSION_BRIGHTNESS = 0.3f
private const val NEW_NODE_SWELL_SECONDS = 6.0
private const val NEW_NODE_SWELL_VELOCITY = 0.18f
private const val NEW_NODE_SWELL_SEND = 0.8f
private const val NEW_NODE_SWELL_BRIGHTNESS = 0.3f

/**
 * The newest nodes first seen this session: each arrival swells the pad once, and the last [MAX_SESSION_VOICES] hum
 * their chord role an octave over the pad at every tick.
 */
internal class SessionVoices(private val score: Score, private val mood: MeshMood, private val nodes: NodeRegistry) {
    private val voices = ArrayList<Long>()
    private var swells = 0

    fun welcome(node: Long) {
        swells++
        voices.remove(node)
        voices += node
        if (voices.size > MAX_SESSION_VOICES) voices.removeAt(0)
    }

    fun forget(node: Long) {
        voices.remove(node)
    }

    fun play(at: Long) {
        val chord = score.chord
        voices.forEachIndexed { i, node ->
            val start = at + score.sec(SESSION_STAGGER_SECONDS * (i + 1))
            val length = score.clip(start, score.sec(Score.SWELL_SECONDS))
            if (length <= 0) return@forEachIndexed
            score.notes += NoteEvent(
                start, Voice.Pad, score.padHz(chord.roleSemitone(Ornaments.role(node)) + Theory.SEMITONES_PER_OCTAVE),
                SESSION_VELOCITY, nodes.panOf(node), Score.PAD_SEND, length, SESSION_BRIGHTNESS, node,
            )
        }
    }

    /** The pad swell for the nodes that arrived since the last window, once, an octave over the chord root. */
    fun swell(nowFrame: Long) {
        if (swells <= 0 || mood.warmingUp) return
        swells = 0
        val level = score.levels(nowFrame).getValue(Voice.Pad)
        val length = score.clip(nowFrame, score.sec(NEW_NODE_SWELL_SECONDS))
        if (level > Score.AUDIBLE_LEVEL && length > 0) {
            score.notes += NoteEvent(
                nowFrame, Voice.Pad, score.keyHz * Theory.justRatio(score.chord.rootSemitone) * 2,
                NEW_NODE_SWELL_VELOCITY, 0f, NEW_NODE_SWELL_SEND, length, NEW_NODE_SWELL_BRIGHTNESS,
            )
        }
    }
}
