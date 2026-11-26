package org.meshtastic.ambient.blescan

import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.ambient.ble.RadioList
import org.meshtastic.ambient.ble.bleScan
import org.meshtastic.ambient.ui.nowMillis

/** Scans for Meshtastic radios for N seconds (default 15) and prints each one as it is first seen, then the final list. */
fun main(args: Array<String>) = runBlocking {
    val seconds = args.firstOrNull()?.toLongOrNull() ?: 15L
    val flow = bleScan() ?: error("Bluetooth scanning is not available on this platform")
    val list = RadioList(staleMillis = seconds * 1000)
    println("scanning for ${seconds}s")
    withTimeoutOrNull(seconds * 1000) {
        flow.catch { println("scan failed: ${it::class.simpleName}: ${it.message}") }.collect {
            if (list.onSeen(it, nowMillis())) println("found ${it.name ?: "unnamed"} ${it.address} rssi=${it.rssi}")
        }
    }
    val radios = list.visible(nowMillis())
    println("done: ${radios.size} radio(s)")
    for (r in radios) println("  ${r.name ?: "unnamed"}  ${r.address}  rssi=${r.rssi}")
}
