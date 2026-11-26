package org.meshtastic.ambient.ble

/** The radios a scan has seen lately, strongest first; one entry per address. */
class RadioList(private val staleMillis: Long = 15_000) {
    private class Entry(var radio: BleRadio, var seenAt: Long)

    private val entries = LinkedHashMap<String, Entry>()

    /** Returns true when [radio] is new to the list. */
    fun onSeen(radio: BleRadio, nowMillis: Long): Boolean {
        val existing = entries[radio.address]
        if (existing == null) {
            entries[radio.address] = Entry(radio, nowMillis)
            return true
        }
        existing.radio = radio
        existing.seenAt = nowMillis
        return false
    }

    /** Radios heard within the stale window, by RSSI descending (unknown last), then name, then address. */
    fun visible(nowMillis: Long): List<BleRadio> =
        entries.values.filter { nowMillis - it.seenAt <= staleMillis }.map { it.radio }
            .sortedWith(
                compareByDescending<BleRadio> { it.rssi ?: Int.MIN_VALUE }
                    .thenBy { it.name ?: "￿" }
                    .thenBy { it.address },
            )
}
