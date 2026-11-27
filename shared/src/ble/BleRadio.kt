package org.meshtastic.aether.ble

/** A radio seen by a scan or picked in a chooser. [handle] is the platform object the transport is built from. */
class BleRadio(val name: String?, val rssi: Int?, val address: String, val handle: Any) {
    override fun toString(): String = "BleRadio(name=$name, rssi=$rssi, address=$address)"
}
