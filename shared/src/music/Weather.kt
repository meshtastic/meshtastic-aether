package org.meshtastic.aether.music

enum class Quantity(val defaultMin: Float, val defaultMax: Float, val minSpan: Float) {
    Temperature(-10f, 35f, 2f),
    Humidity(0f, 100f, 10f),
    Pressure(980f, 1040f, 4f),
    Wind(0f, 15f, 2f),
    Lux(0f, 20000f, 100f),
}

/**
 * Scales each quantity against its own rolling range so a cold season and a warm one use the same 0..1 span.
 * With fewer than two samples or a range narrower than the quantity's minimum span it uses absolute defaults.
 */
class WeatherScaler(private val windowMillis: Long = 7L * 24 * 3600 * 1000) {
    private class Bucket(val minute: Long, var lo: Float, var hi: Float)

    private val buckets = Quantity.entries.associateWith { ArrayDeque<Bucket>() }
    private val counts = Quantity.entries.associateWith { 0 }.toMutableMap()
    private var latest = Long.MIN_VALUE

    fun observe(q: Quantity, value: Float, atMillis: Long) {
        if (!value.isFinite()) return
        latest = maxOf(latest, atMillis)
        val deque = buckets.getValue(q)
        val minute = atMillis / 60_000
        val last = deque.lastOrNull()
        if (last != null && last.minute == minute) {
            last.lo = minOf(last.lo, value)
            last.hi = maxOf(last.hi, value)
        } else {
            deque.addLast(Bucket(minute, value, value))
        }
        counts[q] = counts.getValue(q) + 1
        val cutoff = (latest - windowMillis) / 60_000
        while (deque.isNotEmpty() && deque.first().minute < cutoff) deque.removeFirst()
    }

    /** [value] mapped to 0..1; 0.5 for a non-finite value. */
    fun scale(q: Quantity, value: Float): Float {
        if (!value.isFinite()) return 0.5f
        val deque = buckets.getValue(q)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (b in deque) {
            lo = minOf(lo, b.lo)
            hi = maxOf(hi, b.hi)
        }
        val rolling = counts.getValue(q) >= 2 && deque.isNotEmpty() && hi - lo >= q.minSpan
        val min = if (rolling) lo else q.defaultMin
        val max = if (rolling) hi else q.defaultMax
        return ((value - min) / (max - min)).coerceIn(0f, 1f)
    }
}
