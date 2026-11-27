package org.meshtastic.aether.tools

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Objective stats for one analysis window. Levels are linear full scale (1.0 = 0 dBFS). */
data class WindowStats(
    val startSec: Double,
    val frames: Long,
    val peak: Double,
    val rms: Double,
    val dc: Double,
    val nanCount: Long,
    val clippedCount: Long,
    val centroidHz: Double,
    val onsets: Int,
    val longestQuietSec: Double,
    val lufs: Double = Double.NEGATIVE_INFINITY,
    val lowBandRatio: Double = 0.0,
    val pulse20: Double = 0.0,
    val longestRestSec: Double = 0.0,
)

const val CLIP_LEVEL = 0.999f
private const val FFT_SIZE = 4096
private const val HOP_SEC = 0.01
private const val ONSET_RATIO = 4.0
private const val ONSET_FLOOR = 1e-3
private const val ONSET_REFRACTORY_SEC = 0.1
private const val ONSET_TAU_SEC = 0.25
private const val QUIET_LEVEL = 1e-4
private const val SPECTRUM_PERIOD_SEC = 2.0

/** Streaming analyzer over interleaved stereo float frames; call [push] repeatedly, then [finish]. */
class WindowAnalyzer(private val sampleRate: Int, private val windowFrames: Long) {
    private val results = ArrayList<WindowStats>()
    private val hopFrames = (HOP_SEC * sampleRate).toInt().coerceAtLeast(1)
    private val spectrumPeriod = (SPECTRUM_PERIOD_SEC * sampleRate).toLong()
    private val hann = DoubleArray(FFT_SIZE) { (1 - cos(2 * PI * it / FFT_SIZE)) / 2 }
    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)
    private val power = DoubleArray(FFT_SIZE / 2 + 1)
    private val mono = DoubleArray(FFT_SIZE)
    private val kWeighting = KWeighting(sampleRate)
    private val envelope = Envelope(sampleRate)

    private var frameIndex = 0L
    private var winStart = 0L
    private var peak = 0.0
    private var sumSq = 0.0
    private var sum = 0.0
    private var samples = 0L
    private var nan = 0L
    private var clipped = 0L
    private var spectra = 0
    private var monoFill = -1
    private var hopSumSq = 0.0
    private var hopCount = 0
    private var hopPeak = 0.0
    private var slowEnv = 0.0
    private var sinceOnset = Int.MAX_VALUE / 2
    private var onsets = 0
    private var quietHops = 0
    private var longestQuietHops = 0
    private val alpha = 1.0 - kotlin.math.exp(-HOP_SEC / ONSET_TAU_SEC)

    fun push(block: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            val l = block[2 * f]
            val r = block[2 * f + 1]
            var m = 0.0
            var finite = 0
            for (ch in 0..1) {
                val x = if (ch == 0) l else r
                if (x != x || x.isInfinite()) {
                    nan++
                    continue
                }
                finite++
                val a = abs(x.toDouble())
                if (a > peak) peak = a
                if (a > hopPeak) hopPeak = a
                if (abs(x) >= CLIP_LEVEL) clipped++
                sumSq += a * a
                hopSumSq += a * a
                sum += x
                m += x
            }
            samples += finite
            hopCount += finite
            if (finite > 0) m /= finite
            if (finite == 2) kWeighting.push(l.toDouble(), r.toDouble())
            envelope.push(m)
            feedSpectrum(m)
            frameIndex++
            if ((frameIndex % hopFrames) == 0L) endHop()
            if (frameIndex - winStart >= windowFrames) closeWindow()
        }
    }

    fun finish(): List<WindowStats> {
        if (frameIndex > winStart) closeWindow()
        return results
    }

    private fun feedSpectrum(m: Double) {
        val pos = (frameIndex - winStart) % spectrumPeriod
        if (pos == 0L) monoFill = 0
        if (monoFill in 0 until FFT_SIZE) {
            mono[monoFill++] = m
            if (monoFill == FFT_SIZE) {
                accumulateSpectrum()
                monoFill = -1
            }
        }
    }

    private fun accumulateSpectrum() {
        for (i in 0 until FFT_SIZE) {
            re[i] = mono[i] * hann[i]
            im[i] = 0.0
        }
        fft(re, im)
        for (k in 0..FFT_SIZE / 2) power[k] += re[k] * re[k] + im[k] * im[k]
        spectra++
    }

    private fun endHop() {
        val env = if (hopCount > 0) sqrt(hopSumSq / hopCount) else 0.0
        if (env > ONSET_FLOOR && env > ONSET_RATIO * slowEnv && sinceOnset * HOP_SEC >= ONSET_REFRACTORY_SEC) {
            onsets++
            sinceOnset = 0
        } else {
            sinceOnset++
        }
        slowEnv += alpha * (env - slowEnv)
        if (hopPeak < QUIET_LEVEL) {
            quietHops++
            if (quietHops > longestQuietHops) longestQuietHops = quietHops
        } else {
            quietHops = 0
        }
        hopSumSq = 0.0
        hopCount = 0
        hopPeak = 0.0
    }

    private fun closeWindow() {
        var num = 0.0
        var den = 0.0
        var low = 0.0
        if (spectra > 0) {
            for (k in 0..FFT_SIZE / 2) {
                val hz = k.toDouble() * sampleRate / FFT_SIZE
                num += hz * power[k]
                den += power[k]
                if (hz <= LOW_BAND_HZ) low += power[k]
            }
        }
        val frames = frameIndex - winStart
        results += WindowStats(
            startSec = winStart.toDouble() / sampleRate,
            frames = frames,
            peak = peak,
            rms = if (samples > 0) sqrt(sumSq / samples) else 0.0,
            dc = if (samples > 0) sum / samples else 0.0,
            nanCount = nan,
            clippedCount = clipped,
            centroidHz = if (den > 1e-12) num / den else 0.0,
            onsets = onsets,
            longestQuietSec = longestQuietHops * HOP_SEC,
            lufs = integratedLoudness(kWeighting.subBlocks),
            lowBandRatio = if (den > 1e-12) low / den else 0.0,
            pulse20 = pulse20(envelope.steps),
            longestRestSec = longestRestSec(envelope.steps),
        )
        kWeighting.reset()
        envelope.reset()
        winStart = frameIndex
        peak = 0.0; sumSq = 0.0; sum = 0.0; samples = 0; nan = 0; clipped = 0
        power.fill(0.0); spectra = 0; monoFill = -1
        onsets = 0; longestQuietHops = 0; quietHops = 0
    }
}

internal fun fft(re: DoubleArray, im: DoubleArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            val tr = re[i]; re[i] = re[j]; re[j] = tr
            val ti = im[i]; im[i] = im[j]; im[j] = ti
        }
    }
    var len = 2
    while (len <= n) {
        val ang = -(2 * PI / len)
        val wr = cos(ang)
        val wi = sin(ang)
        var i = 0
        while (i < n) {
            var cr = 1.0
            var ci = 0.0
            for (k in 0 until len / 2) {
                val a = i + k
                val b = a + len / 2
                val xr = re[b] * cr - im[b] * ci
                val xi = re[b] * ci + im[b] * cr
                re[b] = re[a] - xr
                im[b] = im[a] - xi
                re[a] += xr
                im[a] += xi
                val ncr = cr * wr - ci * wi
                ci = cr * wi + ci * wr
                cr = ncr
            }
            i += len
        }
        len = len shl 1
    }
}

fun formatStats(stats: List<WindowStats>): String = buildString {
    appendLine(
        "start_min  peak_dBFS  rms_dBFS      dc  nan  clipped  centroid_Hz  onsets  longest_quiet_s" +
            "    LUFS  low_band  pulse20  longest_rest_s",
    )
    for (s in stats) {
        appendLine(
            "%9.1f  %9s  %8s  %6.4f  %3d  %7d  %11.0f  %6d  %15.2f  %6s  %8.3f  %7.2f  %14.1f".format(
                s.startSec / SECONDS_PER_MINUTE, db(s.peak), db(s.rms), s.dc, s.nanCount, s.clippedCount,
                s.centroidHz, s.onsets, s.longestQuietSec,
                if (s.lufs.isFinite()) "%.1f".format(s.lufs) else "-inf", s.lowBandRatio, s.pulse20,
                s.longestRestSec,
            ),
        )
    }
}

private const val SECONDS_PER_MINUTE = 60.0

/** Below this linear level the table prints `-inf` instead of a dB figure. */
private const val DB_FLOOR = 1e-9

/** An amplitude ratio is 20 dB per decade. */
private const val DB_PER_DECADE = 20

private fun db(x: Double): String = if (x <= DB_FLOOR) "-inf" else "%.1f".format(DB_PER_DECADE * kotlin.math.log10(x))
