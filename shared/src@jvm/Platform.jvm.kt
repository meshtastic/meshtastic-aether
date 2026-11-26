package org.meshtastic.ambient

import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.transport.tcp.TcpTransport

class JVMPlatform : Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
    override val supportsTcp: Boolean = true
    override val lookaheadMillis: Int = 400
    override val needsTapToStart: Boolean = false

    override fun resumeAudio() = Unit
}

actual fun getPlatform(): Platform = JVMPlatform()

actual fun localOffsetMinutes(epochMillis: Long): Int = java.util.TimeZone.getDefault().getOffset(epochMillis) / 60_000

actual fun createTcpTransport(host: String, port: Int): RadioTransport? = TcpTransport(host, port)
