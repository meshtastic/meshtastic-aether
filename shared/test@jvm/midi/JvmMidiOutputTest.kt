package org.meshtastic.aether.midi

import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.Receiver
import javax.sound.midi.Transmitter
import kotlin.test.Test
import kotlin.test.assertTrue

/** A device whose receiver records what reaches it. */
private class RecordingDevice : MidiDevice, Receiver {
    val got = java.util.Collections.synchronizedList(ArrayList<List<Int>>())
    private var open = false

    override fun send(message: MidiMessage, timeStamp: Long) {
        got += message.message.map { it.toInt() and 0xFF }
    }

    override fun getDeviceInfo(): MidiDevice.Info = object : MidiDevice.Info("recording", "test", "", "1") {}
    override fun open() { open = true }
    override fun close() { open = false }
    override fun isOpen() = open
    override fun getMicrosecondPosition() = -1L
    override fun getMaxReceivers() = -1
    override fun getMaxTransmitters() = 0
    override fun getReceiver(): Receiver = this
    override fun getReceivers(): List<Receiver> = listOf(this)
    override fun getTransmitter(): Transmitter = throw UnsupportedOperationException()
    override fun getTransmitters(): List<Transmitter> = emptyList()
}

class JvmMidiOutputTest {
    // The mapper counts a note ended once it hands over the note-off; a stop that drops that note-off before its time
    // would leave the note sounding on the receiver unless the port ends what it sent.
    @Test
    fun `closing ends a note whose queued note-off it dropped`() {
        val device = RecordingDevice()
        val port = JvmMidiOutput("recording", device, device)
        val now = System.nanoTime()
        port.send(byteArrayOf(0x91.toByte(), 60, 90), now)
        port.send(byteArrayOf(0x81.toByte(), 60, 64), now + 10_000_000_000L)
        val deadline = now + 2_000_000_000L
        while (device.got.isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait()
        port.cancel()
        port.close()
        assertTrue(device.got.first() == listOf(0x91, 60, 90), "the note-on never went out: ${device.got}")
        assertTrue(listOf(0x81, 60, 64) in device.got, "no note-off for the sounding note: ${device.got}")
    }
}
