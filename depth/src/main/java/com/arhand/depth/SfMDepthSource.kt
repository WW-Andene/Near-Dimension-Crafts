package com.arhand.depth

import android.content.Context
import com.arhand.camera.GrayscaleCamera
import com.arhand.util.OneEuroFilter3
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mode C — Sliding-Window Structure from Motion.
 *
 * Full Kotlin port of lidar.html's `scanFrame()` engine:
 *   Harris corner detector → Lucas-Kanade optical flow → median background flow
 *   → cumulative camera pose → triangulation against a ring buffer of anchor frames.
 *
 * Constants directly match the HTML source (WINDOW=8, BASELINE_MIN=14 px, FX=265…)
 * since [GrayscaleCamera] delivers frames at the same 320×240 resolution.
 *
 * Per-(anchor,feature) OEF smoothing is applied before emitting each point,
 * using [OneEuroFilter3] with the same mc/beta as lidar.html.
 *
 * No ARCore dependency. Works on every device with a camera.
 */
class SfMDepthSource(private val context: Context) : DepthSource {

    override val mode = DepthSource.Mode.SFM
    override fun isAvailable() = true  // only needs a camera

    // ─── Processing constants (match lidar.html exactly) ─────────────────
    companion object {
        private const val W = GrayscaleCamera.TARGET_W   // 320
        private const val H = GrayscaleCamera.TARGET_H   // 240
        // FX = W / (2 * tan(32.5°)) ≈ 265
        private val FX = (W / (2.0 * Math.tan(Math.toRadians(32.5)))).toFloat()
        private val FY = FX
        private val CX = W / 2f
        private val CY = H / 2f
        const val PX_TO_M = 0.001f   // 1 px flow ≈ 1 mm lateral motion (default; overridden by FusedDepthSource)

        private const val WINDOW_MIN      = 4    // minimum integration window — used during fast camera motion
        private const val WINDOW_MAX      = 12   // maximum integration window — used during slow/static camera
        private const val WINDOW_DEFAULT  = 8    // original fixed value — used at medium speed
        private const val BASELINE_MIN    = 14f
        private const val BASELINE_MAX    = 140f
        private const val MAX_DISP_PX     = 80f
        private const val FEATS_PER_FRAME = 90
        private const val MAX_ANCHOR_FEATS= 260
        private const val CONF_MIN        = 0.15f

        // Smoothed magnitude of background optical flow (px/frame) — drives dynamic window sizing.
        private const val SPEED_EMA_ALPHA = 0.2f
        // Speed thresholds for window size transitions
        private const val SPEED_FAST = 8f   // px/frame → use WINDOW_MIN
        private const val SPEED_SLOW = 2f   // px/frame → use WINDOW_MAX
    }

    private var callback: DepthSourceCallback? = null
    private val camera = GrayscaleCamera(context)

    // Shim support — when attached, frames come from BitmapGrayscaleShim instead of GrayscaleCamera
    private var shim: com.arhand.camera.BitmapGrayscaleShim? = null

    /**
     * Attach a [BitmapGrayscaleShim] so this source consumes Handy's existing
     * [com.arhand.camera.CameraFrameProvider] Bitmap stream instead of opening its
     * own [GrayscaleCamera] session. Must be called before [start].
     */
    fun attachShim(s: com.arhand.camera.BitmapGrayscaleShim) { shim = s }

    // Ring buffer of anchor frames
    private data class AnchorFrame(
        val gray: FloatArray,
        val camX: Float,
        val camY: Float,
        val feats: Array<IntArray>,  // [[x,y],…]
        val id: Int
    )
    private val ring   = ArrayDeque<AnchorFrame>()
    private var ringId = 0

    // Cumulative camera position (px)
    private var camPx = 0f; private var camPy = 0f
    private var bgFlowX = 0f; private var bgFlowY = 0f
    private var prevGray: FloatArray? = null

    // Smoothed camera speed — drives dynamic integration window selection.
    private var smoothedSpeed = 0f

    /** Current ring buffer capacity — updated every frame based on camera speed. */
    var currentWindow: Int = WINDOW_DEFAULT
        private set

    // OEF smoothers keyed by "anchorId:kx:ky"
    private val smoothers = HashMap<Long, OneEuroFilter3>()

    // Reusable batch buffer
    private val batchBuf = FloatArray(MAX_ANCHOR_FEATS * WINDOW_MAX * 4)

    override fun start(callback: DepthSourceCallback) {
        this.callback = callback
        reset()
        val frameHandler = GrayscaleCamera.FrameListener { gray, _, _, tsMs -> processFrame(gray, tsMs) }
        if (shim != null) {
            shim!!.setListener(frameHandler)
        } else {
            camera.start(facingBack = true, listener = frameHandler)
        }
    }

    override fun stop() {
        if (shim != null) shim!!.setListener(null) else camera.stop()
        callback = null
        reset()
    }

    private fun reset() {
        ring.clear(); ringId = 0
        camPx = 0f; camPy = 0f; bgFlowX = 0f; bgFlowY = 0f
        prevGray = null; smoothers.clear()
        smoothedSpeed = 0f; currentWindow = WINDOW_DEFAULT
    }

    // ─── Per-frame pipeline ───────────────────────────────────────────────

    private fun processFrame(gray: FloatArray, nowMs: Long) {
        val cb = callback ?: return

        // Step 1: frame-to-frame background flow → cumulative camera pose
        val prev = prevGray
        if (prev != null) {
            val pts = harris(prev, 100)
            val matches = lk(prev, gray, pts)
            if (matches.size >= 6) {
                val dxs = FloatArray(matches.size) { (matches[it][2] - matches[it][0]).toFloat() }
                val dys = FloatArray(matches.size) { (matches[it][3] - matches[it][1]).toFloat() }
                dxs.sort(); dys.sort()
                val mdx = dxs[dxs.size shr 1]
                val mdy = dys[dys.size shr 1]
                bgFlowX = bgFlowX * 0.7f + mdx * 0.3f
                bgFlowY = bgFlowY * 0.7f + mdy * 0.3f
                camPx += bgFlowX; camPy += bgFlowY

                // Update smoothed camera speed and derive integration window size.
                // Slow camera → large window (more baseline accumulation before triangulation).
                // Fast camera → small window (avoids tracking failure across too many frames).
                val frameSpeed = hypot(bgFlowX, bgFlowY)
                smoothedSpeed = smoothedSpeed * (1f - SPEED_EMA_ALPHA) + frameSpeed * SPEED_EMA_ALPHA

                currentWindow = when {
                    smoothedSpeed >= SPEED_FAST -> WINDOW_MIN
                    smoothedSpeed <= SPEED_SLOW -> WINDOW_MAX
                    else -> {
                        val t = (smoothedSpeed - SPEED_SLOW) / (SPEED_FAST - SPEED_SLOW)
                        (WINDOW_MAX - (WINDOW_MAX - WINDOW_MIN) * t).toInt().coerceIn(WINDOW_MIN, WINDOW_MAX)
                    }
                }

                // Trim ring if window just shrank — evict oldest anchor(s) immediately
                while (ring.size > currentWindow) {
                    val evicted = ring.removeFirst()
                    pruneSmoothers(evicted.id)
                }
            }
        }

        // Step 2: triangulate against each usable anchor
        var bIdx = 0
        var activeFeat = 0

        for (anchor in ring) {
            val bx = camPx - anchor.camX
            val by = camPy - anchor.camY
            val baseline = hypot(bx, by)
            if (baseline < BASELINE_MIN || baseline > BASELINE_MAX || anchor.feats.isEmpty()) continue

            val tracked = lk(anchor.gray, gray, anchor.feats)
            activeFeat += tracked.size

            for (m in tracked) {
                val kx = m[0]; val ky = m[1]; val cx = m[2]; val cy = m[3]
                val fx = cx - kx.toFloat(); val fy = cy - ky.toFloat()
                val dx = fx - bx; val dy = fy - by
                val disp = hypot(dx, dy)
                if (disp < 1f || disp > MAX_DISP_PX) continue

                val useX = abs(bx) >= abs(by)
                val B = if (useX) abs(bx) else abs(by)
                val d = if (useX) abs(dx) else abs(dy)
                if (d < 0.5f) continue

                val depthPx = FX * B / d
                val depthM  = depthPx * PX_TO_M
                if (depthM < 0.15f || depthM > 8f) continue

                val Xm = (kx - CX) / FX * depthM
                val Ym = (ky - CY) / FY * depthM
                val Zm = -depthM

                val conf = min(disp / 15f, 1f) * min(B / 60f, 1f)
                if (conf < CONF_MIN) continue

                val key = smoother3Key(anchor.id, kx, ky)
                val oef = smoothers.getOrPut(key) { OneEuroFilter3(1.0f, 0.006f, 0.7f, 0.003f) }
                val (sX, sY, sZ) = oef.run(Xm, Ym, Zm, nowMs)

                if (bIdx + 3 < batchBuf.size) {
                    batchBuf[bIdx++] = sX; batchBuf[bIdx++] = sY
                    batchBuf[bIdx++] = sZ; batchBuf[bIdx++] = conf
                }
            }
        }

        if (bIdx > 0) {
            cb.onPoints(batchBuf, bIdx)
            cb.onStats(DepthSource.Stats(
                pointsThisFrame = bIdx / 4,
                featuresActive  = activeFeat,
                baselinePx      = ring.firstOrNull()?.let { hypot(camPx - it.camX, camPy - it.camY) } ?: 0f,
                extraLabel = "SfM(w=$currentWindow,spd=${"%.1f".format(smoothedSpeed)}px)"
            ))
        }

        // Step 3: add current frame to ring, evict oldest beyond currentWindow
        val feats = harris(gray, FEATS_PER_FRAME)
        val capped = if (feats.size > MAX_ANCHOR_FEATS) feats.sliceArray(0 until MAX_ANCHOR_FEATS) else feats
        ring.addLast(AnchorFrame(gray.copyOf(), camPx, camPy, capped, ringId++))
        if (ring.size > currentWindow) {
            val evicted = ring.removeFirst()
            pruneSmoothers(evicted.id)
        }

        prevGray = gray.copyOf()
    }

    private fun pruneSmoothers(evictedId: Int) {
        val liveIds = ring.mapTo(HashSet()) { it.id }
        smoothers.keys.removeAll { keyAnchorId(it) !in liveIds }
    }

    // ─── Harris corner detector (direct port of lidar.html harris()) ──────

    private fun harris(g: FloatArray, maxN: Int = 250): Array<IntArray> {
        val R = 3; val k = 0.04f
        val Ix = FloatArray(W * H); val Iy = FloatArray(W * H)
        for (y in 1 until H - 1) for (x in 1 until W - 1) {
            Ix[y * W + x] = (g[y * W + x + 1] - g[y * W + x - 1]) * 0.5f
            Iy[y * W + x] = (g[(y + 1) * W + x] - g[(y - 1) * W + x]) * 0.5f
        }
        val sc = FloatArray(W * H)
        for (y in R until H - R) for (x in R until W - R) {
            var a = 0f; var b = 0f; var c = 0f
            for (dy in -R..R) for (dx in -R..R) {
                val i = (y + dy) * W + (x + dx)
                a += Ix[i] * Ix[i]; b += Iy[i] * Iy[i]; c += Ix[i] * Iy[i]
            }
            sc[y * W + x] = a * b - c * c - k * (a + b) * (a + b)
        }
        val mx = sc.max() ?: 0f
        val thr = 5e-5f * mx
        val pts = ArrayList<IntArray>(maxN * 2)
        for (y in R + 1 until H - R - 1) for (x in R + 1 until W - R - 1) {
            val s = sc[y * W + x]; if (s < thr) continue
            var ok = true
            outer@ for (dy in -2..2) for (dx in -2..2) {
                if ((dy != 0 || dx != 0) && sc[(y + dy) * W + (x + dx)] >= s) { ok = false; break@outer }
            }
            if (ok) pts.add(intArrayOf(x, y, (s * 1e6f).toInt()))
        }
        pts.sortByDescending { it[2] }
        return pts.take(maxN).map { intArrayOf(it[0], it[1]) }.toTypedArray()
    }

    // ─── Lucas-Kanade optical flow (port of lidar.html lk()) ─────────────

    /**
     * Input pts: Array<IntArray> where each is [x, y].
     * Returns Array<IntArray> [kx, ky, nx, ny] for each tracked point.
     */
    private fun lk(gA: FloatArray, gB: FloatArray, pts: Array<IntArray>): Array<IntArray> {
        val res = ArrayList<IntArray>(pts.size)
        for (pt in pts) {
            val px = pt[0]; val py = pt[1]
            var vx = 0f; var vy = 0f
            for (iter in 0 until 12) {
                var Ixx = 0f; var Iyy = 0f; var Ixy = 0f; var Ixt = 0f; var Iyt = 0f
                for (dy in -2..2) for (dx in -2..2) {
                    val ax = px + dx; val ay = py + dy
                    val bx = (px + vx + dx).toInt(); val by = (py + vy + dy).toInt()
                    if (ax < 1 || ax > W - 2 || ay < 1 || ay > H - 2 ||
                        bx < 1 || bx > W - 2 || by < 1 || by > H - 2) continue
                    val ia = ay * W + ax; val ib = by * W + bx
                    val ix = (gA[ia + 1] - gA[ia - 1]) * 0.5f
                    val iy = (gA[(ay + 1) * W + ax] - gA[(ay - 1) * W + ax]) * 0.5f
                    val it2 = gB[ib] - gA[ia]
                    Ixx += ix * ix; Iyy += iy * iy; Ixy += ix * iy
                    Ixt += ix * it2; Iyt += iy * it2
                }
                val det = Ixx * Iyy - Ixy * Ixy
                if (abs(det) < 1e-7f) break
                val dvx = -(Iyy * Ixt - Ixy * Iyt) / det
                val dvy = -(Ixx * Iyt - Ixy * Ixt) / det
                vx += dvx; vy += dvy
                if (vx * vx + vy * vy > 2500f) { vx = 0f; vy = 0f; break }
                if (dvx * dvx + dvy * dvy < 0.001f) break
            }
            val nx = (px + vx).toInt(); val ny = (py + vy).toInt()
            if (nx >= 1 && nx < W - 1 && ny >= 1 && ny < H - 1)
                res.add(intArrayOf(px, py, nx, ny))
        }
        return res.toTypedArray()
    }

    // ─── Key helpers ──────────────────────────────────────────────────────

    /** Pack (anchorId, kx, ky) into a Long key for the smoothers map. */
    private fun smoother3Key(anchorId: Int, kx: Int, ky: Int): Long =
        (anchorId.toLong() shl 20) or (kx.toLong() shl 10) or ky.toLong()

    private fun keyAnchorId(key: Long): Int = (key ushr 20).toInt()
}
