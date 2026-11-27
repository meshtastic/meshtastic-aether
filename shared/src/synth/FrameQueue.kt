package org.meshtastic.aether.synth

import org.meshtastic.aether.music.ControlEvent
import org.meshtastic.aether.music.NoteEvent
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Events handed from one producer thread to the render thread, then held in frame order until they are due.
 *
 * [offer] puts an event on a lock-free ring whose size is a power of two; [drain], on the render thread, moves what
 * arrived into the [pending] list sorted by frame. Nothing allocates.
 */
@OptIn(ExperimentalAtomicApi::class)
internal abstract class FrameQueue<T : Any>(private val ring: Array<T?>, private val pending: Array<T?>) {
    private val ringMask = ring.size - 1
    private val head = AtomicInt(0)
    private val tail = AtomicInt(0)
    private var count = 0

    /** Events drained from the ring and still waiting for their frame. */
    val size: Int get() = count

    protected abstract fun frameOf(event: T): Long

    /** Producer side: queues [event], or returns false when the ring is full. */
    fun offer(event: T): Boolean {
        val t = tail.load()
        if (t - head.load() >= ring.size) return false
        ring[t and ringMask] = event
        tail.store(t + 1)
        return true
    }

    /** Render side: sorts every handed-off event into the pending list; returns how many did not fit. */
    fun drain(): Int {
        var h = head.load()
        val t = tail.load()
        var refused = 0
        while (h != t) {
            val event = ring[h and ringMask]!!
            ring[h and ringMask] = null
            h++
            if (!insert(event)) refused++
        }
        head.store(h)
        return refused
    }

    private fun insert(event: T): Boolean {
        if (count == pending.size) return false
        var i = count
        while (i > 0 && frameOf(pending[i - 1]!!) > frameOf(event)) {
            pending[i] = pending[i - 1]
            i--
        }
        pending[i] = event
        count++
        return true
    }

    /** True when the earliest pending event falls at or before [now]. */
    fun isDue(now: Long): Boolean = count > 0 && frameOf(pending[0]!!) <= now

    /** Removes and returns the earliest pending event. */
    fun pop(): T {
        val event = pending[0]!!
        for (i in 1 until count) pending[i - 1] = pending[i]
        count--
        pending[count] = null
        return event
    }

    /** [frames], cut short so a chunk starting at [now] ends where the earliest pending event falls. */
    fun clampChunk(frames: Int, now: Long): Int =
        if (count > 0) minOf(frames.toLong(), frameOf(pending[0]!!) - now).toInt() else frames
}

internal class NoteQueue(ringSize: Int, pendingSize: Int) :
    FrameQueue<NoteEvent>(arrayOfNulls(ringSize), arrayOfNulls(pendingSize)) {
    override fun frameOf(event: NoteEvent): Long = event.atFrame
}

internal class TextureQueue(ringSize: Int, pendingSize: Int) :
    FrameQueue<ControlEvent.SetTexture>(arrayOfNulls(ringSize), arrayOfNulls(pendingSize)) {
    override fun frameOf(event: ControlEvent.SetTexture): Long = event.atFrame
}
