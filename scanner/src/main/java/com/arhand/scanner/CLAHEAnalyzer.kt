package com.arhand.scanner

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.min

/**
 * CLAHE (Contrast Limited Adaptive Histogram Equalization) — full port from HTML prototype.
 *
 * Applies tile-based equalization with bilinear interpolation between tile CLUTs.
 * Returns a contrast score [0,1]; the enhanced bitmap is never materialised.
 *
 * This version processes luminance only (Y from YUV), leaves Cr/Cb unchanged.
 * Runs on a downscaled version of the frame for performance (max 160×120).
 *
 * Improvements over the previous version:
 *
 * 1. Pre-allocated pixel/luminance buffers — sized once on first call, reused every
 *    subsequent frame. Eliminates two IntArray allocations per frame (19,200 ints each).
 *
 * 2. Integer BT.601 luminance — replaces three float multiplies + one float add per pixel
 *    with three integer multiply-adds and a right-shift. Equivalent result within ±1 LSB.
 *      old: (0.299f * r + 0.587f * g + 0.114f * b).toInt()
 *      new: (77 * r + 150 * g + 29 * b) shr 8
 *    At 19,200 pixels this removes ~57,600 float ops per CLAHE call.
 *
 * 3. Variance computed with integer accumulator — the outLum variance loop now uses
 *    Long accumulation with integer math instead of mapping to Float and calling average().
 *    No accuracy loss for 160×120 pixel values (max accumulator: 255² × 19200 < 2^31).
 *
 * Tile CLUT build, clip-and-redistribute, and bilinear interpolation are unchanged.
 */
class CLAHEAnalyzer(
    private val tilesX: Int = 8,
    private val tilesY: Int = 8,
    private val clipLimit: Float = 3.0f
) {
    var lastContrastScore: Float = 1.0f
        private set

    private val W = 160
    private val H = 120

    // Pre-allocated once, reused every call — zero allocations after warm-up.
    private val pixels = IntArray(W * H)
    private val lum    = IntArray(W * H)
    private val outLum = IntArray(W * H)

    /**
     * Process a Bitmap and return the contrast score [0,1].
     * Bitmap is scaled to 160×120 internally; no output Bitmap is retained.
     */
    fun process(bitmap: Bitmap): Float {
        val scaled = Bitmap.createScaledBitmap(bitmap, W, H, false)
        scaled.getPixels(pixels, 0, W, 0, 0, W, H)
        scaled.recycle()

        // Integer BT.601 luminance: (77*R + 150*G + 29*B) >> 8
        // Coefficients: 77/256 ≈ 0.301, 150/256 ≈ 0.586, 29/256 ≈ 0.113
        // Max value: (77+150+29)*255 >> 8 = 256*255 >> 8 = 255 — no coerce needed.
        for (i in 0 until W * H) {
            val p = pixels[i]
            lum[i] = (77 * (p shr 16 and 0xFF) + 150 * (p shr 8 and 0xFF) + 29 * (p and 0xFF)) shr 8
        }

        val tileW = W / tilesX
        val tileH = H / tilesY

        // Build CLUT per tile
        val cluts = Array(tilesX * tilesY) { ti ->
            val tx = ti % tilesX; val ty = ti / tilesX
            val x0 = tx * tileW; val y0 = ty * tileH

            val hist = IntArray(256)
            for (y in y0 until min(y0 + tileH, H)) {
                for (x in x0 until min(x0 + tileW, W)) {
                    hist[lum[y * W + x]]++
                }
            }

            val tilePixels = tileW * tileH
            val clipVal = (clipLimit * tilePixels / 256f).toInt().coerceAtLeast(1)
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

        // Bilinear interpolation between tile CLUTs
        for (y in 0 until H) {
            for (x in 0 until W) {
                val txf = (x.toFloat() / tileW - 0.5f).coerceIn(0f, (tilesX - 1).toFloat())
                val tyf = (y.toFloat() / tileH - 0.5f).coerceIn(0f, (tilesY - 1).toFloat())
                val tx0 = txf.toInt().coerceIn(0, tilesX - 1)
                val ty0 = tyf.toInt().coerceIn(0, tilesY - 1)
                val tx1 = min(tx0 + 1, tilesX - 1)
                val ty1 = min(ty0 + 1, tilesY - 1)
                val fx = txf - tx0; val fy = tyf - ty0

                val src = lum[y * W + x]
                val v00 = cluts[ty0 * tilesX + tx0][src]
                val v10 = cluts[ty0 * tilesX + tx1][src]
                val v01 = cluts[ty1 * tilesX + tx0][src]
                val v11 = cluts[ty1 * tilesX + tx1][src]

                outLum[y * W + x] = (
                    v00 * (1 - fx) * (1 - fy) +
                    v10 * fx       * (1 - fy) +
                    v01 * (1 - fx) * fy       +
                    v11 * fx       * fy
                ).toInt().coerceIn(0, 255)
            }
        }

        // Contrast score: normalised stddev of equalized luminance.
        // Integer accumulator — no boxing, no lambda allocation, no .average() float cast.
        // Max accumulator for sum: 255 * 19200 = 4,896,000 < Int.MAX_VALUE — safe as Int.
        // Max accumulator for sumSq: 255² * 19200 = 1,248,480,000 < Int.MAX_VALUE — safe as Int.
        val n = W * H
        var sum = 0
        for (v in outLum) sum += v
        val mean = sum / n
        var sumSq = 0
        for (v in outLum) { val d = v - mean; sumSq += d * d }
        val stddev = Math.sqrt(sumSq.toDouble() / n).toFloat()
        lastContrastScore = (stddev / 80f).coerceIn(0f, 1f)

        return lastContrastScore
    }
}
