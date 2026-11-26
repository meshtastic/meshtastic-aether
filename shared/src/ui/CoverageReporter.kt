package org.meshtastic.ambient.ui

import org.meshtastic.ambient.mesh.Coverage
import org.meshtastic.ambient.mesh.MeshEvent

/**
 * Feeds mesh events to a [Coverage] and writes its line to [write]: once [FIRST_MILLIS] after the link first comes
 * up, so the first reading is early, then every [PERIOD_MILLIS]. Times are the caller's clock.
 */
internal class CoverageReporter(
    private val coverage: Coverage = Coverage(),
    private val write: (String) -> Unit,
    /** Appended to each line, for example a count the mood knows and the tally does not. */
    private val extra: () -> String = { "" },
) {
    private var nextMillis = Long.MIN_VALUE

    fun onEvent(e: MeshEvent, nowMillis: Long) {
        coverage.add(e)
        if (e is MeshEvent.Link && isLinked(e.state) && nextMillis == Long.MIN_VALUE) nextMillis = nowMillis + FIRST_MILLIS
    }

    fun tick(nowMillis: Long) {
        if (nextMillis == Long.MIN_VALUE || nowMillis < nextMillis) return
        write(coverage.describe() + extra())
        nextMillis = nowMillis + PERIOD_MILLIS
    }

    companion object {
        const val FIRST_MILLIS = 10 * 60_000L
        const val PERIOD_MILLIS = 60 * 60_000L
    }
}
