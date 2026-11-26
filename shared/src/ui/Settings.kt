package org.meshtastic.ambient.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.meshtastic.ambient.theme.PaletteChoice

/** A small persistent string store; the platform supplies the real one. Implementations must not throw. */
internal interface KeyValueStore {
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)
}

/** A store that forgets on exit: the fallback where no persistent one is available, and the test fake. */
internal class MemoryStore : KeyValueStore {
    private val map = HashMap<String, String>()

    override fun get(key: String): String? = map[key]

    override fun put(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}

/** What the app remembers between launches: the last link choice and the palette. */
internal class Settings(private val store: KeyValueStore) {
    /** The link to reconnect to; null when none was chosen or the last one cannot be saved. */
    var link: LinkChoice?
        get() = store.get(KEY_LINK)?.let(::decodeLink)
        set(value) {
            val encoded = value?.let(::encodeLink)
            if (encoded == null) store.remove(KEY_LINK) else store.put(KEY_LINK, encoded)
        }

    var palette: PaletteChoice
        get() = PaletteChoice.entries.firstOrNull { it.name == store.get(KEY_PALETTE) } ?: PaletteChoice.Standard
        set(value) = store.put(KEY_PALETTE, value.name)

    private companion object {
        const val KEY_LINK = "link"
        const val KEY_PALETTE = "palette"
    }
}

private const val SEP = '|'

/** The saved form of [choice], or null for a choice that cannot be saved (Bluetooth, the capture replay). */
internal fun encodeLink(choice: LinkChoice): String? = when (choice) {
    LinkChoice.Demo -> "demo"
    is LinkChoice.Tcp -> "tcp$SEP${choice.host}$SEP${choice.port}"
    is LinkChoice.WebSocket -> "ws$SEP${choice.url}"
    LinkChoice.Replay, is LinkChoice.Ble -> null
}

/** Inverse of [encodeLink]; null for anything unreadable, so a corrupt store means no saved link. */
internal fun decodeLink(text: String): LinkChoice? {
    val parts = text.split(SEP, limit = 3)
    return when (parts[0]) {
        "demo" -> LinkChoice.Demo
        "tcp" -> {
            val port = parts.getOrNull(2)?.toIntOrNull() ?: return null
            val host = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
            LinkChoice.Tcp(host, port)
        }
        "ws" -> text.substringAfter(SEP, "").takeIf { it.isNotBlank() }?.let { LinkChoice.WebSocket(it) }
        else -> null
    }
}

/**
 * A cancelable countdown in whole seconds. [tick] is called once a second; it returns true exactly once, on the
 * tick that reaches zero, and never after [cancel].
 */
internal class Countdown(private val total: Int) {
    var secondsLeft by mutableIntStateOf(total)
        private set

    var cancelled by mutableStateOf(false)
        private set

    fun cancel() {
        cancelled = true
    }

    fun tick(): Boolean {
        if (cancelled || secondsLeft <= 0) return false
        secondsLeft--
        return secondsLeft == 0
    }
}
