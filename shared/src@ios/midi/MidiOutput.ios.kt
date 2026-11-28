package org.meshtastic.aether.midi

actual fun midiSupported(): Boolean = false

actual fun midiOutputNames(): List<String> = emptyList()

actual fun openMidiOutput(name: String): MidiOutput? = null
