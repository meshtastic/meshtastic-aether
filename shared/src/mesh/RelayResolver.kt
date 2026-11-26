package org.meshtastic.ambient.mesh

/**
 * The node a packet's relay byte most likely names: the one known node, other than [source] and [self], whose low byte
 * matches. Null when the byte is absent or zero, when nothing matches, or when two or more nodes match (a 1-in-256
 * collision per pair), so callers fall back to the source instead of guessing.
 */
internal fun resolveRelay(known: Collection<Long>, relayByte: Int?, source: Long, self: Long? = null): Long? {
    if (relayByte == null || relayByte == 0) return null
    val matches = known.filter { it != source && it != self && (it and 0xFF).toInt() == relayByte }.distinct()
    return matches.singleOrNull()
}
