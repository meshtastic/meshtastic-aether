package org.meshtastic.aether.blescan

import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.aether.ble.RadioList
import org.meshtastic.aether.ble.bleScan
import org.meshtastic.aether.ui.nowMillis

private const val DEFAULT_SCAN_SECONDS = 15L
private const val MILLIS_PER_SECOND = 1000L

/**
 * Scans for Meshtastic radios for N seconds (default 15) and prints each one as it is first seen, then the final
 * list.
 */
fun main(args: Array<String>) = runBlocking {
    val seconds = args.firstOrNull()?.toLongOrNull() ?: DEFAULT_SCAN_SECONDS
    val scanMillis = seconds * MILLIS_PER_SECOND
    val flow = bleScan() ?: error("Bluetooth scanning is not available on this platform")
    val list = RadioList(staleMillis = scanMillis)
    println("scanning for ${seconds}s")
    withTimeoutOrNull(scanMillis) {
        flow.catch { println("scan failed: ${it::class.simpleName}: ${it.message}") }.collect {
            if (list.onSeen(it, nowMillis())) println("found ${it.name ?: "unnamed"} ${it.address} rssi=${it.rssi}")
        }
    }
    val radios = list.visible(nowMillis())
    println("done: ${radios.size} radio(s)")
    for (r in radios) println("  ${r.name ?: "unnamed"}  ${r.address}  rssi=${r.rssi}")
}
