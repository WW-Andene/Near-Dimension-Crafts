package com.arhand.scanner

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.sqrt

/**
 * G5 — Photometric stereo normal estimation from torch flash.
 *
 * Rapidly toggles the torch between frames at ~15 fps to produce alternating
 * lit (torch-on) and unlit (ambient-only) Bitmap pairs. For each pixel pair,
 * the ratio I_lit / (I_unlit + ε) encodes a coarse surface normal in a
 * single-source photometric stereo formulation.
 *
 * The resulting per-pixel normal map (stored as a flat FloatArray of xyz
 * triplets, normalised to unit length) captures high-frequency surface detail
 * — wrinkles, knuckle creases, vein topology — that the capsule-SDF
 * [com.arhand.depth.DepthCarver] cannot represent.
 *
 * ## Torch-interleaving contract
 *
 * The caller drives the frame pipeline. Each incoming [Bitmap] from the camera
 * must be tagged as lit or unlit via [submitFrame]. Internally the class
 * maintains a pending "lit" slot: when the second (unlit) frame of a pair
 * arrives the pair is committed to the accumulation buffer.
 *
 * The torch state *preceding* the frame's capture determines the tag:
 *   - Frame captured while torch was ON  → [submitFrame(bitmap, isLit = true)]
 *   - Frame captured while torch was OFF → [submitFrame(bitmap, isLit = false)]
 *
 * Because Camera2 has a non-deterministic pipeline delay (typically 1–3 frames
 * on Snapdragon 8 Gen 2), [submitFrame] tolerates out-of-order delivery: it
 * buffers the last lit frame and the last unlit frame independently and only
 * commits a pair once *both* slots have been filled since the last commit.
 *
 * ## Output
 *
 * [computeNormalMap] averages all committed pairs and returns a 640×480
 * FloatArray normal map (W × H × 3 floats: nx, ny, nz per pixel).
 * Each triplet is normalised to unit length.
 *
 * [frameCount] exposes how many pairs have been accumulated so the UI can
 * show a progress indicator.
 */
class PhotometricStereoCapture {

    companion object {
        /** Number of lit/unlit frame pairs to accumulate before the normal map is stable. */
        const val TARGET_PAIRS = 15

        /** Small epsilon added to the unlit denominator to prevent division-by-zero. */
        private const val EPSILON = 1e-3f

        /**
         * Minimum lit/unlit ratio that is considered a real surface reflection
         * (ratios below this are treated as shadow/occlusion and clamped to zero).
         */
        private const val RATIO_MIN = 0.05f

        /**
         * Maximum lit/unlit ratio (ratios above this suggest specular highlights
         * and are clamped to prevent single-pixel contamination).
         */
        private const val RATIO_MAX = 8.0f
    }

    // ── State ──────────────────────────────────────────────────────────────

    /** Whether photometric stereo capture is currently active. */
    val isActive: Boolean get() = synchronized(lock) { _isActive }
    private var _isActive: Boolean = false

    /** Number of committed lit/unlit pairs accumulated so far. */
    val frameCount: Int get() = synchronized(lock) { _frameCount }
    private var _frameCount: Int = 0

    // Pending single-frame slots (null = not yet received since last commit)
    private var pendingLit: Bitmap?   = null
    private var pendingUnlit: Bitmap? = null

    // Accumulation buffer: sum of ratio maps across all committed pairs.
    // Indexed [y * width + x], with 3 floats per pixel (R-ratio, G-ratio, B-ratio).
    private var accumRatio: FloatArray? = null
    private var accumWidth  = 0
    private var accumHeight = 0

    // Synchronisation lock — protects all mutable state
    private val lock = Any()

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Start accumulation. Clears any previously accumulated data.
     * The torch is not driven here — the [com.arhand.ui.AppViewModel] drives it
     * on the camera thread before each submitted frame.
     */
    fun start() {
        synchronized(lock) {
            _isActive     = true
            _frameCount   = 0
            pendingLit    = null
            pendingUnlit  = null
            accumRatio    = null
            accumWidth    = 0
            accumHeight   = 0
        }
    }

    /** Stop accumulation without discarding buffered data. */
    fun stop() {
        synchronized(lock) { _isActive = false }
    }

    /** Reset to initial state, discarding all accumulated data. */
    fun reset() {
        synchronized(lock) {
            _isActive    = false
            _frameCount  = 0
            pendingLit   = null
            pendingUnlit = null
            accumRatio   = null
            accumWidth   = 0
            accumHeight  = 0
        }
    }

    /**
     * Submit a camera frame, tagging it as lit (torch ON) or unlit (torch OFF).
     *
     * When both a lit and an unlit frame have been received since the last commit,
     * the pair is committed to the accumulation buffer automatically.
     *
     * Thread-safe; may be called from the camera callback thread.
     */
    fun submitFrame(bitmap: Bitmap, isLit: Boolean) {
        synchronized(lock) {
            if (!_isActive) return
            if (_frameCount >= TARGET_PAIRS) return
            if (isLit) {
                pendingLit = bitmap
            } else {
                pendingUnlit = bitmap
            }
            // Commit only when both slots have a fresh frame
            val lit   = pendingLit
            val unlit = pendingUnlit
            if (lit != null && unlit != null) {
                commitPair(lit, unlit)
                pendingLit   = null
                pendingUnlit = null
                _frameCount++
            }
        }
    }

    /**
     * True once [TARGET_PAIRS] pairs have been accumulated.
     */
    val isComplete: Boolean get() = synchronized(lock) { _frameCount >= TARGET_PAIRS }

    /**
     * Compute and return the averaged normal map from all accumulated pairs.
     *
     * Returns a FloatArray of size [accumWidth] × [accumHeight] × 3.
     * Each triplet (nx, ny, nz) is unit-normalised.
     * Returns an empty array if no pairs have been accumulated.
     *
     * Thread-safe; can be called from any thread.
     */
    fun computeNormalMap(): PhotometricNormalMap {
        synchronized(lock) {
            val ratio  = accumRatio ?: return PhotometricNormalMap(FloatArray(0), 0, 0)
            val w      = accumWidth
            val h      = accumHeight
            val n      = _frameCount.coerceAtLeast(1)
            val pixels = w * h
            val normals = FloatArray(pixels * 3)

            for (i in 0 until pixels) {
                // Average ratio per channel
                val rR = (ratio[i * 3    ] / n).coerceIn(RATIO_MIN, RATIO_MAX)
                val rG = (ratio[i * 3 + 1] / n).coerceIn(RATIO_MIN, RATIO_MAX)
                val rB = (ratio[i * 3 + 2] / n).coerceIn(RATIO_MIN, RATIO_MAX)

                // Convert ratio to normal estimate using single-source photometric stereo:
                //   The torch is a near-axial light source at the device rear.
                //   Brighter pixels (high ratio) face the torch → normal tilted toward camera.
                //   We approximate: N ≈ (rR-1, rG-1, rB-1) in a tangent-space sense,
                //   then use the luminance ratio as the Z (depth-facing) component and
                //   derive XY from the channel imbalance (colour fringing from off-axis torch).
                val intensity = (rR + rG + rB) / 3f        // luminance ratio
                val nx = (rR - rG) * 0.5f                  // lateral colour gradient → X normal
                val ny = (rB - rG) * 0.5f                  // vertical colour gradient → Y normal
                val nz = intensity.coerceAtLeast(EPSILON)   // depth-facing component

                // Normalise to unit length
                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(EPSILON)
                normals[i * 3    ] = nx / len
                normals[i * 3 + 1] = ny / len
                normals[i * 3 + 2] = nz / len
            }
            return PhotometricNormalMap(normals, w, h)
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Accumulate the ratio map from a single lit/unlit pair.
     * Called inside [lock].
     */
    private fun commitPair(lit: Bitmap, unlit: Bitmap) {
        val w = lit.width.coerceAtMost(unlit.width)
        val h = lit.height.coerceAtMost(unlit.height)

        // Lazy-allocate accumulation buffer on first pair
        if (accumRatio == null) {
            accumRatio  = FloatArray(w * h * 3)
            accumWidth  = w
            accumHeight = h
        }

        // Use the already-allocated dimensions for all subsequent pairs.
        // If a later pair arrives at a different resolution (e.g. due to a camera
        // reconfiguration mid-scan), we clamp to the buffer's size to avoid
        // ArrayIndexOutOfBoundsException. This discards edge pixels on larger
        // bitmaps and safely ignores out-of-range rows/cols on smaller ones.
        val rW = accumWidth
        val rH = accumHeight.coerceAtMost(h)  // can't read rows that don't exist in the bitmaps
        val rWclamped = rW.coerceAtMost(w)    // can't read cols that don't exist in the bitmaps

        val ratio = accumRatio!!

        // Read pixels row by row to avoid allocating a full-image IntArray more than once
        val litPixels   = IntArray(rWclamped)
        val unlitPixels = IntArray(rWclamped)

        for (y in 0 until rH) {
            lit.getPixels(litPixels,   0, rWclamped, 0, y, rWclamped, 1)
            unlit.getPixels(unlitPixels, 0, rWclamped, 0, y, rWclamped, 1)

            for (x in 0 until rWclamped) {
                val lp = litPixels[x]
                val up = unlitPixels[x]

                val lR = Color.red(lp)   / 255f
                val lG = Color.green(lp) / 255f
                val lB = Color.blue(lp)  / 255f

                val uR = Color.red(up)   / 255f + EPSILON
                val uG = Color.green(up) / 255f + EPSILON
                val uB = Color.blue(up)  / 255f + EPSILON

                val idx = (y * rW + x) * 3
                ratio[idx    ] += lR / uR
                ratio[idx + 1] += lG / uG
                ratio[idx + 2] += lB / uB
            }
        }
    }
}

/**
 * Result of [PhotometricStereoCapture.computeNormalMap].
 *
 * @param data   Flat FloatArray of nx, ny, nz triplets, row-major.
 * @param width  Image width (pixels).
 * @param height Image height (pixels).
 *
 * Not a data class — Kotlin's generated equals/hashCode for data classes use
 * referential equality for arrays, so two instances with identical pixel data
 * would not be equal. Explicit overrides use [FloatArray.contentEquals] instead.
 */
class PhotometricNormalMap(
    val data:   FloatArray,
    override val width:  Int,
    override val height: Int
) : com.arhand.util.NormalMap {
    override val isEmpty: Boolean get() = data.isEmpty()

    /** Number of pixels in the map (width × height). */
    val pixelCount: Int get() = width * height

    /**
     * Returns the unit normal at pixel (x, y), or null if out of bounds.
     */
    override fun normalAt(x: Int, y: Int): Triple<Float, Float, Float>? {
        if (x < 0 || x >= width || y < 0 || y >= height) return null
        val i = (y * width + x) * 3
        return Triple(data[i], data[i + 1], data[i + 2])
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhotometricNormalMap) return false
        return width == other.width && height == other.height && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }

    override fun toString(): String =
        "PhotometricNormalMap(${width}×${height}, ${data.size / 3} pixels)"
}
