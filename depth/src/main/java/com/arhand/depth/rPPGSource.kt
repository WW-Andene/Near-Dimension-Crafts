package com.arhand.depth

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * rPPG — Remote PhotoPlethysmoGraphy.
 *
 * Estimates blood-volume pulse (BVP) and heart rate from the skin-pixel green
 * channel variation captured by the camera. Blood oxygenation causes small
 * periodic intensity changes in the green channel (~0.5–3% peak-to-peak) at
 * the cardiac rate (typically 0.75–3 Hz, 45–180 BPM).
 *
 * ## Algorithm
 *
 * 1. Each frame: sample the green channel mean from a central face ROI.
 * 2. Accumulate in a circular buffer of [WINDOW] samples.
 * 3. Once warm: detrend (subtract linear fit), apply a Hann window, then
 *    compute a DFT over [BPM_LO..BPM_HI] to find the dominant frequency.
 * 4. Output [bpm] (dominant frequency in BPM) and [amplitude] (AC/DC ratio).
 *
 * ## SNS proxy
 *
 * High-frequency variability in [amplitude] correlates with sympathetic nervous
 * system (SNS) arousal. A simple measure is the coefficient of variation of
 * [amplitude] over the last few seconds.
 *
 * ## Integration
 *
 * Call [process] once per camera frame (face crop or full frame). The face ROI
 * defaults to the central 20% × 20% of the bitmap. For best results, pass a
 * cropped forehead or cheek region when face landmarks are available.
 */
class rPPGSource {

    companion object {
        const val WINDOW      = 128   // sample buffer length (~4 s at 30fps)
        const val BPM_LO      = 45    // lowest detectable BPM
        const val BPM_HI      = 180   // highest detectable BPM
        // ROI: central region of the bitmap (fraction of W/H)
        private const val ROI_CX = 0.5f; private const val ROI_CY = 0.35f
        private const val ROI_W  = 0.20f; private const val ROI_H  = 0.20f
        private const val SAMPLE_STEP = 4   // pixel stride within ROI
    }

    /** Estimated heart rate in BPM. 0 until at least [WINDOW] frames are collected. */
    @Volatile var bpm: Int = 0
        private set

    /** AC amplitude of the dominant cardiac frequency as a fraction of mean DC [0–1]. */
    @Volatile var amplitude: Float = 0f
        private set

    /** SNS proxy: rolling standard deviation of [amplitude] over the last 32 frames. */
    @Volatile var snsProxy: Float = 0f
        private set

    /** True once the buffer has accumulated [WINDOW] frames. */
    val isWarm: Boolean get() = sampleCount >= WINDOW

    private val greenBuf    = FloatArray(WINDOW) { 0f }
    private val ampHistory  = FloatArray(32)     { 0f }
    private var bufHead     = 0
    private var sampleCount = 0
    private var ampHead     = 0

    private var frameRate   = 30f

    /**
     * ENGINE_ARCHITECTURE.md §5.1 — wall-clock time [process] last ran. `bpm`/`amplitude` are
     * written here at raw-frame rate but read by `SpatialFrameProducer.assembleFrame()` at the
     * throttled hand-inference rate; exposed so a `SpatialFrame` consumer can see the actual
     * age of these values instead of assuming they're synchronised to the bundled hand landmarks.
     */
    @Volatile var lastFrameMs = 0L
        private set

    // S1.4 — Differential rPPG: previous absolute green mean for frame-to-frame difference
    private var prevGreenMean: Float = Float.NaN

    /**
     * Process [bitmap], extracting the skin-pixel green mean from the face ROI.
     *
     * @param bitmap      Current camera frame
     * @param roiLeft     Optional ROI left edge (0–1 fraction of width)
     * @param roiTop      Optional ROI top edge (0–1 fraction of height)
     * @param roiRight    Optional ROI right edge
     * @param roiBottom   Optional ROI bottom edge
     */
    fun process(
        bitmap:    Bitmap,
        roiLeft:   Float = ROI_CX - ROI_W / 2,
        roiTop:    Float = ROI_CY - ROI_H / 2,
        roiRight:  Float = ROI_CX + ROI_W / 2,
        roiBottom: Float = ROI_CY + ROI_H / 2
    ) {
        val nowMs = System.currentTimeMillis()
        if (lastFrameMs > 0L) {
            val dtMs = (nowMs - lastFrameMs).coerceIn(10L, 200L)
            frameRate = 0.9f * frameRate + 0.1f * (1000f / dtMs)
        }
        lastFrameMs = nowMs

        val greenMean = sampleGreen(bitmap, roiLeft, roiTop, roiRight, roiBottom)
        // S1.4: Differential rPPG — store frame-to-frame green difference rather than absolute
        // value, making the signal immune to global illumination steps (clouds, shade transitions)
        val signal = if (prevGreenMean.isNaN()) 0f else greenMean - prevGreenMean
        prevGreenMean = greenMean
        greenBuf[bufHead] = signal
        bufHead = (bufHead + 1) % WINDOW
        sampleCount++

        if (sampleCount < WINDOW) return

        analyseBuffer()
    }

    /** Reset all accumulated state. */
    fun reset() {
        greenBuf.fill(0f); ampHistory.fill(0f)
        bufHead = 0; sampleCount = 0; ampHead = 0
        bpm = 0; amplitude = 0f; snsProxy = 0f; lastFrameMs = 0L
        prevGreenMean = Float.NaN
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun sampleGreen(bmp: Bitmap, l: Float, t: Float, r: Float, b: Float): Float {
        val w = bmp.width; val h = bmp.height
        val x0 = (l * w).toInt().coerceIn(0, w - 1)
        val y0 = (t * h).toInt().coerceIn(0, h - 1)
        val x1 = (r * w).toInt().coerceIn(0, w - 1)
        val y1 = (b * h).toInt().coerceIn(0, h - 1)

        var sum = 0L; var count = 0
        for (y in y0..y1 step SAMPLE_STEP) {
            for (x in x0..x1 step SAMPLE_STEP) {
                val px = bmp.getPixel(x, y)
                sum += (px shr 8) and 0xFF
                count++
            }
        }
        return if (count > 0) sum.toFloat() / count else 128f
    }

    private fun analyseBuffer() {
        // Linearise the circular buffer into a contiguous array
        val n   = WINDOW
        val sig = FloatArray(n)
        for (i in 0 until n) sig[i] = greenBuf[(bufHead + i) % n]

        // Linear detrend: subtract least-squares line
        detrend(sig)

        // Hann window
        for (i in 0 until n) sig[i] *= (0.5f - 0.5f * cos(2f * PI.toFloat() * i / (n - 1)))

        val dc = sig.map { abs(it) }.average().toFloat().coerceAtLeast(1e-6f)

        // DFT over the cardiac band [BPM_LO, BPM_HI]
        val fps = frameRate
        val freqLo  = BPM_LO  / 60f   // Hz
        val freqHi  = BPM_HI  / 60f

        var bestPower = 0f; var bestFreq = freqLo
        val kLo = (freqLo * n / fps).toInt().coerceAtLeast(1)
        val kHi = (freqHi * n / fps).toInt().coerceAtMost(n / 2)

        for (k in kLo..kHi) {
            val freq = k * fps / n
            var re = 0f; var im = 0f
            for (i in 0 until n) {
                val angle = 2f * PI.toFloat() * k * i / n
                re += sig[i] * cos(angle); im += sig[i] * sin(angle)
            }
            val power = re * re + im * im
            if (power > bestPower) { bestPower = power; bestFreq = freq }
        }

        val newAmp  = sqrt(bestPower) / n / dc
        amplitude   = newAmp.coerceIn(0f, 1f)
        bpm         = (bestFreq * 60f).toInt().coerceIn(BPM_LO, BPM_HI)

        // SNS proxy — rolling std-dev of amplitude
        ampHistory[ampHead] = amplitude
        ampHead = (ampHead + 1) % ampHistory.size
        val mean = ampHistory.average().toFloat()
        var variance = 0f
        ampHistory.forEach { val d = it - mean; variance += d * d }
        snsProxy = sqrt(variance / ampHistory.size)
    }

    private fun detrend(sig: FloatArray) {
        val n   = sig.size.toFloat()
        var sx  = 0f; var sy = 0f; var sxy = 0f; var sx2 = 0f
        for (i in sig.indices) { val x = i.toFloat(); sx += x; sy += sig[i]; sxy += x * sig[i]; sx2 += x * x }
        val denom = n * sx2 - sx * sx
        if (denom == 0f) return
        val slope = (n * sxy - sx * sy) / denom
        val inter = (sy - slope * sx) / n
        for (i in sig.indices) sig[i] -= slope * i + inter
    }
}
