package org.meshtastic.ambient.music

/** Fires once when the median pressure has fallen [DROP_HPA] or more over 3 to 4 hours; then rests for 3 hours. */
internal class PressureWatch {
    private val samples = ArrayDeque<Pair<Long, Float>>()
    private var lastFire = Long.MIN_VALUE

    fun sample(ms: Long, hpa: Float): Boolean {
        if (samples.isEmpty() || ms - samples.last().first >= SAMPLE_MS) samples.addLast(ms to hpa)
        while (samples.isNotEmpty() && ms - samples.first().first > KEEP_MS) samples.removeFirst()
        val ref = samples.lastOrNull { ms - it.first >= WINDOW_MS && ms - it.first <= MAX_AGE_MS } ?: return false
        if (ref.second - hpa < DROP_HPA) return false
        if (lastFire != Long.MIN_VALUE && ms - lastFire < WINDOW_MS) return false
        lastFire = ms
        return true
    }

    companion object {
        const val DROP_HPA = 2.0f
        const val WINDOW_MS = 3L * 3600 * 1000
        const val MAX_AGE_MS = 4L * 3600 * 1000
        const val SAMPLE_MS = 10L * 60 * 1000
        private const val KEEP_MS = 5L * 3600 * 1000
    }
}

/** Reports +1 when a 0..1 reading rises through [HIGH], -1 when it falls through [LOW]; the first reading only sets the side. */
internal class Crossing {
    private var warm: Boolean? = null

    fun update(x: Float): Int {
        val side = warm
        return when {
            side == null -> { warm = x >= MID; 0 }
            !side && x > HIGH -> { warm = true; 1 }
            side && x < LOW -> { warm = false; -1 }
            else -> 0
        }
    }

    companion object {
        const val LOW = 0.45f
        const val HIGH = 0.55f
        private const val MID = 0.5f
    }
}
