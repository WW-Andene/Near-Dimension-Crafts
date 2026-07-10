package com.arhand.camera

import android.graphics.Bitmap
import kotlin.math.min
import kotlin.math.pow

/**
 * Low-light detection enhancement — ENGINE_ARCHITECTURE.md §17.3.
 *
 * Ported from a browser prototype (`DarkVision.jsx`) the user built to explore near-pitch-black
 * hand/body detection. Nothing in this app did any image-domain enhancement before MediaPipe saw
 * a frame, in any lighting condition — `scanner.CLAHEAnalyzer` computes an equivalent contrast
 * pass but only keeps a scalar quality score, discarding the enhanced image every call. This
 * class actually materialises the enhanced image and is meant to be applied to the bitmap handed
 * to detection/depth, not just used as a metric.
 *
 * ## What's ported from the prototype, and what's deliberately different
 *
 *  - Hot-pixel suppression, multi-scale CLAHE (tile-8 + tile-4 blend), gamma shadow lift, and
 *    unsharp mask are the same techniques and same overall math as the prototype.
 *  - **Gated on actual darkness, with hysteresis** — the prototype ran its full pipeline
 *    unconditionally the moment it was started; only the stack depth and gamma varied with
 *    brightness. Here, [updateDarkState] enters dark mode below [DARK_ENTER_THRESHOLD] and exits
 *    above [DARK_EXIT_THRESHOLD] — the gap between them means normal-room flicker/shadow can't
 *    toggle the pipeline on and off every frame. Callers should skip calling [enhance] entirely
 *    when [isDarkMode] is false: zero behavior change and zero added per-frame cost in normal
 *    lighting, which the prototype (a standalone demo, not embedded in a tracking pipeline) never
 *    had to care about.
 *  - **Luma-only, chroma preserved.** The prototype's final output is fully grayscale (its
 *    `lumaToImageData` sets R=G=B=luma — it never reconstructs colour). [enhance] processes
 *    luminance only and recombines with the frame's own original chrominance, so the output stays
 *    a plausible colour image instead of degrading to grayscale — the same design choice
 *    `CLAHEAnalyzer`'s own doc comment already describes ("processes luminance only... leaves
 *    Cr/Cb unchanged") but, unlike that class, actually carried through to a real output bitmap.
 *  - **No multi-frame stacking.** The prototype's single biggest low-light win is temporal
 *    integration (6-48 frame stacking with motion alignment) — but that trades added latency for
 *    signal, directly working against the frame-lag fixes already shipped for this pipeline
 *    (ENGINE_ARCHITECTURE.md §17.1: this session *removed* exactly this class of added delay from
 *    the live tracking path). Deliberately left out of the real-time path here. If a genuinely
 *    darker capability is wanted later, it belongs in a separate still-capture mode (e.g. a
 *    dedicated "night snapshot" affordance) that can afford the integration time, not the live
 *    per-frame detection feed.
 *
 * Not thread-confined by itself, but not designed for concurrent calls either — callers (this
 * app calls it from [com.arhand.feature.spatial.SpatialFrameProducer]'s single per-frame
 * coroutine) should serialise access, same assumption the rest of that per-frame pipeline makes.
 */
class LowLightEnhancer {

    companion object {
        // Hysteresis band (mean luma 0-255 scale). The ~15-point gap absorbs ordinary
        // brightness noise/flicker at the boundary without needing a separate debounce timer.
        const val DARK_ENTER_THRESHOLD = 45f
        const val DARK_EXIT_THRESHOLD  = 60f

        const val CLAHE_TILES_FINE   = 8
        const val CLAHE_TILES_COARSE = 4
        const val CLAHE_CLIP         = 3.5f
        const val CLAHE_BLEND        = 0.6f   // fine-tile weight; coarse tile gets (1 - this)

        // Less aggressive than the prototype's 0.35 — this feeds a detector, not a human
        // viewer; over-lifting shadows adds visible noise the detector doesn't need lifted
        // as far as a human eye watching a display does.
        const val GAMMA_DARK = 0.45f

        const val USM_STRENGTH   = 0.5f
        const val HOT_PIXEL_DELTA = 30

        // Sample size for the cheap mean-luminance gate. Small and fixed so this costs
        // effectively nothing to call every frame, unlike [enhance] itself.
        private const val SAMPLE_W = 80
        private const val SAMPLE_H = 60

        private val GAUSS5 = intArrayOf(
            1, 4, 7, 4, 1,
            4, 16, 26, 16, 4,
            7, 26, 41, 26, 7,
            4, 16, 26, 16, 4,
            1, 4, 7, 4, 1
        )
        private const val GAUSS5_SUM = 273
    }

    private var isDark = false

    private var w = 0
    private var h = 0
    private var pixels  = IntArray(0)
    private var yPlane  = IntArray(0)
    private var cbPlane = IntArray(0)
    private var crPlane = IntArray(0)
    private val samplePixels = IntArray(SAMPLE_W * SAMPLE_H)

    /**
     * Cheap mean-luminance sample on a small downscaled copy of [bitmap]. Call every frame —
     * this is what decides whether dark-mode (and therefore [enhance]) should engage at all.
     */
    fun meanLuminance(bitmap: Bitmap): Float {
        val scaled = Bitmap.createScaledBitmap(bitmap, SAMPLE_W, SAMPLE_H, false)
        scaled.getPixels(samplePixels, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)
        scaled.recycle()
        var sum = 0L
        for (p in samplePixels) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            sum += (77 * r + 150 * g + 29 * b) shr 8
        }
        return sum / (SAMPLE_W * SAMPLE_H).toFloat()
    }

    /**
     * Update the hysteresis dark-mode state from this frame's [meanLuminance] reading.
     * Call once per frame before checking [isDarkMode] / calling [enhance].
     */
    fun updateDarkState(luma: Float): Boolean {
        isDark = when {
            luma < DARK_ENTER_THRESHOLD -> true
            luma > DARK_EXIT_THRESHOLD  -> false
            else -> isDark   // inside the hysteresis band — hold the current state
        }
        return isDark
    }

    fun isDarkMode(): Boolean = isDark

    /**
     * Apply hot-pixel suppression + multi-scale CLAHE + gamma lift + unsharp mask to [bitmap]'s
     * luminance, preserving its chrominance, and return a new enhanced [Bitmap]. Callers should
     * only call this when [isDarkMode] is true — it always does the full-cost work when called,
     * it does not re-check darkness itself.
     */
    fun enhance(bitmap: Bitmap): Bitmap {
        ensureSized(bitmap.width, bitmap.height)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in 0 until w * h) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            // BT.601 RGB -> YCbCr
            yPlane[i]  = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            cbPlane[i] = (-0.168736f * r - 0.331264f * g + 0.5f * b + 128f).toInt().coerceIn(0, 255)
            crPlane[i] = (0.5f * r - 0.418688f * g - 0.081312f * b + 128f).toInt().coerceIn(0, 255)
        }

        val denoised = suppressHotPixels(yPlane, w, h)
        val equalised = multiScaleClahe(denoised, w, h)
        val lifted = applyGamma(equalised, GAMMA_DARK)
        val sharpened = unsharpMask(lifted, w, h, USM_STRENGTH)

        // Recombine enhanced luma with the *original* chroma — colour is preserved, only
        // brightness/contrast/detail were touched.
        for (i in 0 until w * h) {
            val yv = sharpened[i].toFloat()
            val cb = cbPlane[i] - 128f
            val cr = crPlane[i] - 128f
            val r = (yv + 1.402f * cr).toInt().coerceIn(0, 255)
            val g = (yv - 0.344136f * cb - 0.714136f * cr).toInt().coerceIn(0, 255)
            val b = (yv + 1.772f * cb).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun ensureSized(newW: Int, newH: Int) {
        if (newW == w && newH == h) return
        w = newW; h = newH
        val n = w * h
        pixels  = IntArray(n)
        yPlane  = IntArray(n)
        cbPlane = IntArray(n)
        crPlane = IntArray(n)
    }

    /**
     * 3x3 median pre-filter, only correcting pixels significantly brighter than their local
     * median (isolated hot pixels are a sensor defect, not real detail — real edges have
     * neighbouring support and aren't touched by the outlier-only threshold).
     */
    private fun suppressHotPixels(luma: IntArray, w: Int, h: Int): IntArray {
        val out = luma.copyOf()
        val nbrs = IntArray(9)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val center = luma[i]
                nbrs[0] = luma[i - w - 1]; nbrs[1] = luma[i - w]; nbrs[2] = luma[i - w + 1]
                nbrs[3] = luma[i - 1];     nbrs[4] = center;      nbrs[5] = luma[i + 1]
                nbrs[6] = luma[i + w - 1]; nbrs[7] = luma[i + w]; nbrs[8] = luma[i + w + 1]
                nbrs.sort()
                val median = nbrs[4]
                if (center > median + HOT_PIXEL_DELTA) out[i] = median
            }
        }
        return out
    }

    /** Single-scale tile-based CLAHE — same CLUT-build/clip/redistribute/bilinear-blend shape
     *  as [com.arhand.scanner.CLAHEAnalyzer], generalised to a configurable tile count. */
    private fun runClahe(src: IntArray, w: Int, h: Int, tiles: Int, clipFactor: Float): IntArray {
        val tileW = w / tiles; val tileH = h / tiles
        val cluts = Array(tiles * tiles) { ti ->
            val tx = ti % tiles; val ty = ti / tiles
            val x0 = tx * tileW; val y0 = ty * tileH

            val hist = IntArray(256)
            for (y in y0 until min(y0 + tileH, h)) {
                for (x in x0 until min(x0 + tileW, w)) {
                    hist[src[y * w + x]]++
                }
            }

            val tilePixels = tileW * tileH
            val clipVal = (clipFactor * tilePixels / 256f).toInt().coerceAtLeast(1)
            var excess = 0
            for (v in 0..255) {
                if (hist[v] > clipVal) { excess += hist[v] - clipVal; hist[v] = clipVal }
            }
            val redistribute = excess / 256
            for (v in 0..255) hist[v] += redistribute

            val lut = IntArray(256)
            var cum = 0
            for (v in 0..255) { cum += hist[v]; lut[v] = (cum * 255 / tilePixels).coerceIn(0, 255) }
            lut
        }

        val dst = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val txf = (x.toFloat() / tileW - 0.5f).coerceIn(0f, (tiles - 1).toFloat())
                val tyf = (y.toFloat() / tileH - 0.5f).coerceIn(0f, (tiles - 1).toFloat())
                val tx0 = txf.toInt().coerceIn(0, tiles - 1)
                val ty0 = tyf.toInt().coerceIn(0, tiles - 1)
                val tx1 = min(tx0 + 1, tiles - 1)
                val ty1 = min(ty0 + 1, tiles - 1)
                val fx = txf - tx0; val fy = tyf - ty0

                val srcV = src[y * w + x]
                val v00 = cluts[ty0 * tiles + tx0][srcV]
                val v10 = cluts[ty0 * tiles + tx1][srcV]
                val v01 = cluts[ty1 * tiles + tx0][srcV]
                val v11 = cluts[ty1 * tiles + tx1][srcV]

                dst[y * w + x] = (
                    v00 * (1 - fx) * (1 - fy) +
                    v10 * fx       * (1 - fy) +
                    v01 * (1 - fx) * fy       +
                    v11 * fx       * fy
                ).toInt().coerceIn(0, 255)
            }
        }
        return dst
    }

    /** Blend a fine-tile CLAHE pass (local contrast) with a coarse-tile pass (regional
     *  structure) — the prototype's "multi-scale CLAHE" technique. */
    private fun multiScaleClahe(src: IntArray, w: Int, h: Int): IntArray {
        val fine   = runClahe(src, w, h, CLAHE_TILES_FINE,   CLAHE_CLIP)
        val coarse = runClahe(src, w, h, CLAHE_TILES_COARSE, CLAHE_CLIP * 0.8f)
        val dst = IntArray(w * h)
        for (i in dst.indices) {
            dst[i] = (fine[i] * CLAHE_BLEND + coarse[i] * (1f - CLAHE_BLEND)).toInt().coerceIn(0, 255)
        }
        return dst
    }

    /** Non-linear shadow lift: f(x) = 255 * (x/255)^gamma. gamma < 1 lifts shadows. */
    private fun applyGamma(luma: IntArray, gamma: Float): IntArray {
        val lut = IntArray(256) { i -> (255f * (i / 255f).pow(gamma)).toInt().coerceIn(0, 255) }
        return IntArray(luma.size) { lut[luma[it]] }
    }

    /** 5x5 Gaussian blur subtracted at [strength] — restores edge detail averaged away by
     *  the median/CLAHE passes above. */
    private fun unsharpMask(luma: IntArray, w: Int, h: Int, strength: Float): IntArray {
        val out = luma.copyOf()
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                var acc = 0
                var k = 0
                for (ky in -2..2) {
                    for (kx in -2..2) {
                        acc += luma[(y + ky) * w + (x + kx)] * GAUSS5[k]
                        k++
                    }
                }
                val blur = acc / GAUSS5_SUM
                val v = luma[y * w + x]
                out[y * w + x] = (v + strength * (v - blur)).toInt().coerceIn(0, 255)
            }
        }
        return out
    }
}
