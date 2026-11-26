package org.meshtastic.aether

import org.meshtastic.aether.ui.KeyValueStore
import org.meshtastic.sdk.RadioTransport

/** What the running target can and cannot do; the UI branches on these flags, never on [name]. */
internal interface Platform {
    /** Human-readable target, for the log only. */
    val name: String

    /** Whether [createTcpTransport] returns a transport on this target (the browser cannot open raw sockets). */
    val supportsTcp: Boolean

    /** How far ahead of the speaker the composer fills: 250-500 ms natively, longer on the web. */
    val lookaheadMillis: Int

    /** The browser keeps audio suspended until a gesture, so the app opens on a tap-to-start screen there. */
    val needsTapToStart: Boolean

    /** Whether a hardware keyboard is the usual input; decides whether hints name keys or only say tap. */
    val hasKeyboard: Boolean

    /** Whether the app may reconnect to the saved link on launch; false where a tap is needed first anyway. */
    val autoConnects: Boolean

    /** Called from a click or key handler; lets a suspended audio context run. */
    fun resumeAudio()
}

internal expect fun getPlatform(): Platform

/** The local UTC offset in minutes at [epochMillis], from the platform's own time API (the browser has no tz database to load). */
internal expect fun localOffsetMinutes(epochMillis: Long): Int

/** A TCP link to a node's port, or null where the target has none. */
internal expect fun createTcpTransport(host: String, port: Int): RadioTransport?

/** The platform's small persistent string store: Preferences, SharedPreferences, NSUserDefaults or localStorage. */
internal expect fun createKeyValueStore(): KeyValueStore
