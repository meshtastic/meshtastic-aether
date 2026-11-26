package org.meshtastic.aether

import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.transport.tcp.TcpTransport
import org.meshtastic.aether.ui.KeyValueStore
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.UIKit.UIDevice

internal class IOSPlatform : Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
    override val supportsTcp: Boolean = true
    override val lookaheadMillis: Int = 400
    override val needsTapToStart: Boolean = false
    override val hasKeyboard: Boolean = false
    override val autoConnects: Boolean = true

    override fun resumeAudio() = Unit
}

internal actual fun getPlatform(): Platform = IOSPlatform()

internal actual fun localOffsetMinutes(epochMillis: Long): Int =
    (NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)) / 60).toInt()

internal actual fun createTcpTransport(host: String, port: Int): RadioTransport? = TcpTransport(host, port)

private class UserDefaultsStore : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun get(key: String): String? = defaults.stringForKey(key)

    override fun put(key: String, value: String) = defaults.setObject(value, forKey = key)

    override fun remove(key: String) = defaults.removeObjectForKey(key)
}

internal actual fun createKeyValueStore(): KeyValueStore = UserDefaultsStore()
