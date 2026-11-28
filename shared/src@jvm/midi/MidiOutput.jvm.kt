package org.meshtastic.aether.midi

import org.meshtastic.aether.log.Log
import java.util.concurrent.locks.ReentrantLock
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiSystem
import javax.sound.midi.MidiUnavailableException
import javax.sound.midi.Receiver
import javax.sound.midi.Sequencer
import javax.sound.midi.ShortMessage
import kotlin.concurrent.withLock

private const val TAG = "midi"
private const val BYTE_MASK = 0xFF
private const val CLOSE_JOIN_MILLIS = 500L

/** Every device that takes messages, the Java software synth included; sequencers are not outputs. */
private fun outputDevices(): List<MidiDevice> = MidiSystem.getMidiDeviceInfo().mapNotNull { info ->
    try {
        MidiSystem.getMidiDevice(info).takeIf { it !is Sequencer && it.maxReceivers != 0 }
    } catch (e: MidiUnavailableException) {
        Log.w(TAG, "skipping ${info.name}", e)
        null
    }
}

actual fun midiOutputNames(): List<String> = outputDevices().map { it.deviceInfo.name }

actual fun openMidiOutput(name: String): MidiOutput? {
    val devices = outputDevices()
    val device = devices.firstOrNull { it.deviceInfo.name == name }
        ?: devices.firstOrNull { it.deviceInfo.name.contains(name, ignoreCase = true) }
        ?: return null
    return try {
        OpenDevices.open(device)
        JvmMidiOutput(device.deviceInfo.name, device, device.receiver)
    } catch (e: MidiUnavailableException) {
        Log.e(TAG, "could not open ${device.deviceInfo.name}", e)
        OpenDevices.close(device)
        null
    }
}

/**
 * `MidiSystem` hands every caller the same device object, so the next engine opens a port before the one it replaces
 * closes it; the device closes when its last output does.
 */
private object OpenDevices {
    private val users = HashMap<MidiDevice, Int>()

    @Synchronized
    fun open(device: MidiDevice) {
        if (!device.isOpen) device.open()
        users[device] = (users[device] ?: 0) + 1
    }

    @Synchronized
    fun close(device: MidiDevice) {
        val left = (users[device] ?: 1) - 1
        if (left > 0) {
            users[device] = left
        } else {
            users.remove(device)
            device.close()
        }
    }
}

/**
 * `javax.sound.midi` sends at once and ignores timestamps on most devices, so a thread holds each message until its
 * time on `System.nanoTime`, the clock the audio sink's timestamps use.
 */
private class JvmMidiOutput(
    override val name: String,
    private val device: MidiDevice,
    private val receiver: Receiver,
) : MidiOutput {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queue = TimedQueue()
    private var closing = false
    private val thread = Thread(::run, "aether-midi").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY
        start()
    }

    override fun send(data: ByteArray, atNanos: Long) = lock.withLock {
        queue.add(data, atNanos)
        changed.signal()
    }

    override fun cancel() = lock.withLock { queue.clear() }

    override fun close() {
        lock.withLock {
            closing = true
            changed.signal()
        }
        thread.join(CLOSE_JOIN_MILLIS)
        receiver.close()
        OpenDevices.close(device)
    }

    private fun run() {
        val due = ArrayList<ByteArray>()
        while (true) {
            val last = lock.withLock { awaitDue(due) }
            for (data in due) receiver.send(ShortMessage(byte(data, 0), byte(data, 1), byte(data, 2)), -1)
            due.clear()
            if (last) return
        }
    }

    /** Waits until a message is due and moves what is due into [due]; true when closing, once what is due is out. */
    private fun awaitDue(due: MutableList<ByteArray>): Boolean {
        while (true) {
            queue.popDue(System.nanoTime()) { due += it }
            if (due.isNotEmpty() || closing) return closing
            val next = queue.nextAt()
            if (next == null) changed.await() else changed.awaitNanos(next - System.nanoTime())
        }
    }

    private fun byte(data: ByteArray, i: Int): Int = (data.getOrNull(i)?.toInt() ?: 0) and BYTE_MASK
}
