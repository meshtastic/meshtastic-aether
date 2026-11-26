package org.meshtastic.aether.audio

/** The platform's speaker output; each target supplies its own on platform APIs. */
expect fun createAudioSink(): AudioSink
