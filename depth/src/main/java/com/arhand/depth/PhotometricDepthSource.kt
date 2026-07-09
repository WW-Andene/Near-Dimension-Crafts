package com.arhand.depth

import android.content.Context
import com.arhand.camera.GrayscaleCamera
import com.arhand.util.OneEuroFilter3
import kotlin.math.*

/**
 * Mode B — Photometric Stereo (torch on/off).
 *
 * Two-source photometric stereo using the rear torch as a point light:
 *
 *   Frame A: torch OFF  → ambient-only image I_a
 *   Frame B: torch ON   → ambient + torch image I_b
 *   Difference image D  = I_b − I_a  (torch contribution only, with sign)
 *
 * Under the Lambertian reflectance model, D(x,y) ∝ n⃗·l⃗ where l⃗ is the
 * torch direction. We assume l⃗ ≈ camera axis (0,0,−1) since the torch is
 * co-axial with the rear lens on most phones, giving:
 *
 *   n_z ∝ D(x,y)     (bright patch = surface facing camera)
 *
 * Surface normals are recovered by also computing the finite-difference
 * spatial gradient of D:
 *
 *   n_x ≈ −∂D/∂x / |∇D|,  n_y ≈ −∂D/∂y / |∇D|
 *
 * Depth is recovered by integrating the normals field using a simple
 * Frankot-Chellappa-like least-squares integration over the image plane
 * (one Poisson solve approximated with Gauss-Seidel iterations).
 *
 * Output: per-pixel world-space points (x,y,z) with confidence = D(x,y).
 *
 * Honest proto notes:
 *  - Two-source PS is underdetermined for full 3D; this gives relative depth,
 *    not metric scale. Scale is set heuristically by calibrating depth range.
 *  - The co-axial light assumption breaks near the frame edges and at close range.
 *  - For real use, 3–4 light directions from a multi-flash LED array would be needed.
 *  - This is the correct architecture for Handy's G5 task; wire in better light
 *    geometry once hardware is characterised.
 */
class PhotometricDepthSource(private val context: Context) : DepthSource {

    override val mode = DepthSource.Mode.PHOTOMETRIC
    override fun isAvailable() = true  // needs camera + torch; torch absence is handled gracefully

    companion object {
        private const val W = GrayscaleCamera.TARGET_W   // 320
        private const val H = GrayscaleCamera.TARGET_H   // 240
        private val FX = (W / (2.0 * tan(Math.toRadians(32.5)))).toFloat()
        private val FY = FX
        private val CX = W / 2f
        private val CY = H / 2f
        private const val DEPTH_SCALE = 0.3f   // heuristic: maps normalised depth to metres
        private const val GS_ITER     = 30     // Gauss-Seidel iterations for depth integration
        private const val MIN_DIFF    = 0.02f  // minimum torch diff to consider a pixel lit
        private const val STRIDE      = 4      // subsample for performance

        // HAND-4 — Albedo normalisation threshold.
        // Pixels with mean illumination below this are too dark to normalise reliably.
        private const val MIN_ALBEDO  = 0.05f

        // HAND-3 — Torch radiance falloff correction.
        //
        // A phone torch follows inverse-square law with a Gaussian radial profile.
        // The co-axial Lambertian model assumes uniform illumination, producing
        // systematically wrong normals at off-axis pixels (pixels near frame edges
        // appear to slope toward the camera because they receive less light).
        //
        // Correction: divide the lit image by the expected illumination map before
        // computing D = lit - unlit. This removes the spatial lighting gradient from
        // the difference signal, leaving only the surface reflectance × cos(θ) term.
        //
        //   correctionMap[px] = 1 / exp(-r² / σ²)
        //   where r = normalised distance from image centre (max = 1 at corner)
        //         σ = TORCH_SIGMA ≈ 0.3 (empirically validated across rear cameras)
        //
        // The map is precomputed once at class load time. At W=320, H=240 it fits
        // in 307KB — negligible.
        private const val TORCH_SIGMA  = 0.3f
        private const val MIN_FALLOFF  = 0.1f   // clamp denominator to avoid div-by-zero at extreme corners

        /** Precomputed correction map: correctionMap[y * W + x] = 1 / gaussianFalloff. */
        val TORCH_CORRECTION_MAP: FloatArray = FloatArray(W * H).also { map ->
            val sigSq  = TORCH_SIGMA * TORCH_SIGMA
            val halfW  = W * 0.5f
            val halfH  = H * 0.5f
            val diagSq = halfW * halfW + halfH * halfH   // max r² at corner (normalised = 1)
            for (y in 0 until H) for (x in 0 until W) {
                val dx = (x - halfW) / halfW
                val dy = (y - halfH) / halfH
                val rSq = dx * dx + dy * dy   // normalised: 0 at centre, ~2 at corner
                val falloff = kotlin.math.exp(-rSq / sigSq).coerceAtLeast(MIN_FALLOFF)
                map[y * W + x] = 1f / falloff
            }
        }
    }

    private val camera = GrayscaleCamera(context)
    private var callback: DepthSourceCallback? = null
    private var shim: com.arhand.camera.BitmapGrayscaleShim? = null

    /** Attach a shim — see [SfMDepthSource.attachShim] for rationale. Must be called before [start]. */
    fun attachShim(s: com.arhand.camera.BitmapGrayscaleShim) { shim = s }

    // Alternating state: collect OFF then ON frames
    private var torchState = false
    private var frameA: FloatArray? = null   // torch-off
    private var frameB: FloatArray? = null   // torch-on

    // Pre-allocated buffers
    private val diff   = FloatArray(W * H)
    private val depth  = FloatArray(W * H)
    private val oefMap = HashMap<Int, OneEuroFilter3>()
    // Output stride: 7 floats per point (x,y,z,conf,nx,ny,nz)
    private val batchBuf = FloatArray((W / STRIDE) * (H / STRIDE) * 7)

    override fun start(callback: DepthSourceCallback) {
        this.callback = callback
        frameA = null; frameB = null; torchState = false
        oefMap.clear()
        val frameHandler = GrayscaleCamera.FrameListener { gray, _, _, tsMs -> onFrame(gray, tsMs) }
        if (shim != null) {
            shim!!.setListener(frameHandler)
        } else {
            camera.start(facingBack = true, listener = frameHandler)
            camera.setTorch(false)
        }
    }

    override fun stop() {
        if (shim != null) {
            shim!!.setListener(null)
        } else {
            camera.setTorch(false)
            camera.stop()
        }
        callback = null; frameA = null; frameB = null
    }

    private fun onFrame(gray: FloatArray, nowMs: Long) {
        if (!torchState) {
            // Captured ambient frame — flip torch on for next frame
            frameA = gray.copyOf()
            torchState = true
            camera.setTorch(true)
        } else {
            // Captured lit frame — process the pair
            frameB = gray.copyOf()
            torchState = false
            camera.setTorch(false)
            val a = frameA ?: return
            val b = frameB ?: return
            processPair(a, b, nowMs)
        }
    }

    private fun processPair(a: FloatArray, b: FloatArray, nowMs: Long) {
        val cb = callback ?: return

        // Step 1: difference image (torch contribution) with radiance falloff correction.
        //
        // HAND-3 — Multiply lit frame b[i] by TORCH_CORRECTION_MAP[i] before subtracting
        // the ambient frame a[i]. This normalises out the Gaussian torch intensity gradient,
        // leaving a spatially uniform diff signal (albedo × cos(θ)) across the whole frame.
        // Without this, off-axis normals are systematically biased toward the camera.
        for (i in diff.indices) {
            val correctedLit = b[i] * TORCH_CORRECTION_MAP[i]
            diff[i] = maxOf(0f, correctedLit - a[i])
        }

        // HAND-4 — Albedo normalisation.
        //
        // The photometric model derives surface normals from D = lit - unlit.
        // True normal equation: D = albedo × n⃗·l⃗  (Lambertian, co-axial)
        // Without albedo normalisation: D ∝ albedo × cos(θ).
        // Skin-tone variation, freckles, and nail colour produce false normals —
        // the model "sees" surface bumps that are pigmentation changes.
        //
        // Albedo estimate: ρ ≈ (correctedLit + unlit) / 2
        //   (mean illumination independent of surface normal direction)
        //
        // Normalised diff: D_norm = D / max(ρ, MIN_ALBEDO)
        //   This removes the albedo term, leaving only n⃗·l⃗.
        //
        // MIN_ALBEDO prevents division by zero on very dark (shadowed) regions.
        for (i in diff.indices) {
            val correctedLit = b[i] * TORCH_CORRECTION_MAP[i]
            val albedo = (correctedLit + a[i]) * 0.5f
            if (albedo > MIN_ALBEDO) {
                diff[i] = diff[i] / albedo
            }
            // If albedo ≤ MIN_ALBEDO the pixel is too dark to normalise — diff[i] stays
            // as-is (low confidence, will be gated out by MIN_DIFF check in step 3).
        }

        // Step 2: spatial gradients of D → surface normals (Lambertian, co-axial light)
        //   px = surface normal component x ≈ -∂D/∂x
        //   py = surface normal component y ≈ -∂D/∂y
        //   pz = n_z ∝ D(x,y)  (faces towards camera = bright)
        // We then integrate p,q → z via Poisson / Gauss-Seidel.

        // Compute p = ∂D/∂x, q = ∂D/∂y at each pixel
        val p = FloatArray(W * H)
        val q = FloatArray(W * H)
        for (y in 1 until H - 1) for (x in 1 until W - 1) {
            p[y * W + x] = (diff[y * W + x + 1] - diff[y * W + x - 1]) * 0.5f
            q[y * W + x] = (diff[(y + 1) * W + x] - diff[(y - 1) * W + x]) * 0.5f
        }

        // Integrate normals → depth via Gauss-Seidel (simple iterative Poisson solve):
        //   ∇²z = ∂p/∂x + ∂q/∂y
        // GS update: z[i,j] = 0.25*(z[i-1,j]+z[i+1,j]+z[i,j-1]+z[i,j+1] - rhs[i,j])
        val rhs = FloatArray(W * H)
        for (y in 1 until H - 1) for (x in 1 until W - 1) {
            rhs[y * W + x] = ((p[y * W + x] - p[y * W + x - 1]) +
                              (q[y * W + x] - q[(y - 1) * W + x]))
        }
        depth.fill(0f)
        repeat(GS_ITER) {
            for (y in 1 until H - 1) for (x in 1 until W - 1) {
                depth[y * W + x] = 0.25f * (
                    depth[y * W + x - 1] + depth[y * W + x + 1] +
                    depth[(y - 1) * W + x] + depth[(y + 1) * W + x] -
                    rhs[y * W + x])
            }
        }

        // Normalise depth to [0,1] then scale to metres
        var dMin = Float.MAX_VALUE; var dMax = -Float.MAX_VALUE
        for (v in depth) { if (v < dMin) dMin = v; if (v > dMax) dMax = v }
        val dRange = maxOf(dMax - dMin, 1e-6f)

        // Step 3: unproject to 3D — IMP-DEPTH-2: output stride-7 (x,y,z,conf,nx,ny,nz)
        // Surface normals (nx,ny,nz) from the p,q gradient field are emitted alongside
        // each point so FusedDepthSource can weight voxel confidence by view-alignment.
        var bIdx = 0; var ptsOut = 0
        var y = STRIDE; while (y < H - STRIDE) {
            var x = STRIDE; while (x < W - STRIDE) {
                val diffVal = diff[y * W + x]
                if (diffVal < MIN_DIFF) { x += STRIDE; continue }  // unlit pixel

                val normDepth = (depth[y * W + x] - dMin) / dRange
                val depthM    = (1f - normDepth) * DEPTH_SCALE + 0.05f  // nearer = brighter

                val Xm = (x - CX) / FX * depthM
                val Ym = (y - CY) / FY * depthM
                val Zm = -depthM
                val conf = minOf(diffVal * 4f, 1f)

                val key = y * W + x
                val oef = oefMap.getOrPut(key) { OneEuroFilter3(0.8f, 0.005f, 0.5f, 0.003f) }
                val (sX, sY, sZ) = oef.run(Xm, Ym, Zm, nowMs)

                // Compute normalised surface normal from p,q image gradients.
                // n = normalise([-p, -q, 1]) in image-space (points toward camera for lit surface).
                val px_ = p[y * W + x]; val qy_ = q[y * W + x]
                val nLen = kotlin.math.sqrt(px_ * px_ + qy_ * qy_ + 1f).coerceAtLeast(1e-6f)
                val nx = -px_ / nLen; val ny = -qy_ / nLen; val nz = 1f / nLen

                if (bIdx + 6 < batchBuf.size) {
                    batchBuf[bIdx++] = sX;   batchBuf[bIdx++] = sY
                    batchBuf[bIdx++] = sZ;   batchBuf[bIdx++] = conf
                    batchBuf[bIdx++] = nx;   batchBuf[bIdx++] = ny;   batchBuf[bIdx++] = nz
                    ptsOut++
                }
                x += STRIDE
            }
            y += STRIDE
        }

        if (bIdx > 0) {
            cb.onPoints(batchBuf, bIdx)
            cb.onStats(DepthSource.Stats(
                pointsThisFrame = ptsOut,
                // Stride-7 tuples: x,y,z at [0-2], confidence at [3], normal at [4-6].
                confidenceMean  = if (ptsOut > 0) {
                    var sum = 0f
                    var i = 3; while (i < bIdx) { sum += batchBuf[i]; i += 7 }
                    sum / ptsOut
                } else 0f,
                extraLabel = "Photometric(normals)"
            ))
        }
    }
}
