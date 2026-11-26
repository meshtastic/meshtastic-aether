package org.meshtastic.aether.serial

import org.meshtastic.sdk.RadioTransport

internal actual fun serialSupported(): Boolean = false

internal actual suspend fun listSerialPorts(): List<SerialRadio> = emptyList()

internal actual fun createSerialTransport(path: String): RadioTransport? = null
