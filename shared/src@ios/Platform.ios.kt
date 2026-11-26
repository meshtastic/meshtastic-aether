package org.meshtastic.ambient

import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.transport.tcp.TcpTransport
import platform.UIKit.UIDevice

class IOSPlatform : Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
    override val supportsTcp: Boolean = true
    override val lookaheadMillis: Int = 400
    override val needsTapToStart: Boolean = false

    override fun resumeAudio() = Unit
}

actual fun getPlatform(): Platform = IOSPlatform()

actual fun createTcpTransport(host: String, port: Int): RadioTransport? = TcpTransport(host, port)
