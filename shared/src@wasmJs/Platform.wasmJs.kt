package org.meshtastic.ambient

import org.meshtastic.ambient.audio.resumeAudioFromGesture
import org.meshtastic.sdk.RadioTransport

class WasmPlatform : Platform {
    override val name: String = "Web with Kotlin/Wasm"
    override val supportsTcp: Boolean = false
    override val lookaheadMillis: Int = 1200
    override val needsTapToStart: Boolean = true

    override fun resumeAudio() = resumeAudioFromGesture()
}

actual fun getPlatform(): Platform = WasmPlatform()

@JsFun("(ms) => -new Date(ms).getTimezoneOffset()")
private external fun jsOffsetMinutes(ms: Double): Int

actual fun localOffsetMinutes(epochMillis: Long): Int = jsOffsetMinutes(epochMillis.toDouble())

actual fun createTcpTransport(host: String, port: Int): RadioTransport? = null
