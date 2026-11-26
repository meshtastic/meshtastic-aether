package org.meshtastic.aether.serial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The by-id names have the form udev gives real boards; three of these boards report the same USB product, "TinyUSB Serial". */
class SerialLabelTest {
    @Test
    fun `a by-id path reads as the vendor and product without the serial number or interface`() {
        assertEquals("RAKwireless WisCore RAK4631 Board", byIdLabel("/dev/serial/by-id/usb-RAKwireless_WisCore_RAK4631_Board_0123456789ABCDEF-if00"))
        assertEquals("Seeed Studio TRACKER L1", byIdLabel("/dev/serial/by-id/usb-Seeed_Studio_TRACKER_L1_FEDCBA9876543210-if00"))
        assertEquals("Seeed Studio T1000-E-BOOT", byIdLabel("/dev/serial/by-id/usb-Seeed_Studio_T1000-E-BOOT_00112233AABBCCDD-if00"))
        assertEquals("Silicon Labs CP2102 USB to UART Bridge Controller", byIdLabel("/dev/serial/by-id/usb-Silicon_Labs_CP2102_USB_to_UART_Bridge_Controller_0001-if00-port0"))
    }

    @Test
    fun `any other path has no by-id name`() {
        assertNull(byIdLabel("ttyS0"))
        assertNull(byIdLabel("/dev/ttyACM0"))
        assertNull(byIdLabel("COM3"))
    }
}
