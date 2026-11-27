package org.meshtastic.aether.music

import org.meshtastic.aether.mood.MeshMood

/** A node silent this long gets its farewell. */
private const val SILENT_MS = 3600L * 1000
private const val FAREWELL_CHECK_MS = 60_000L
private const val FAREWELL_STEP_SECONDS = 0.7
private const val FAREWELL_SECONDS = 2.5
private const val FAREWELL_VELOCITY = 0.25f
private const val FAREWELL_SEND = 0.8f
private const val FAREWELL_BRIGHTNESS = 0.3f

/** One farewell at a time for a node silent over an hour: its pitch and a chord tone below it, falling. */
internal class Farewells(private val score: Score, private val mood: MeshMood, private val nodes: NodeRegistry) {
    private val farewelled = HashSet<Long>()
    private var nextFarewellMs = Long.MIN_VALUE

    /** [node] was heard again or is gone: it can be farewelled anew. */
    fun forget(node: Long) {
        farewelled.remove(node)
    }

    fun run(nowFrame: Long, moment: LocalMoment) {
        val ms = moment.epochMillis
        if (mood.warmingUp || ms < nextFarewellMs || !score.bedVisible(nowFrame)) return
        nextFarewellMs = ms + FAREWELL_CHECK_MS
        val id = nodes.longestSilent(ms, SILENT_MS, farewelled) ?: return
        farewelled += id
        val chord = score.chord
        val high = Ornaments.pitchHz(id, chord, score.keyHz)
        val below = (Ornaments.role(id) - 1).mod(Chord.ROLES)
        var low = Ornaments.pitchHz(below, Ornaments.band(id), chord, score.keyHz)
        while (low >= high) low /= 2
        val pan = nodes.panOf(id)
        val duration = score.sec(FAREWELL_SECONDS)
        score.emit(
            NoteEvent(
                nowFrame, Voice.Bell, high, FAREWELL_VELOCITY, pan, FAREWELL_SEND, duration, FAREWELL_BRIGHTNESS, id,
            ),
        )
        score.emit(
            NoteEvent(
                nowFrame + score.sec(FAREWELL_STEP_SECONDS), Voice.Bell, low, FAREWELL_VELOCITY, pan, FAREWELL_SEND,
                duration, FAREWELL_BRIGHTNESS, id,
            ),
        )
    }
}
