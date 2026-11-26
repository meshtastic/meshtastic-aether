@file:OptIn(ExperimentalWasmJsInterop::class)

package org.meshtastic.ambient

import org.meshtastic.ambient.audio.resumeAudioFromGesture
import org.meshtastic.ambient.ui.KeyValueStore
import org.meshtastic.sdk.RadioTransport
import kotlin.js.ExperimentalWasmJsInterop

internal class WasmPlatform : Platform {
    override val name: String = "Web with Kotlin/Wasm"
    override val supportsTcp: Boolean = false
    override val lookaheadMillis: Int = 1200
    override val needsTapToStart: Boolean = true
    override val hasKeyboard: Boolean = true
    override val autoConnects: Boolean = false

    override fun resumeAudio() = resumeAudioFromGesture()
}

internal actual fun getPlatform(): Platform = WasmPlatform()

@JsFun("(ms) => -new Date(ms).getTimezoneOffset()")
private external fun jsOffsetMinutes(ms: Double): Int

internal actual fun localOffsetMinutes(epochMillis: Long): Int = jsOffsetMinutes(epochMillis.toDouble())

internal actual fun createTcpTransport(host: String, port: Int): RadioTransport? = null

@JsFun("(k) => { try { return localStorage.getItem(k); } catch (e) { return null; } }")
private external fun storageGet(key: String): String?

@JsFun("(k, v) => { try { localStorage.setItem(k, v); } catch (e) {} }")
private external fun storagePut(key: String, value: String)

@JsFun("(k) => { try { localStorage.removeItem(k); } catch (e) {} }")
private external fun storageRemove(key: String)

private class LocalStorageStore : KeyValueStore {
    override fun get(key: String): String? = storageGet(key)

    override fun put(key: String, value: String) = storagePut(key, value)

    override fun remove(key: String) = storageRemove(key)
}

internal actual fun createKeyValueStore(): KeyValueStore = LocalStorageStore()
