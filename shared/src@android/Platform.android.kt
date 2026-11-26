package org.meshtastic.aether

import android.content.Context
import android.os.Build
import org.meshtastic.aether.ui.KeyValueStore
import org.meshtastic.aether.ui.MemoryStore
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.transport.tcp.TcpTransport

internal class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
    override val supportsTcp: Boolean = true
    override val lookaheadMillis: Int = 400
    override val needsTapToStart: Boolean = false
    override val hasKeyboard: Boolean = false
    override val autoConnects: Boolean = true

    override fun resumeAudio() = Unit
}

internal actual fun getPlatform(): Platform = AndroidPlatform()

internal actual fun localOffsetMinutes(epochMillis: Long): Int = java.util.TimeZone.getDefault().getOffset(epochMillis) / 60_000

internal actual fun createTcpTransport(host: String, port: Int): RadioTransport? = TcpTransport(host, port)

/** Set by the host activity before the first composition; the saved link and palette need a [Context]. */
object AndroidAppContext {
    var context: Context? = null
}

private class SharedPreferencesStore(context: Context) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences("meshtastic-aether", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()

    override fun remove(key: String) = prefs.edit().remove(key).apply()
}

internal actual fun createKeyValueStore(): KeyValueStore =
    AndroidAppContext.context?.let { SharedPreferencesStore(it) } ?: MemoryStore()
