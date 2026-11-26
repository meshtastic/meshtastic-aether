package org.meshtastic.ambient.music

/** A composer whose packets all play the Mallet, the timbre these tests were written against; the palette has its own tests. */
internal fun malletComposer(seed: Long, sampleRate: Int, pinFormFull: Boolean = false): Composer =
    Composer(seed, sampleRate, pinFormFull).also { it.voicePicker = { _, _, _ -> Voice.Mallet } }
