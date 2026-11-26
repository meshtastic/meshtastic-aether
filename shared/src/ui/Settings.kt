package org.meshtastic.aether.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.meshtastic.aether.theme.PaletteChoice

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

/** What the app remembers between launches: the last link choice, the palette, the volume and the mute. */
internal class Settings(private val store: KeyValueStore) {
    /** The link to reconnect to; null when none was saved. A choice that cannot be encoded leaves the saved link as it was. */
    var link: LinkChoice?
        get() = store.get(KEY_LINK)?.let(::decodeLink)
        set(value) {
            if (value == null) {
                store.remove(KEY_LINK)
                return
            }
            encodeLink(value)?.let { store.put(KEY_LINK, it) }
        }

    /** The palette the person chose, or null when none was ever saved. */
    val savedPalette: PaletteChoice?
        get() = PaletteChoice.entries.firstOrNull { it.name == store.get(KEY_PALETTE) }

    /** The link type last chosen on the connect screen, by name; the screen opens on it next time. */
    var linkKind: String?
        get() = store.get(KEY_LINK_KIND)
        set(value) = if (value == null) store.remove(KEY_LINK_KIND) else store.put(KEY_LINK_KIND, value)

    var palette: PaletteChoice
        get() = savedPalette ?: PaletteChoice.Standard
        set(value) = store.put(KEY_PALETTE, value.name)

    /** Master volume 0..1 before muting; full when nothing valid is saved. */
    var volume: Float
        get() = store.get(KEY_VOLUME)?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        set(value) = store.put(KEY_VOLUME, value.coerceIn(0f, 1f).toString())

    var muted: Boolean
        get() = store.get(KEY_MUTED) == "1"
        set(value) = store.put(KEY_MUTED, if (value) "1" else "0")

    /** Whether dry voices are placed to the side of the head for headphones instead of panned for speakers. */
    var headphones: Boolean
        get() = store.get(KEY_HEADPHONES) == "1"
        set(value) = store.put(KEY_HEADPHONES, if (value) "1" else "0")

    private companion object {
        const val KEY_HEADPHONES = "headphones"
        const val KEY_LINK = "link"
        const val KEY_LINK_KIND = "link_kind"
        const val KEY_PALETTE = "palette"
        const val KEY_VOLUME = "volume"
        const val KEY_MUTED = "muted"
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
