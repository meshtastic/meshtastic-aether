package org.meshtastic.aether.mesh

/** The broadcast destination, `NODENUM_BROADCAST` in the firmware. */
internal const val BROADCAST_NUM = 0xFFFFFFFFL

/** A node number (a proto `uint32`, which Wire reads as a signed Int) as its unsigned value. */
internal fun Int.toNodeNum(): Long = toUInt().toLong()

/** The low byte of a node number: all a packet's `relay_node` field carries. */
internal fun Long.lowByte(): Int = toUByte().toInt()
