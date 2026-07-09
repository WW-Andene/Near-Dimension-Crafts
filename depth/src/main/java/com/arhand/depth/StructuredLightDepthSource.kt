package com.arhand.depth

import android.graphics.Bitmap
import com.arhand.util.PointCloudStore
import kotlin.math.*

/**
 * Structured light depth source — Kotlin port of fused-sl-v7.html.
 *
 * ## What this does
 *
 * Projects two superimposed sinusoidal fringe patterns at frequencies freqA and
 * freqA + [FREQ_OFFSET] onto the scene using the device display or a secondary
 * screen. The reflected pattern is captured by the camera and demodulated to
 * recover per-block depth via phase shift.
 *
 * ## Dual-frequency phase unwrapping
 *
 * A single sinusoidal fringe has 2π phase ambiguity: objects at depth d and
 * d + (λ/2) produce identical phase readings. The dual-frequency approach projects
 * freqA and freqB = freqA + [FREQ_OFFSET] simultaneously. Their beat phase
 * (freqA phase − freqB phase) has a much longer unambiguous range:
 *
 *   Unambiguous range = imageWidth / FREQ_OFFSET blocks
 *
 * With FREQ_OFFSET = 3 and BLOCK = 16px at 640px width: 640/(3×16) = 13 block-widths.
 * The beat phase resolves the 2π ambiguity across the full frame.
 *
 * ## v7 additions (from fused-sl-v7.html)
 *
 * - **Phase velocity prediction**: EMA-smoothed per-block beat phase delta.
 *   Fast-moving blocks (|delta| > [PV_THRESH]) get predictive compensation,
 *   reducing lag on gestures. Source: `SL_PV_ALPHA`, `SL_PV_THRESH`.
 *
 * - **Phase gradient** (∂phase/∂x, ∂phase/∂y): central-difference finite
 *   differences on the beat phase map. Encodes local surface tilt relative to
 *   the fringe projection axis. Used by [SpatialLayer] to apply sub-pixel
 *   tilt correction at landmark positions. Source: `slGradX`, `slGradY`.
 *
 * - **Temporal phase coherence ring**: 5-frame ring buffer on beat phase.
 *   Rolling mean replaces instantaneous phase, suppressing frame-to-frame flicker.
 *   Source: `SL_PHASE_FRAMES`, `slPhaseHistory`.
 *
 * - **Drift detection + rolling recalibration**: monitors freqA mean phase for
 *   slow drift (ambient light change, display warm-up). When drift exceeds
 *   [DRIFT_THRESH], recalibrates the background reference.
 *   Source: `SL_DRIFT_THRESH`, `SL_ROLLING_ALPHA`.
 *
 * ## Android adaptation
 *
 * The HTML version drives a canvas overlay visible on the same display. On Android:
 *
 *   - The fringe pattern is sent to the device's front display via a [SLPatternCallback]
 *     (the UI layer draws the pattern in an overlay View; this source just provides
 *     the pattern parameters each frame).
 *   - The camera captures the reflected pattern from the scene.
 *   - Bitmap frames arrive via [processBitmap] (same flow as BitmapGrayscaleShim).
 *
 * ## Output
 *
 * Per-block depth is pushed into [store] as (blockCenterX, blockCenterY, depth01, confidence).
 * [phaseGradX] and [phaseGradY] are updated each frame for use by [SpatialLayer].
 */
class StructuredLightDepthSource {

    companion object {
        const val BLOCK          = 16        // pixels per block — matches HTML
        const val FREQ_OFFSET    = 3         // freqB = freqA + FREQ_OFFSET; beat period = W/3 blocks
        const val DEFAULT_FREQ_A = 10        // default base frequency (cycles across image width)
        const val CAL_FRAMES     = 20        // calibration warm-up frames
        const val PHASE_FRAMES   = 5         // temporal coherence ring length
        const val DRIFT_THRESH   = 0.18f     // phase mean drift threshold for recalibration
        const val RECAL_GATE     = 0.018f    // modulation below this = background, recal eligible
        const val ROLLING_ALPHA  = 0.008f    // rolling recal EMA weight
        const val MOD_GATE       = 0.022f    // modulation gate: skip degenerate blocks
        const val PV_ALPHA       = 0.35f     // phase velocity EMA weight
        const val PV_THRESH      = 0.08f     // min smoothed |delta| (rad/frame) to apply prediction
        const val GRAD_SCALE     = 0.015f    // phase rad/block → normalised depth correction
        const val SL_ALPHA       = 0.25f     // temporal EMA weight for SL depth output
    }

    val store = PointCloudStore(capacity = 200_000)

    // Image dimensions — set on first processBitmap call
    private var W  = 0;  private var H  = 0
    private var bW = 0;  private var bH = 0;  private var N = 0

    // User-settable frequency (cycles across W pixels)
    var freqA: Int = DEFAULT_FREQ_A

    // Calibration state
    private var calFrames   = 0
    private var refPhaseA   = FloatArray(0)   // per-block reference freqA phase
    private var refPhaseB   = FloatArray(0)   // per-block reference freqB phase
    private var refMean     = 0f
    var isDrifting = false; private set
    val isCalibrated get() = calFrames >= CAL_FRAMES

    // Per-frame scratch — allocated once in init()
    private var cosA = FloatArray(0); private var sinA = FloatArray(0)
    private var cosB = FloatArray(0); private var sinB = FloatArray(0)
    private var wrappedA  = FloatArray(0)     // per-block wrapped freqA phase
    private var wrappedB  = FloatArray(0)     // per-block wrapped freqB phase
    private var modBuf    = FloatArray(0)     // per-block freqA modulation amplitude
    private var unwrappedA = FloatArray(0)
    private var unwrappedB = FloatArray(0)

    // Beat phase state
    private var beatPhase  = FloatArray(0)    // current frame beat (unwrappedA − unwrappedB)
    private var prevPhase  = FloatArray(0)    // last frame beat phase
    private var phaseDelta = FloatArray(0)    // EMA-smoothed per-block phase velocity

    // Temporal coherence ring on beat phase
    private var phaseHistory = FloatArray(0)  // [N × PHASE_FRAMES]
    private var phaseSum     = FloatArray(0)  // rolling sum per block
    private var phaseHead    = 0

    // Phase gradient output (read by SpatialLayer)
    val phaseGradX = FloatArray(0).also { }   // reallocated in init()
    val phaseGradY = FloatArray(0).also { }

    // SL depth output (EMA-smoothed)
    private var slSmooth = FloatArray(0)

    // Pattern phase state — advances each frame so fringes appear to shift
    private var patternPhase = 0f

    // Callback to give the UI layer the current pattern parameters so it can draw the overlay
    var patternCallback: SLPatternCallback? = null

    /** Holds the last computed phase gradient arrays — set after every processed frame. */
    data class SLResult(
        val gradX: FloatArray,    // [N] — ∂beat/∂x in block units
        val gradY: FloatArray,    // [N] — ∂beat/∂y in block units
        val depth: FloatArray,    // [N] — normalised depth 0..1
        val modulation: FloatArray, // [N] — fringe modulation amplitude
        val bW: Int, val bH: Int
    )

    @Volatile private var lastResult: SLResult? = null
    fun getLastResult(): SLResult? = lastResult

    // ── Init ─────────────────────────────────────────────────────────────────

    private fun init(w: Int, h: Int) {
        W = w; H = h
        bW = ceil(w.toFloat() / BLOCK).toInt()
        bH = ceil(h.toFloat() / BLOCK).toInt()
        N  = bW * bH

        cosA = FloatArray(w); sinA = FloatArray(w)
        cosB = FloatArray(w); sinB = FloatArray(w)
        wrappedA   = FloatArray(N); wrappedB  = FloatArray(N)
        modBuf     = FloatArray(N); unwrappedA = FloatArray(N); unwrappedB = FloatArray(N)
        beatPhase  = FloatArray(N); prevPhase = FloatArray(N); phaseDelta = FloatArray(N)
        phaseHistory = FloatArray(N * PHASE_FRAMES)
        phaseSum     = FloatArray(N)
        phaseHead    = 0

        val gx = FloatArray(N); val gy = FloatArray(N)
        lastResult = SLResult(gx, gy, FloatArray(N), FloatArray(N), bW, bH)

        slSmooth   = FloatArray(N)
        refPhaseA  = FloatArray(N); refPhaseB = FloatArray(N)
        calFrames  = 0; refMean = 0f; isDrifting = false
        patternPhase = 0f
    }

    // ── Process one camera frame ──────────────────────────────────────────────

    /**
     * Process a camera frame and update [store] with depth points.
     * Call from any background thread (camera callback / Dispatchers.Default).
     *
     * @param bitmap  Current camera frame (any size; internally scaled to [W]×[H])
     * @param freqOverride  Override the default [freqA] for this frame (pass -1 to use default)
     */
    fun processBitmap(bitmap: Bitmap, freqOverride: Int = -1) {
        val fw = bitmap.width; val fh = bitmap.height
        if (fw == 0 || fh == 0) return

        if (W != fw || H != fh) init(fw, fh)

        val freq = if (freqOverride > 0) freqOverride else freqA
        val freqB = freq + FREQ_OFFSET

        val kA = (2.0 * PI * freq)  / W
        val kB = (2.0 * PI * freqB) / W
        val phB = patternPhase * 1.3

        // Notify UI of current pattern params so it can draw the fringe overlay
        patternCallback?.onPatternParams(freq, freqB, patternPhase.toDouble(), phB)

        // ── Extract blue channel per pixel (blue carries most fringe signal)
        val pixels = IntArray(W * H)
        bitmap.getPixels(pixels, 0, W, 0, 0, W, H)

        // Pre-compute per-column trig tables (eliminates 4 cos/sin calls per pixel)
        for (x in 0 until W) {
            val kax = kA * x + patternPhase
            val kbx = kB * x + phB
            cosA[x] = cos(kax).toFloat(); sinA[x] = sin(kax).toFloat()
            cosB[x] = cos(kbx).toFloat(); sinB[x] = sin(kbx).toFloat()
        }

        // ── Pass 1: dual quadrature demodulation per block
        for (by in 0 until bH) {
            for (bx in 0 until bW) {
                val bi = by * bW + bx
                val x0 = bx * BLOCK; val y0 = by * BLOCK
                val x1 = min(x0 + BLOCK, W); val y1 = min(y0 + BLOCK, H)
                var IcA = 0f; var QcA = 0f; var IcB = 0f; var QcB = 0f; var cnt = 0

                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val px = pixels[y * W + x]
                        val b  = (px and 0xFF) / 255f   // blue channel
                        IcA += b * cosA[x]; QcA += b * sinA[x]
                        IcB += b * cosB[x]; QcB += b * sinB[x]
                        cnt++
                    }
                }
                val inv = 1f / cnt
                IcA *= inv; QcA *= inv; IcB *= inv; QcB *= inv

                wrappedA[bi] = atan2(QcA, IcA)
                wrappedB[bi] = atan2(QcB, IcB)
                modBuf[bi]   = sqrt(IcA * IcA + QcA * QcA)

                // Calibration: EMA of reference phase both channels
                if (calFrames < CAL_FRAMES) {
                    val w = 1f / (calFrames + 1)
                    refPhaseA[bi] += (wrappedA[bi] - refPhaseA[bi]) * w
                    refPhaseB[bi] += (wrappedB[bi] - refPhaseB[bi]) * w
                }
            }
        }

        // ── Pass 2: spatial phase unwrap (left→right per row)
        unwrapRows(wrappedA, unwrappedA)
        unwrapRows(wrappedB, unwrappedB)

        if (calFrames >= CAL_FRAMES) {
            // ── Beat phase = unwrappedA − unwrappedB
            for (i in 0 until N) {
                beatPhase[i] = unwrappedA[i] - unwrappedB[i]
            }

            // ── Phase velocity: EMA per-block delta with predictive compensation
            for (i in 0 until N) {
                val rawDelta = wrapPhase(beatPhase[i] - prevPhase[i])
                phaseDelta[i] = phaseDelta[i] * (1f - PV_ALPHA) + rawDelta * PV_ALPHA
                if (abs(phaseDelta[i]) > PV_THRESH) {
                    beatPhase[i] += phaseDelta[i]
                }
                prevPhase[i] = beatPhase[i]
            }

            // ── Phase gradient: central differences on beat phase
            val result = lastResult!!
            val gradX = result.gradX; val gradY = result.gradY
            for (by in 0 until bH) {
                for (bx in 0 until bW) {
                    val bi = by * bW + bx
                    val l  = if (bx > 0)      beatPhase[by * bW + (bx - 1)] else beatPhase[bi]
                    val r  = if (bx < bW - 1) beatPhase[by * bW + (bx + 1)] else beatPhase[bi]
                    val u  = if (by > 0)      beatPhase[(by - 1) * bW + bx] else beatPhase[bi]
                    val dn = if (by < bH - 1) beatPhase[(by + 1) * bW + bx] else beatPhase[bi]
                    gradX[bi] = (r - l) * 0.5f
                    gradY[bi] = (dn - u) * 0.5f
                }
            }

            // ── Temporal coherence ring on beat phase
            for (i in 0 until N) {
                val base   = i * PHASE_FRAMES
                val oldest = phaseHistory[base + phaseHead]
                phaseSum[i]            += beatPhase[i] - oldest
                phaseHistory[base + phaseHead] = beatPhase[i]
            }
            phaseHead = (phaseHead + 1) % PHASE_FRAMES

            // ── Compute SL depth output
            val depth = result.depth
            val mod   = result.modulation
            store.clear()
            for (i in 0 until N) {
                mod[i] = modBuf[i]
                if (modBuf[i] < MOD_GATE) { slSmooth[i] *= 0.9f; depth[i] = slSmooth[i]; continue }

                val avgBeat = phaseSum[i] / PHASE_FRAMES
                val refBeat = refPhaseA[i] - refPhaseB[i]
                val delta   = avgBeat - refBeat
                val deform  = abs(delta) / (PI.toFloat() * FREQ_OFFSET)
                val raw     = min(1f, deform * modBuf[i] * 8f)
                slSmooth[i] = slSmooth[i] * (1f - SL_ALPHA) + raw * SL_ALPHA
                depth[i]    = slSmooth[i]

                // Push to store as (cx, cy, depth, confidence)
                val cx = ((i % bW + 0.5f) * BLOCK) / W
                val cy = ((i / bW + 0.5f) * BLOCK) / H
                val batch = floatArrayOf(cx, cy, slSmooth[i], modBuf[i])
                store.push(batch, 4)

                // Rolling recal for background blocks
                if (modBuf[i] < RECAL_GATE) {
                    refPhaseA[i] += (wrappedA[i] - refPhaseA[i]) * ROLLING_ALPHA
                    refPhaseB[i] += (wrappedB[i] - refPhaseB[i]) * ROLLING_ALPHA
                }
            }

            // Drift detection on freqA mean
            var curMean = 0f
            for (i in 0 until N) curMean += unwrappedA[i]
            curMean /= N
            isDrifting = abs(curMean - refMean) > DRIFT_THRESH
            if (isDrifting) refMean = curMean
        }

        if (calFrames < CAL_FRAMES) {
            calFrames++
            if (calFrames == CAL_FRAMES) {
                var m = 0f
                for (i in 0 until N) m += refPhaseA[i]
                refMean = m / N
                // Pre-fill phase history with reference beat phase
                for (i in 0 until N) {
                    val refBeat = refPhaseA[i] - refPhaseB[i]
                    val base    = i * PHASE_FRAMES
                    for (f in 0 until PHASE_FRAMES) phaseHistory[base + f] = refBeat
                    phaseSum[i]  = refBeat * PHASE_FRAMES
                    prevPhase[i] = refBeat
                }
            }
        }

        // Advance pattern phase
        patternPhase = ((patternPhase + 0.04f) % (2f * PI.toFloat()))
    }

    /**
     * Reset calibration — call when torch state or lighting conditions change significantly.
     */
    fun resetCalibration() {
        calFrames = 0
        if (N > 0) {
            refPhaseA.fill(0f); refPhaseB.fill(0f)
            phaseHistory.fill(0f); phaseSum.fill(0f)
            prevPhase.fill(0f); phaseDelta.fill(0f)
            slSmooth.fill(0f); phaseHead = 0
        }
        isDrifting = false; refMean = 0f
    }

    /**
     * Apply phase-gradient tilt correction to a depth sample at sub-block position (fx, fy).
     *
     * `fx`, `fy` are fractional block coordinates (0 = block left/top, 1 = right/bottom).
     * The correction mirrors `bilinearDepthWithNormal()` from fused-sl-v7.html:
     *
     *   dz = (gx * (fx - 0.5) + gy * (fy - 0.5)) * GRAD_SCALE
     *
     * This corrects for surface tilt: a landmark slightly to the right of a block whose
     * phase gradient slopes rightward is slightly deeper than the block-centre sample.
     */
    fun gradientCorrectZ(bilinearZ: Float, gx: Float, gy: Float, fx: Float, fy: Float): Float {
        val dz = (gx * (fx - 0.5f) + gy * (fy - 0.5f)) * GRAD_SCALE
        return (bilinearZ + dz).coerceIn(0f, 1f)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun wrapPhase(p: Float): Float {
        var r = p
        while (r >  PI) r -= (2f * PI.toFloat())
        while (r < -PI) r += (2f * PI.toFloat())
        return r
    }

    private fun unwrapRows(wrapped: FloatArray, out: FloatArray) {
        for (by in 0 until bH) {
            var accum = 0f
            for (bx in 0 until bW) {
                val bi = by * bW + bx
                val w  = wrapped[bi]
                if (bx > 0) {
                    val prev = wrapped[by * bW + (bx - 1)] + accum
                    var jump = w - prev
                    if (jump >  PI) jump -= (2f * PI.toFloat())
                    if (jump < -PI) jump += (2f * PI.toFloat())
                    accum += (w + accum - prev - jump)
                }
                out[bi] = w + accum
            }
        }
    }
}

/** Callback so the UI layer can draw the fringe pattern overlay on each frame. */
interface SLPatternCallback {
    /**
     * Called each processed frame with the current pattern parameters.
     * The UI should render: I(x) = (0.09 + 0.09·cos(kA·x + phaseA)) + (0.03 + 0.09·cos(kB·x + phaseB))
     * as a translucent blue overlay on the camera feed.
     */
    fun onPatternParams(freqA: Int, freqB: Int, phaseA: Double, phaseB: Double)
}
