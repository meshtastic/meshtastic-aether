package org.meshtastic.aether.visual

/** The faint lines between known nodes, one per node pair, each fading unless it is reported again. */
internal class EdgeSet(private val sampleRate: Int) {
    private val map = LinkedHashMap<Pair<Long, Long>, Edge>()

    val values: Collection<Edge> get() = map.values

    /** Adds or refreshes the edge between [a] and [b] when both are known to [roster]; the oldest goes past the cap. */
    fun add(a: Long, b: Long, frame: Long, roster: OrbRoster) {
        if (a == b || !roster.isKnown(a) || !roster.isKnown(b)) return
        val key = Pair(minOf(a, b), maxOf(a, b))
        val edge = map[key]
        if (edge != null) {
            edge.refreshedFrame = frame
            return
        }
        map[key] = Edge(key.first, key.second, frame)
        if (map.size > SceneModel.MAX_EDGES) map.remove(map.keys.first())
    }

    /** 1.0 when reported at [frame], fading linearly to 0 over [SceneModel.EDGE_FADE_SECONDS]. */
    fun fade(edge: Edge, frame: Long): Double =
        (1.0 - (frame - edge.refreshedFrame).coerceAtLeast(0L).toDouble() / sampleRate / SceneModel.EDGE_FADE_SECONDS)
            .coerceIn(0.0, 1.0)

    /** Drops the edges fully faded by [frame]. */
    fun expire(frame: Long) {
        map.values.removeAll { (frame - it.refreshedFrame).toDouble() / sampleRate >= SceneModel.EDGE_FADE_SECONDS }
    }
}
