package org.meshtastic.aether

import org.meshtastic.aether.ui.KeyValueStore
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.transport.tcp.TcpTransport

private class JVMPlatform : Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
    override val supportsTcp: Boolean = true
    override val lookaheadMillis: Int = 400
    override val needsTapToStart: Boolean = false
    override val hasKeyboard: Boolean = true
    override val hasSystemBack: Boolean = false
    override val autoConnects: Boolean = true

    override fun resumeAudio() = Unit
}

internal actual fun getPlatform(): Platform = JVMPlatform()

private const val MILLIS_PER_MINUTE = 60_000

internal actual fun localOffsetMinutes(epochMillis: Long): Int =
    java.util.TimeZone.getDefault().getOffset(epochMillis) / MILLIS_PER_MINUTE

internal actual fun createTcpTransport(host: String, port: Int): RadioTransport? = TcpTransport(host, port)

/** Java Preferences under one node; a failing backing store (read-only home, sandbox) degrades to forgetting. */
private class PreferencesStore : KeyValueStore {
    private val prefs = java.util.prefs.Preferences.userRoot().node("org/meshtastic/aether")

    override fun get(key: String): String? = try {
        prefs.get(key, null)
    } catch (_: Exception) {
        null
    }

    override fun put(key: String, value: String) {
        try {
            prefs.put(key, value)
            prefs.flush()
        } catch (_: Exception) {
        }
    }

    override fun remove(key: String) {
        try {
            prefs.remove(key)
            prefs.flush()
        } catch (_: Exception) {
        }
    }
}

internal actual fun createKeyValueStore(): KeyValueStore = PreferencesStore()
