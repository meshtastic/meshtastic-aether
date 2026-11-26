package org.meshtastic.aether.serial

import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.transport.serial.JvmSerialPorts
import org.meshtastic.sdk.transport.serial.isLikelyMeshtastic

internal actual fun serialSupported(): Boolean = true

internal actual suspend fun listSerialPorts(): List<SerialRadio> = JvmSerialPorts.list().map { info ->
    val byId = JvmSerialPorts.byIdPath(info.name)
    SerialRadio(
        path = byId ?: info.name,
        label = byId?.let(::byIdLabel) ?: info.usbProduct ?: info.description ?: info.name,
        likelyMeshtastic = info.isLikelyMeshtastic(),
    )
}

internal actual fun createSerialTransport(path: String): RadioTransport? = JvmSerialPorts.open(path)
