package org.meshtastic.ambient.mood

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong

internal fun clamp01(v: Double): Double = if (v.isNaN()) 0.0 else v.coerceIn(0.0, 1.0)

internal fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/** 0 with no samples, 1 once [full] samples (or an equivalent decayed weight) exist. */
internal fun ramp(n: Double, full: Double): Double = if (full <= 0.0) 1.0 else clamp01(n / full)

internal fun sign(v: Double): Int = if (v > 0.0) 1 else if (v < 0.0) -1 else 0

/** Median of the first [n] entries of [values] (which it reorders); 0 for none. */
internal fun median(values: DoubleArray, n: Int): Double {
    if (n <= 0) return 0.0
    values.sort(0, n)
    return if (n % 2 == 1) values[n / 2] else 0.5 * (values[n / 2 - 1] + values[n / 2])
}

/** [v] with [decimals] places and no String.format (not in common Kotlin). */
internal fun formatDecimal(v: Double, decimals: Int): String {
    if (!v.isFinite()) return "-"
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val r = (abs(v) * scale).roundToLong()
    val body = if (decimals == 0) (r / scale).toString() else (r / scale).toString() + "." + (r % scale).toString().padStart(decimals, '0')
    return if (v < 0 && r != 0L) "-$body" else body
}

/** An accumulator that decays exponentially with [tauSeconds] against event time. */
internal class Decay(private val tauSeconds: Double) {
    var value = 0.0
        private set
    private var last = Long.MIN_VALUE

    fun advance(ms: Long) {
        if (last == Long.MIN_VALUE) {
            last = ms
            return
        }
        val dt = (ms - last) / 1000.0
        if (dt > 0) {
            value *= exp(-dt / tauSeconds)
            last = ms
        }
    }

    fun add(amount: Double) {
        value += amount
    }
}

/**
 * Maps a value onto 0..1 against its own rolling 24 h range, kept as per-minute buckets.
 * Until enough minutes exist the absolute default range is used, blended in so there is no jump.
 */
internal class RollingRange(
    private val minSpan: Double,
    private val defaultLo: Double,
    private val defaultHi: Double,
    private val windowMinutes: Int = 24 * 60,
    private val rampBuckets: Int = 120,
) {
    private val lo = DoubleArray(windowMinutes)
    private val hi = DoubleArray(windowMinutes)
    private val stamp = LongArray(windowMinutes) { Long.MIN_VALUE }
    private var latest = Long.MIN_VALUE
    private var cLo = 0.0
    private var cHi = 0.0
    private var cCount = 0

    fun observe(x: Double, atMillis: Long) {
        if (!x.isFinite()) return
        val m = atMillis.floorDiv(60_000L)
        if (latest == Long.MIN_VALUE || m > latest) latest = m
        if (m <= latest - windowMinutes) return
        val i = m.mod(windowMinutes)
        if (stamp[i] != m) {
            stamp[i] = m
            lo[i] = x
            hi[i] = x
            rescan()
        } else {
            if (x < lo[i]) lo[i] = x
            if (x > hi[i]) hi[i] = x
            if (x < cLo) cLo = x
            if (x > cHi) cHi = x
        }
    }

    private fun rescan() {
        var l = Double.MAX_VALUE
        var h = -Double.MAX_VALUE
        var n = 0
        for (i in 0 until windowMinutes) {
            val s = stamp[i]
            if (s == Long.MIN_VALUE || s <= latest - windowMinutes || s > latest) continue
            n++
            if (lo[i] < l) l = lo[i]
            if (hi[i] > h) h = hi[i]
        }
        cCount = n
        cLo = l
        cHi = h
    }

    fun normalize(x: Double): Double {
        if (!x.isFinite()) return 0.5
        val abs = clamp01((x - defaultLo) / (defaultHi - defaultLo))
        if (cCount < 2) return abs
        var l = cLo
        var h = cHi
        if (h - l < minSpan) {
            val c = 0.5 * (h + l)
            l = c - 0.5 * minSpan
            h = c + 0.5 * minSpan
        }
        val rel = clamp01((x - l) / (h - l))
        return lerp(abs, rel, clamp01((cCount - RAMP_START).toDouble() / rampBuckets))
    }

    private companion object {
        const val RAMP_START = 5
    }
}
