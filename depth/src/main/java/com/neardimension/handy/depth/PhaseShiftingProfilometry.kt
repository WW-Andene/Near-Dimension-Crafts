package com.arhand.depth

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sin

/**
 * PSP — 4-Step Phase-Shifting Profilometry.
 *
 * Uses the device display as a structured-light projector. Four sinusoidal fringe
 * patterns are displayed in sequence (phases 0°, 90°, 180°, 270°); the camera
 * captures the patterns deformed by the surface under measurement. Phase extraction
 * then gives a high-precision height map independent of ambient illumination.
 *
 * Phase extraction (per pixel, 4-step algorithm):
 *   φ = atan2(I₃ − I₁, I₀ − I₂)
 *
 * The wrapped phase φ ∈ [−π, π] is monotonically related to surface height for
 * surfaces within the camera–display working distance (~20–60 cm for a phone).
 *
 * ## Capture sequence
 *
 * 1. Call [startCapture] to begin.
 * 2. The display layer must show [currentPattern] while the camera captures.
 * 3. On each camera frame tick, call [onFrame]; it returns true when all 4 frames
 *    are collected and [phaseBlocks] is ready.
 * 4. [isStale] is false immediately after capture; it becomes true after
 *    [PSP_STALE_FRAMES] frames have passed without a new capture.
 */
class PhaseShiftingProfilometry(
    val frameWidth:  Int = 320,
    val frameHeight: Int = 240
) {

    companion object {
        const val STEPS              = 4         // 4-step algorithm
        const val FREQUENCY          = 8f        // fringe cycles across frame width
        /** Frames after which a PSP result is considered stale (use 0 weight in arbiter). */
        const val PSP_STALE_FRAMES   = 90        // ~3 s at 30fps

        /** Phase → normalised depth scale (maps [−π, π] to [0, 1]). */
        private const val PHASE_TO_DEPTH = 1f / (2f * PI.toFloat())
    }

    /** Current phase step index 0–3 while capturing, -1 when idle. */
    @Volatile var currentStep: Int = -1
        private set

    /** True while a capture sequence is active. */
    val isCapturing: Boolean get() = currentStep in 0 until STEPS

    /**
     * Per-block (8×6) mean normalised phase [0–1] from the most recent capture.
     * Null until at least one capture has completed.
     */
    @Volatile var phaseBlocks: FloatArray? = null
        private set

    /** Full per-pixel phase map [frameWidth × frameHeight], or null. */
    @Volatile var phaseMap: FloatArray? = null
        private set

    /** True after [PSP_STALE_FRAMES] frames have elapsed without a new capture. */
    @Volatile var isStale: Boolean = true
        private set

    /** Frames since last capture completed — used for stale decay. */
    private var framesSinceCapture = Int.MAX_VALUE

    private val frames = arrayOfNulls<Bitmap>(STEPS)

    /** Signal strength of the most recent result (modulation amplitude [0–1]). */
    @Volatile var signalStrength: Float = 0f
        private set

    /** Call every camera frame to advance the stale counter. */
    fun tick() {
        if (!isCapturing) {
            framesSinceCapture++
            if (framesSinceCapture >= PSP_STALE_FRAMES) isStale = true
        }
    }

    /** Begin a new 4-frame capture. Resets stale state. */
    fun startCapture() {
        frames.fill(null)
        currentStep          = 0
        isStale              = false
        framesSinceCapture   = 0
    }

    /** Cancel the in-progress capture without producing a result. */
    fun cancelCapture() { currentStep = -1 }

    /**
     * Submit [bitmap] as the capture for the current phase step.
     *
     * @return true if all 4 frames have been collected and [phaseBlocks] is updated.
     */
    fun onFrame(bitmap: Bitmap): Boolean {
        val step = currentStep
        if (step < 0 || step >= STEPS) return false
        frames[step] = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        currentStep++
        if (currentStep < STEPS) return false
        currentStep = -1
        computePhase()
        return true
    }

    /**
     * Render the fringe pattern for [currentStep] as a [Bitmap] of [displayW]×[displayH].
     * The display layer should show this image on-screen while the camera captures.
     */
    fun currentPattern(displayW: Int, displayH: Int): Bitmap {
        val step       = currentStep.coerceIn(0, STEPS - 1)
        val phaseShift = step * (2f * PI.toFloat() / STEPS)
        val bmp        = Bitmap.createBitmap(displayW, displayH, Bitmap.Config.ARGB_8888)
        val canvas     = Canvas(bmp)
        val paint      = Paint().apply { style = Paint.Style.FILL }

        for (x in 0 until displayW) {
            val phase     = 2f * PI.toFloat() * FREQUENCY * x.toFloat() / displayW + phaseShift
            val intensity = ((sin(phase) * 0.5f + 0.5f) * 255f).toInt().coerceIn(0, 255)
            paint.color   = Color.rgb(intensity, intensity, intensity)
            canvas.drawRect(x.toFloat(), 0f, x + 1f, displayH.toFloat(), paint)
        }
        return bmp
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun computePhase() {
        val f0 = frames[0] ?: return
        val f1 = frames[1] ?: return
        val f2 = frames[2] ?: return
        val f3 = frames[3] ?: return

        val w   = minOf(f0.width, frameWidth)
        val h   = minOf(f0.height, frameHeight)
        val map = FloatArray(w * h)
        var modSum = 0f

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i0 = lumaOf(f0.getPixel(x, y)).toFloat()
                val i1 = lumaOf(f1.getPixel(x, y)).toFloat()
                val i2 = lumaOf(f2.getPixel(x, y)).toFloat()
                val i3 = lumaOf(f3.getPixel(x, y)).toFloat()
                val phi = atan2(i3 - i1, i0 - i2)
                map[y * w + x] = phi
                // Modulation: amplitude of the fundamental — proxy for signal quality
                val mod = kotlin.math.sqrt((i3 - i1) * (i3 - i1) + (i0 - i2) * (i0 - i2))
                modSum += mod
            }
        }

        phaseMap      = map
        signalStrength = (modSum / (w * h * 255f)).coerceIn(0f, 1f)

        val bW = 8; val bH = 6
        val blocks    = FloatArray(bW * bH)
        val blockPixW = w / bW; val blockPixH = h / bH

        for (by in 0 until bH) {
            for (bx in 0 until bW) {
                var sum = 0f; var count = 0
                for (py in 0 until blockPixH) {
                    for (px in 0 until blockPixW) {
                        val ix = bx * blockPixW + px
                        val iy = by * blockPixH + py
                        if (ix < w && iy < h) {
                            // Remap [−π, π] → [0, 1]
                            sum += map[iy * w + ix] * PHASE_TO_DEPTH + 0.5f
                            count++
                        }
                    }
                }
                blocks[by * bW + bx] = if (count > 0) sum / count else 0f
            }
        }

        phaseBlocks        = blocks
        isStale            = false
        framesSinceCapture = 0
    }

    private fun lumaOf(px: Int): Int {
        val r = (px shr 16) and 0xFF
        val g = (px shr 8)  and 0xFF
        val b =  px         and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b).toInt()
    }
}
