package org.meshtastic.ambient.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioListTest {
    private fun radio(address: String, rssi: Int?, name: String? = address) = BleRadio(name, rssi, address, Unit)

    // Regression: a picker that lists a radio once per advertisement is unusable.
    @Test
    fun `one entry per address with the latest signal`() {
        val list = RadioList()
        assertTrue(list.onSeen(radio("A", -80), 0))
        assertFalse(list.onSeen(radio("A", -60), 1_000))
        assertEquals(listOf(-60), list.visible(1_000).map { it.rssi })
    }

    @Test
    fun `strongest first and a radio without a signal last`() {
        val list = RadioList()
        list.onSeen(radio("A", -90), 0)
        list.onSeen(radio("B", null), 0)
        list.onSeen(radio("C", -40), 0)
        assertEquals(listOf("C", "A", "B"), list.visible(0).map { it.address })
    }

    // Regression: a radio that powered off keeps showing, and tapping it times out.
    // Stale window 15 000 ms: seen at 0, still visible at exactly 15 000, gone at 15 001.
    @Test
    fun `a radio not heard for the stale window drops out and returns when heard again`() {
        val list = RadioList(staleMillis = 15_000)
        list.onSeen(radio("A", -50), 0)
        assertEquals(1, list.visible(15_000).size)
        assertEquals(0, list.visible(15_001).size)
        assertFalse(list.onSeen(radio("A", -50), 20_000))
        assertEquals(1, list.visible(20_000).size)
    }

    @Test
    fun `equal signals order by name then address`() {
        val list = RadioList()
        list.onSeen(radio("2", -50, "zed"), 0)
        list.onSeen(radio("1", -50, "alpha"), 0)
        list.onSeen(radio("3", -50, null), 0)
        assertEquals(listOf("1", "2", "3"), list.visible(0).map { it.address })
    }
}
