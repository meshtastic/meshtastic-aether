package org.meshtastic.aether.midi

import org.meshtastic.aether.audio.FrameTime
import kotlin.math.abs
import kotlin.math.roundToLong

private const val NANOS_PER_SECOND = 1_000_000_000L

/**
 * The time a sample frame plays, from the audio sink's (frame, time) pairs. A pair is read after its frame played,
 * by up to a hardware period, so the clock follows the earliest pairs: a pair earlier than predicted is taken at
 * once, a later one moves the clock a little, and once the rate is fitted barely at all: how late a pair is
 * sweeps slowly when the reads keep step with the card's period. The rate is a least-squares fit over every pair
 * since the clock was anchored, since a sound card's clock runs a few hundred ppm off the system's. A pair off by
 * more than [jumpNanos] (an underrun, a route change) anchors the clock afresh.
 */
class MidiClock(private val sampleRate: Int, private val jumpNanos: Long = DEFAULT_JUMP_NANOS) {
    private val nominal = NANOS_PER_SECOND.toDouble() / sampleRate
    private var nanosPerFrame = nominal
    private var baseFrame = 0L
    private var baseNanos = 0L
    private val fit = RateFit()

    var anchored = false
        private set

    fun anchor(t: FrameTime) {
        if (!anchored || abs(t.nanos - nanosAt(t.frame)) > jumpNanos) {
            baseFrame = t.frame
            baseNanos = t.nanos
            fit.reset(t)
            anchored = true
            return
        }
        fit.add(t)
        val fitted = t.frame - fit.startFrame >= MIN_FIT_SECONDS * sampleRate
        if (fitted) nanosPerFrame = fit.slope().coerceIn(nominal * (1 - MAX_SKEW), nominal * (1 + MAX_SKEW))
        val predicted = nanosAt(t.frame)
        val error = t.nanos - predicted
        val rise = if (fitted) FITTED_RISE else RISE
        baseNanos = predicted + if (error < 0) error else (error * rise).roundToLong()
        baseFrame = t.frame
    }

    /** When [frame] plays; meaningful once [anchored]. */
    fun nanosAt(frame: Long): Long = baseNanos + ((frame - baseFrame) * nanosPerFrame).roundToLong()

    private companion object {
        const val DEFAULT_JUMP_NANOS = 200_000_000L

        /** The share of a late pair's error taken: at 10 pairs a second a late clock settles in about 10 s. */
        const val RISE = 0.01

        /** With the rate fitted, a late pair only corrects the fit's residual error. */
        const val FITTED_RISE = 0.0005

        /** Seconds of pairs before the fitted rate replaces the nominal one. */
        const val MIN_FIT_SECONDS = 10

        /** No sound card runs further off than this; a fit beyond it is noise. */
        const val MAX_SKEW = 0.001
    }
}

/** A running least-squares slope of time against frame, by Welford's method, relative to the first pair. */
private class RateFit {
    var startFrame = 0L
        private set
    private var startNanos = 0L
    private var n = 0
    private var meanFrame = 0.0
    private var meanNanos = 0.0
    private var cov = 0.0
    private var variance = 0.0

    fun reset(t: FrameTime) {
        startFrame = t.frame
        startNanos = t.nanos
        n = 0
        meanFrame = 0.0
        meanNanos = 0.0
        cov = 0.0
        variance = 0.0
        add(t)
    }

    fun add(t: FrameTime) {
        val f = (t.frame - startFrame).toDouble()
        val s = (t.nanos - startNanos).toDouble()
        n++
        val df = f - meanFrame
        meanFrame += df / n
        meanNanos += (s - meanNanos) / n
        cov += df * (s - meanNanos)
        variance += df * (f - meanFrame)
    }

    fun slope(): Double = cov / variance
}
