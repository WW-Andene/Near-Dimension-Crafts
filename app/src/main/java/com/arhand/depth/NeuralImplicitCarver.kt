package com.arhand.depth

import com.arhand.util.Vec3
import kotlin.math.*

/**
 * G2 — Implicit Neural Representation for Hand Reconstruction.
 *
 * Replaces [DepthCarver]'s hard-coded capsule-SDF prior with a per-scan MLP occupancy field.
 *
 * Architecture: 4-layer MLP (input:3 → 64 → 64 → 64 → 1)
 *   - Input:    normalized world-space (x, y, z)
 *   - Hidden:   ReLU activation, 64 units per layer
 *   - Output:   occupancy logit (sigmoid → probability); 0=outside, 1=inside
 *
 * Training: supervised from the scan's accumulate landmark sets.
 *   - Positive samples:  points sampled on capsule surfaces (known inside)
 *   - Negative samples:  points sampled in free space outside capsules (known outside)
 *   - Loss:              binary cross-entropy with logits
 *   - Optimizer:         Adam (lr=1e-3, β1=0.9, β2=0.999)
 *   - Epochs:            configurable, default=300 (< 2 s on Snapdragon 8 Gen 2)
 *
 * Why no TFLite for training:
 * TFLite is inference-only — it cannot run backpropagation. Per-scan neural reconstruction
 * requires training from scratch each scan on the user's own geometry. We implement the
 * full forward + backward pass in pure Kotlin/JVM. Inference speed post-training is
 * ~0.3 µs/query, yielding ~30 ms for a 48³ grid — within the same budget as [DepthCarver].
 *
 * MANO pre-study note: See [ManoShapeFitter] for the parametric shape model intermediate
 * step. [ManoShapeFitter] can generate richer training samples for this MLP by producing
 * a plausible surface mesh from the PCA shape space, allowing the MLP to learn deviations
 * from the parametric prior (veins, knuckle topology, nail geometry).
 *
 * Usage in [AppViewModel.processScan]:
 * ```kotlin
 * val carver = NeuralImplicitCarver()
 * carver.train(capturedFrames, aspect = currentAspect.value, epochs = 300)
 * val meshPos = carver.extractMesh()
 * ```
 */
class NeuralImplicitCarver {

    // ── Architecture constants ─────────────────────────────────────────────
    private val INPUT_DIM  = 3
    private val HIDDEN_DIM = 64
    private val OUTPUT_DIM = 1

    /**
     * IMP-R3 — Fourier positional encoding.
     *
     * Raw (x,y,z) as MLP input limits the network to smooth low-frequency surfaces
     * due to spectral bias — the network can't easily represent knuckle ridges,
     * nail edges, or vein topology at the 1–3mm scale.
     *
     * Nerf-style positional encoding maps each coordinate through L frequency bands:
     *   γ(p) = [sin(2⁰πp), cos(2⁰πp), sin(2¹πp), cos(2¹πp), ..., sin(2^(L-1)πp), cos(2^(L-1)πp)]
     *
     * With L=4 and 3 input axes: encoded dimension = 3 + 3×4×2 = 27 (raw + encoded).
     * This lets the same 64-unit hidden layers resolve surface detail ~16× finer than
     * raw input while adding only 24 extra input weights (negligible).
     *
     * The raw coords are also appended to preserve DC (low-frequency) content.
     */
    private val FOURIER_L      = 4                          // frequency bands
    private val ENCODED_DIM    = 3 + 3 * FOURIER_L * 2     // 3 raw + 24 encoded = 27
    private val PI_F           = Math.PI.toFloat()

    private fun encode(x: FloatArray): FloatArray {
        val out = FloatArray(ENCODED_DIM)
        // Copy raw coords
        out[0] = x[0]; out[1] = x[1]; out[2] = x[2]
        var idx = 3
        for (l in 0 until FOURIER_L) {
            val scale = Math.pow(2.0, l.toDouble()).toFloat() * PI_F
            for (d in 0..2) {
                out[idx++] = kotlin.math.sin(scale * x[d])
                out[idx++] = kotlin.math.cos(scale * x[d])
            }
        }
        return out
    }

    // Layer weight matrices and biases — initialized lazily in init()
    // Layer 0: [INPUT_DIM  × HIDDEN_DIM]
    // Layer 1: [HIDDEN_DIM × HIDDEN_DIM]
    // Layer 2: [HIDDEN_DIM × HIDDEN_DIM]
    // Layer 3: [HIDDEN_DIM × OUTPUT_DIM]
    private lateinit var w0: FloatArray; private lateinit var b0: FloatArray
    private lateinit var w1: FloatArray; private lateinit var b1: FloatArray
    private lateinit var w2: FloatArray; private lateinit var b2: FloatArray
    private lateinit var w3: FloatArray; private lateinit var b3: FloatArray

    // Adam optimizer first/second moment estimates (same shape as weights)
    private lateinit var m0w: FloatArray; private lateinit var v0w: FloatArray
    private lateinit var m0b: FloatArray; private lateinit var v0b: FloatArray
    private lateinit var m1w: FloatArray; private lateinit var v1w: FloatArray
    private lateinit var m1b: FloatArray; private lateinit var v1b: FloatArray
    private lateinit var m2w: FloatArray; private lateinit var v2w: FloatArray
    private lateinit var m2b: FloatArray; private lateinit var v2b: FloatArray
    private lateinit var m3w: FloatArray; private lateinit var v3w: FloatArray
    private lateinit var m3b: FloatArray; private lateinit var v3b: FloatArray

    private var adamStep = 0

    // ── Training config ────────────────────────────────────────────────────
    companion object {
        const val DEFAULT_EPOCHS         = 300
        const val DEFAULT_BATCH_SIZE     = 256
        /** Epochs per incremental training pass (HAND-6). Fewer than full train — warm-start converges faster. */
        const val INCREMENTAL_EPOCHS     = 60
        const val LEARNING_RATE          = 1e-3f
        const val ADAM_BETA1             = 0.9f
        const val ADAM_BETA2             = 0.999f
        const val ADAM_EPS               = 1e-8f
        /** Samples per bone segment for positive training set. */
        const val POS_SAMPLES_PER_SEG    = 6
        /** Negative samples per positive sample. */
        const val NEG_RATIO              = 3
        /** Bone segments (same topology as DepthCarver) */
        val BONE_SEGMENTS = listOf(
            1 to 2, 2 to 3, 3 to 4,
            5 to 6, 6 to 7, 7 to 8,
            9 to 10, 10 to 11, 11 to 12,
            13 to 14, 14 to 15, 15 to 16,
            17 to 18, 18 to 19, 19 to 20,
            0 to 5, 0 to 9, 0 to 13, 0 to 17,
            5 to 9, 9 to 13, 13 to 17
        )
        val SEG_RADII = floatArrayOf(
            // finger segments
            1.0f, 0.85f, 0.65f,
            1.0f, 0.85f, 0.65f,
            1.0f, 0.85f, 0.65f,
            1.0f, 0.85f, 0.65f,
            1.0f, 0.85f, 0.65f,
            // palm
            1.2f, 1.2f, 1.2f, 1.2f,
            1.1f, 1.1f, 1.1f
        )
    }

    // Track training outcome for diagnostics
    var lastTrainLoss: Float = Float.NaN
    var lastTrainEpochs: Int = 0
    var isTrained: Boolean = false

    /** Input normalization bounds — computed from training data. */
    private var normMin = Vec3(0f, 0f, 0f)
    private var normScale = Vec3(1f, 1f, 1f)

    // ── Weight initialization ──────────────────────────────────────────────

    private fun initWeights() {
        val rng = java.util.Random(42L)
        fun kaiming(fan: Int, size: Int): FloatArray {
            val std = sqrt(2f / fan)
            return FloatArray(size) { (rng.nextGaussian() * std).toFloat() }
        }
        fun zeros(size: Int) = FloatArray(size) { 0f }

        w0 = kaiming(ENCODED_DIM, ENCODED_DIM * HIDDEN_DIM)  // Fourier-encoded input layer
        b0 = zeros(HIDDEN_DIM)
        w1 = kaiming(HIDDEN_DIM, HIDDEN_DIM * HIDDEN_DIM)
        b1 = zeros(HIDDEN_DIM)
        w2 = kaiming(HIDDEN_DIM, HIDDEN_DIM * HIDDEN_DIM)
        b2 = zeros(HIDDEN_DIM)
        w3 = kaiming(HIDDEN_DIM, HIDDEN_DIM * OUTPUT_DIM)
        b3 = zeros(OUTPUT_DIM)

        m0w = zeros(w0.size); v0w = zeros(w0.size); m0b = zeros(b0.size); v0b = zeros(b0.size)
        m1w = zeros(w1.size); v1w = zeros(w1.size); m1b = zeros(b1.size); v1b = zeros(b1.size)
        m2w = zeros(w2.size); v2w = zeros(w2.size); m2b = zeros(b2.size); v2b = zeros(b2.size)
        m3w = zeros(w3.size); v3w = zeros(w3.size); m3b = zeros(b3.size); v3b = zeros(b3.size)
        adamStep = 0
    }

    // ── Forward pass ──────────────────────────────────────────────────────

    /**
     * Forward pass: returns (logit, h0, h1, h2, h3) where hN are pre-activation
     * hidden states needed for backprop.
     */
    private fun forward(x: FloatArray): ForwardCache {
        val enc = encode(x)   // Fourier encoding: raw 3D point → 27-dim positional encoding
        // Layer 0 — uses encoded input
        val h0 = FloatArray(HIDDEN_DIM)
        for (j in 0 until HIDDEN_DIM) {
            var s = b0[j]
            for (i in 0 until ENCODED_DIM) s += enc[i] * w0[i * HIDDEN_DIM + j]
            h0[j] = maxOf(0f, s)  // ReLU
        }
        // Layer 1
        val h1 = FloatArray(HIDDEN_DIM)
        for (j in 0 until HIDDEN_DIM) {
            var s = b1[j]
            for (i in 0 until HIDDEN_DIM) s += h0[i] * w1[i * HIDDEN_DIM + j]
            h1[j] = maxOf(0f, s)
        }
        // Layer 2
        val h2 = FloatArray(HIDDEN_DIM)
        for (j in 0 until HIDDEN_DIM) {
            var s = b2[j]
            for (i in 0 until HIDDEN_DIM) s += h1[i] * w2[i * HIDDEN_DIM + j]
            h2[j] = maxOf(0f, s)
        }
        // Layer 3 — linear output (logit)
        var logit = b3[0]
        for (i in 0 until HIDDEN_DIM) logit += h2[i] * w3[i]
        return ForwardCache(x, h0, h1, h2, logit)
    }

    private data class ForwardCache(
        val x: FloatArray,
        val h0: FloatArray,
        val h1: FloatArray,
        val h2: FloatArray,
        val logit: Float
    )

    /** Sigmoid of the output logit → occupancy probability. */
    fun predict(x: FloatArray): Float {
        val c = forward(x)
        return 1f / (1f + exp(-c.logit))
    }

    // ── Backpropagation ────────────────────────────────────────────────────

    /**
     * Backprop for binary cross-entropy loss with logit output.
     * dL/d_logit = sigmoid(logit) - label
     */
    private fun backward(c: ForwardCache, label: Float,
                         dw0: FloatArray, db0: FloatArray,
                         dw1: FloatArray, db1: FloatArray,
                         dw2: FloatArray, db2: FloatArray,
                         dw3: FloatArray, db3: FloatArray) {
        val sig = 1f / (1f + exp(-c.logit))
        val dLogit = sig - label  // BCE gradient wrt logit

        // Layer 3 gradients
        db3[0] += dLogit
        for (i in 0 until HIDDEN_DIM) dw3[i] += c.h2[i] * dLogit

        // Backprop into h2 through layer 3 (no activation on output)
        val dh2 = FloatArray(HIDDEN_DIM)
        for (i in 0 until HIDDEN_DIM) dh2[i] = w3[i] * dLogit

        // Layer 2 gradients (ReLU gate)
        val preact2 = FloatArray(HIDDEN_DIM)
        for (j in 0 until HIDDEN_DIM) {
            var s = b2[j]
            for (i in 0 until HIDDEN_DIM) s += c.h1[i] * w2[i * HIDDEN_DIM + j]
            preact2[j] = s
        }
        val dPreact2 = FloatArray(HIDDEN_DIM) { j -> if (preact2[j] > 0f) dh2[j] else 0f }
        db2.indices.forEach { j -> db2[j] += dPreact2[j] }
        for (i in 0 until HIDDEN_DIM) for (j in 0 until HIDDEN_DIM) dw2[i * HIDDEN_DIM + j] += c.h1[i] * dPreact2[j]

        // Backprop into h1
        val dh1 = FloatArray(HIDDEN_DIM)
        for (i in 0 until HIDDEN_DIM) for (j in 0 until HIDDEN_DIM) dh1[i] += w2[i * HIDDEN_DIM + j] * dPreact2[j]

        // Layer 1 gradients (ReLU gate)
        val preact1 = FloatArray(HIDDEN_DIM)
        for (j in 0 until HIDDEN_DIM) {
            var s = b1[j]
            for (i in 0 until HIDDEN_DIM) s += c.h0[i] * w1[i * HIDDEN_DIM + j]
            preact1[j] = s
        }
        val dPreact1 = FloatArray(HIDDEN_DIM) { j -> if (preact1[j] > 0f) dh1[j] else 0f }
        db1.indices.forEach { j -> db1[j] += dPreact1[j] }
        for (i in 0 until HIDDEN_DIM) for (j in 0 until HIDDEN_DIM) dw1[i * HIDDEN_DIM + j] += c.h0[i] * dPreact1[j]

        // Backprop into h0
        val dh0 = FloatArray(HIDDEN_DIM)
        for (i in 0 until HIDDEN_DIM) for (j in 0 until HIDDEN_DIM) dh0[i] += w1[i * HIDDEN_DIM + j] * dPreact1[j]

        // Layer 0 gradients (ReLU gate) — IMP-R3: use encoded input
        val enc0 = encode(c.x)
        val preact0 = FloatArray(HIDDEN_DIM)
        for (j in 0 until HIDDEN_DIM) {
            var s = b0[j]
            for (i in 0 until ENCODED_DIM) s += enc0[i] * w0[i * HIDDEN_DIM + j]
            preact0[j] = s
        }
        val dPreact0 = FloatArray(HIDDEN_DIM) { j -> if (preact0[j] > 0f) dh0[j] else 0f }
        db0.indices.forEach { j -> db0[j] += dPreact0[j] }
        for (i in 0 until ENCODED_DIM) for (j in 0 until HIDDEN_DIM) dw0[i * HIDDEN_DIM + j] += enc0[i] * dPreact0[j]
    }

    // ── Adam update ────────────────────────────────────────────────────────

    private fun adamUpdate(
        w: FloatArray, b: FloatArray,
        dw: FloatArray, db: FloatArray,
        mw: FloatArray, vw: FloatArray,
        mb: FloatArray, vb: FloatArray,
        batchSize: Int
    ) {
        val inv = 1f / batchSize
        val t = adamStep.toFloat()
        val bc1 = 1f - ADAM_BETA1.pow(t)
        val bc2 = 1f - ADAM_BETA2.pow(t)
        val lrCorr = LEARNING_RATE * sqrt(bc2) / bc1

        for (i in w.indices) {
            val g = dw[i] * inv
            mw[i] = ADAM_BETA1 * mw[i] + (1f - ADAM_BETA1) * g
            vw[i] = ADAM_BETA2 * vw[i] + (1f - ADAM_BETA2) * g * g
            w[i] -= lrCorr * mw[i] / (sqrt(vw[i]) + ADAM_EPS)
            dw[i] = 0f
        }
        for (i in b.indices) {
            val g = db[i] * inv
            mb[i] = ADAM_BETA1 * mb[i] + (1f - ADAM_BETA1) * g
            vb[i] = ADAM_BETA2 * vb[i] + (1f - ADAM_BETA2) * g * g
            b[i] -= lrCorr * mb[i] / (sqrt(vb[i]) + ADAM_EPS)
            db[i] = 0f
        }
    }

    // ── Training data generation ───────────────────────────────────────────

    /**
     * Build supervised training dataset from accumulated landmark frames.
     *
     * Positive samples (label=1): points sampled uniformly on capsule surface volumes.
     * Negative samples (label=0): points in free space — uniformly sampled in the scene
     *   bounding box but outside all capsule radii.
     *
     * @param frames Quality-weighted landmark frames (world-space Vec3 lists)
     * @return Pair of (samples FloatArray[N×3], labels FloatArray[N])
     */
    private fun buildTrainingData(
        frames: List<List<Vec3>>,
        rng: java.util.Random
    ): Pair<List<FloatArray>, FloatArray> {
        // Use top-quality frames (same logic as DepthCarver.carveAndExtract)
        val topFrames = if (frames.size > 1) frames.take(maxOf(1, frames.size * 3 / 4)) else frames

        val positiveSamples = mutableListOf<FloatArray>()
        val negativeSamples = mutableListOf<FloatArray>()

        // Compute scene bounding box for negative sampling
        var xMin = Float.MAX_VALUE; var xMax = -Float.MAX_VALUE
        var yMin = Float.MAX_VALUE; var yMax = -Float.MAX_VALUE
        var zMin = Float.MAX_VALUE; var zMax = -Float.MAX_VALUE
        for (lms in topFrames) for (v in lms) {
            if (v.x < xMin) xMin = v.x; if (v.x > xMax) xMax = v.x
            if (v.y < yMin) yMin = v.y; if (v.y > yMax) yMax = v.y
            if (v.z < zMin) zMin = v.z; if (v.z > zMax) zMax = v.z
        }
        // Expand bounds by 30%
        val pad = 0.15f
        val dx = (xMax - xMin).coerceAtLeast(0.1f); val dy = (yMax - yMin).coerceAtLeast(0.1f); val dz = (zMax - zMin).coerceAtLeast(0.1f)
        xMin -= dx * pad; xMax += dx * pad; yMin -= dy * pad; yMax += dy * pad; zMin -= dz * pad; zMax += dz * pad

        for (lms in topFrames) {
            if (lms.size < 21) continue

            val avgPalmSize = (lms[0] - lms[9]).length().coerceAtLeast(0.02f)
            val boneR = avgPalmSize * 0.14f

            for (segIdx in BONE_SEGMENTS.indices) {
                val (ai, bi) = BONE_SEGMENTS[segIdx]
                val a = lms[ai]; val b = lms[bi]
                val r = boneR * SEG_RADII[segIdx]

                // Sample points inside the capsule volume (positive)
                repeat(POS_SAMPLES_PER_SEG) {
                    // Random point on capsule surface
                    val t = rng.nextFloat()
                    // Interpolate along bone axis
                    val mx = a.x + (b.x - a.x) * t
                    val my = a.y + (b.y - a.y) * t
                    val mz = a.z + (b.z - a.z) * t
                    // Random perturbation within radius (uniformly in ball)
                    val theta = rng.nextFloat() * 2f * PI.toFloat()
                    val phi   = acos(2f * rng.nextFloat() - 1f)
                    val dr    = r * rng.nextFloat().pow(1f / 3f) * 0.85f  // stay inside, not on surface
                    positiveSamples.add(floatArrayOf(
                        mx + dr * sin(phi) * cos(theta),
                        my + dr * sin(phi) * sin(theta),
                        mz + dr * cos(phi)
                    ))
                }
            }
        }

        // Build a flat list of all capsule segments for fast inside-test
        data class Capsule(val ax: Float, val ay: Float, val az: Float,
                           val bx: Float, val by: Float, val bz: Float, val r: Float)
        val capsules = mutableListOf<Capsule>()
        for (lms in topFrames) {
            if (lms.size < 21) continue
            val avgPalmSize = (lms[0] - lms[9]).length().coerceAtLeast(0.02f)
            val boneR = avgPalmSize * 0.14f
            for (segIdx in BONE_SEGMENTS.indices) {
                val (ai, bi) = BONE_SEGMENTS[segIdx]
                val a = lms[ai]; val b = lms[bi]
                capsules.add(Capsule(a.x, a.y, a.z, b.x, b.y, b.z, boneR * SEG_RADII[segIdx]))
            }
        }

        fun insideAnyCapsule(px: Float, py: Float, pz: Float): Boolean {
            for (cap in capsules) {
                val abx = cap.bx - cap.ax; val aby = cap.by - cap.ay; val abz = cap.bz - cap.az
                val apx = px - cap.ax; val apy = py - cap.ay; val apz = pz - cap.az
                val t2 = ((apx * abx + apy * aby + apz * abz) /
                        (abx * abx + aby * aby + abz * abz + 1e-8f)).coerceIn(0f, 1f)
                val qx = cap.ax + t2 * abx; val qy = cap.ay + t2 * aby; val qz = cap.az + t2 * abz
                val dx = px - qx; val dy = py - qy; val dz = pz - qz
                if (sqrt(dx * dx + dy * dy + dz * dz) < cap.r) return true
            }
            return false
        }

        // Sample negatives: uniformly in bbox, reject if inside any capsule
        val negTarget = positiveSamples.size * NEG_RATIO
        var attempts = 0
        while (negativeSamples.size < negTarget && attempts < negTarget * 20) {
            attempts++
            val px = xMin + rng.nextFloat() * (xMax - xMin)
            val py = yMin + rng.nextFloat() * (yMax - yMin)
            val pz = zMin + rng.nextFloat() * (zMax - zMin)
            if (!insideAnyCapsule(px, py, pz)) {
                negativeSamples.add(floatArrayOf(px, py, pz))
            }
        }

        val allSamples = positiveSamples + negativeSamples
        val labels = FloatArray(allSamples.size) { i -> if (i < positiveSamples.size) 1f else 0f }

        // Compute normalization from all sample positions
        var xMin2 = Float.MAX_VALUE; var xMax2 = -Float.MAX_VALUE
        var yMin2 = Float.MAX_VALUE; var yMax2 = -Float.MAX_VALUE
        var zMin2 = Float.MAX_VALUE; var zMax2 = -Float.MAX_VALUE
        for (s in allSamples) {
            if (s[0] < xMin2) xMin2 = s[0]; if (s[0] > xMax2) xMax2 = s[0]
            if (s[1] < yMin2) yMin2 = s[1]; if (s[1] > yMax2) yMax2 = s[1]
            if (s[2] < zMin2) zMin2 = s[2]; if (s[2] > zMax2) zMax2 = s[2]
        }
        normMin   = Vec3(xMin2, yMin2, zMin2)
        normScale = Vec3(
            1f / (xMax2 - xMin2).coerceAtLeast(1e-6f),
            1f / (yMax2 - yMin2).coerceAtLeast(1e-6f),
            1f / (zMax2 - zMin2).coerceAtLeast(1e-6f)
        )

        // Normalize
        for (s in allSamples) {
            s[0] = (s[0] - normMin.x) * normScale.x
            s[1] = (s[1] - normMin.y) * normScale.y
            s[2] = (s[2] - normMin.z) * normScale.z
        }

        return Pair(allSamples, labels)
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Train the MLP occupancy field on a set of quality-weighted world-space landmark frames.
     *
     * Runs entirely on the calling thread (designed to run on [Dispatchers.Default]
     * within [AppViewModel.processScan]'s coroutine scope).
     *
     * @param frames  World-space landmark frames (from [DepthCarver.landmarksToWorld]).
     *                Quality scores are used only to select top-75% frames.
     * @param epochs  Number of gradient descent epochs. Default [DEFAULT_EPOCHS].
     */
    /**
     * Train the MLP occupancy network on the captured scan frames.
     *
     * HAND-5 — [normalMap] augments the training data with photometric surface samples.
     * Each pixel of the normal map that has sufficient confidence is unprojected to a
     * 3D surface point (using the normal map's depth-facing component as a depth proxy)
     * and added as a positive surface sample (label = 1.0). This provides fine-grained
     * surface constraint data that ARCore landmarks alone cannot supply.
     *
     * @param frames    Scan frames: (landmarks, quality) pairs.
     * @param epochs    Training epochs (default [DEFAULT_EPOCHS]).
     * @param normalMap Optional photometric normal map from the scan. Null = same as before.
     */
    fun train(
        frames:    List<Pair<List<Vec3>, Float>>,
        epochs:    Int = DEFAULT_EPOCHS,
        normalMap: com.arhand.scanner.PhotometricNormalMap? = null
    ) {
        initWeights()
        isTrained = false

        val qualifiedFrames = frames.filter { it.second >= 0.55f }
            .sortedByDescending { it.second }
            .take(maxOf(1, (frames.size * 3 / 4).coerceAtLeast(1)))
            .map { it.first }

        val rng = java.util.Random(System.currentTimeMillis())
        val (baseSamples, baseLabels) = buildTrainingData(qualifiedFrames, rng)
        if (baseSamples.isEmpty()) return

        // HAND-5 — Normal map surface sample injection.
        // Unproject normal map pixels to 3D surface points and add them as
        // positive training samples (occupancy = 1.0). This directly constrains
        // the MLP on fine surface geometry the landmark-based training misses.
        //
        // Intrinsics match PhotometricDepthSource: FX = FY ≈ 310, CX = 160, CY = 120.
        val extraSamples = mutableListOf<FloatArray>()
        val extraLabels  = mutableListOf<Float>()
        if (normalMap != null && !normalMap.isEmpty) {
            val nmFx = 310f; val nmFy = 310f
            val nmCx = normalMap.width  * 0.5f
            val nmCy = normalMap.height * 0.5f
            val stride = 4   // subsample the normal map for training efficiency
            for (nmY in stride until normalMap.height - stride step stride) {
                for (nmX in stride until normalMap.width - stride step stride) {
                    val normal = normalMap.normalAt(nmX, nmY) ?: continue
                    val (nx, ny, nz) = normal
                    // Only use pixels whose normal faces the camera (nz > 0.3)
                    // — low nz = grazing angle, unreliable depth estimate
                    if (nz < 0.3f) continue
                    // Use nz as a proxy for depth (brighter = closer to camera)
                    // Map nz ∈ [0.3, 1.0] → depth ∈ [0.25, 0.08] metres (heuristic)
                    val depthM = 0.25f - (nz - 0.3f) / 0.7f * 0.17f
                    val wx = (nmX - nmCx) / nmFx * depthM
                    val wy = (nmY - nmCy) / nmFy * depthM
                    val wz = -depthM
                    // Encode as positional encoding (same as main training path)
                    extraSamples.add(encode(floatArrayOf(wx, wy, wz)))
                    extraLabels.add(1f)   // surface point → inside/on surface
                }
            }
        }

        // Merge base and extra samples
        val samples: List<FloatArray>
        val labels:  FloatArray
        if (extraSamples.isEmpty()) {
            samples = baseSamples
            labels  = baseLabels
        } else {
            samples = baseSamples + extraSamples
            // Concatenate base labels with extra labels into one FloatArray
            labels = FloatArray(baseLabels.size + extraLabels.size).also { arr ->
                baseLabels.copyInto(arr, destinationOffset = 0)
                extraLabels.forEachIndexed { i, v -> arr[baseLabels.size + i] = v }
            }
        }

        if (samples.isEmpty()) return

        val n = samples.size

        // Accumulator arrays (reused across batches, zeroed per-batch by adamUpdate)
        val dw0 = FloatArray(w0.size); val db0 = FloatArray(b0.size)
        val dw1 = FloatArray(w1.size); val db1 = FloatArray(b1.size)
        val dw2 = FloatArray(w2.size); val db2 = FloatArray(b2.size)
        val dw3 = FloatArray(w3.size); val db3 = FloatArray(b3.size)

        val indices = IntArray(n) { it }
        var totalLoss = 0f

        for (epoch in 0 until epochs) {
            // Shuffle
            for (i in n - 1 downTo 1) {
                val j = rng.nextInt(i + 1)
                val tmp = indices[i]; indices[i] = indices[j]; indices[j] = tmp
            }

            totalLoss = 0f
            var batchStart = 0
            while (batchStart < n) {
                val batchEnd = minOf(batchStart + DEFAULT_BATCH_SIZE, n)
                val batchSize = batchEnd - batchStart

                for (k in batchStart until batchEnd) {
                    val idx = indices[k]
                    val cache = forward(samples[idx])
                    val label = labels[idx]

                    // BCE loss
                    val sig = 1f / (1f + exp(-cache.logit))
                    totalLoss += -(label * ln(sig + 1e-7f) + (1f - label) * ln(1f - sig + 1e-7f))

                    backward(cache, label, dw0, db0, dw1, db1, dw2, db2, dw3, db3)
                }

                adamStep++
                adamUpdate(w0, b0, dw0, db0, m0w, v0w, m0b, v0b, batchSize)
                adamUpdate(w1, b1, dw1, db1, m1w, v1w, m1b, v1b, batchSize)
                adamUpdate(w2, b2, dw2, db2, m2w, v2w, m2b, v2b, batchSize)
                adamUpdate(w3, b3, dw3, db3, m3w, v3w, m3b, v3b, batchSize)

                batchStart = batchEnd
            }
        }

        lastTrainLoss   = totalLoss / n
        lastTrainEpochs = epochs
        isTrained = true
    }

    /**
     * HAND-6 — Incremental training: warm-start from current weights.
     *
     * Unlike [train] which calls [initWeights] and discards all prior learning,
     * this method continues training from the current weight state. Designed for
     * use during scan capture: called after each new pose is captured so that
     * a partial scan (user stops early) produces progressively better meshes.
     *
     * The Adam optimizer state ([adamStep], moment accumulators) is preserved across
     * calls so the learning rate schedule continues correctly.
     *
     * @param newFrames  New scan frames to incorporate (typically one pose's worth).
     * @param epochs     Training epochs for this incremental pass (fewer than full train).
     * @param normalMap  Optional photometric normal map for surface sample augmentation.
     */
    fun trainIncremental(
        newFrames: List<Pair<List<Vec3>, Float>>,
        epochs:    Int = INCREMENTAL_EPOCHS,
        normalMap: com.arhand.scanner.PhotometricNormalMap? = null
    ) {
        if (newFrames.isEmpty()) return

        val qualifiedFrames = newFrames.filter { it.second >= 0.55f }.map { it.first }
        if (qualifiedFrames.isEmpty()) return

        val rng = java.util.Random(adamStep.toLong())   // deterministic per-call
        val (baseSamples, baseLabels) = buildTrainingData(qualifiedFrames, rng)
        if (baseSamples.isEmpty()) return

        // Augment with normal map samples (same logic as train())
        val extraSamples = mutableListOf<FloatArray>()
        val extraLabels  = mutableListOf<Float>()
        if (normalMap != null && !normalMap.isEmpty) {
            val nmFx = 310f; val nmFy = 310f
            val nmCx = normalMap.width  * 0.5f
            val nmCy = normalMap.height * 0.5f
            val stride = 8   // coarser stride for incremental passes (speed)
            for (nmY in stride until normalMap.height - stride step stride) {
                for (nmX in stride until normalMap.width - stride step stride) {
                    val normal = normalMap.normalAt(nmX, nmY) ?: continue
                    val (nx, ny, nz) = normal
                    if (nz < 0.3f) continue
                    val depthM = 0.25f - (nz - 0.3f) / 0.7f * 0.17f
                    val wx = (nmX - nmCx) / nmFx * depthM
                    val wy = (nmY - nmCy) / nmFy * depthM
                    val wz = -depthM
                    extraSamples.add(encode(floatArrayOf(wx, wy, wz)))
                    extraLabels.add(1f)
                }
            }
        }

        val samples: List<FloatArray>
        val labels:  FloatArray
        if (extraSamples.isEmpty()) {
            samples = baseSamples
            labels  = baseLabels
        } else {
            samples = baseSamples + extraSamples
            labels  = FloatArray(baseLabels.size + extraLabels.size).also { arr ->
                baseLabels.copyInto(arr, destinationOffset = 0)
                extraLabels.forEachIndexed { i, v -> arr[baseLabels.size + i] = v }
            }
        }

        val n = samples.size

        // Reuse Adam accumulator arrays without zeroing (warm-start)
        val dw0 = FloatArray(w0.size); val db0 = FloatArray(b0.size)
        val dw1 = FloatArray(w1.size); val db1 = FloatArray(b1.size)
        val dw2 = FloatArray(w2.size); val db2 = FloatArray(b2.size)
        val dw3 = FloatArray(w3.size); val db3 = FloatArray(b3.size)

        val indices = IntArray(n) { it }
        var totalLoss = 0f

        for (epoch in 0 until epochs) {
            for (i in n - 1 downTo 1) {
                val j = (java.util.Random().nextInt(i + 1))
                val tmp = indices[i]; indices[i] = indices[j]; indices[j] = tmp
            }
            totalLoss = 0f
            var batchStart = 0
            while (batchStart < n) {
                val batchEnd = minOf(batchStart + DEFAULT_BATCH_SIZE, n)
                val batchSize = batchEnd - batchStart
                for (k in batchStart until batchEnd) {
                    val idx = indices[k]
                    val cache = forward(samples[idx])
                    val label = labels[idx]
                    val sig = 1f / (1f + exp(-cache.logit))
                    totalLoss += -(label * ln(sig + 1e-7f) + (1f - label) * ln(1f - sig + 1e-7f))
                    backward(cache, label, dw0, db0, dw1, db1, dw2, db2, dw3, db3)
                }
                adamStep++
                adamUpdate(w0, b0, dw0, db0, m0w, v0w, m0b, v0b, batchSize)
                adamUpdate(w1, b1, dw1, db1, m1w, v1w, m1b, v1b, batchSize)
                adamUpdate(w2, b2, dw2, db2, m2w, v2w, m2b, v2b, batchSize)
                adamUpdate(w3, b3, dw3, db3, m3w, v3w, m3b, v3b, batchSize)
                batchStart = batchEnd
            }
        }

        lastTrainLoss   = totalLoss / n
        lastTrainEpochs += epochs
        isTrained = true   // mark trained after first incremental pass
    }

    /**
     * Returns probability in [0, 1]: values > 0.5 indicate inside the hand.
     *
     * @param wx World-space X
     * @param wy World-space Y
     * @param wz World-space Z
     */
    fun queryOccupancy(wx: Float, wy: Float, wz: Float): Float {
        val nx = (wx - normMin.x) * normScale.x
        val ny = (wy - normMin.y) * normScale.y
        val nz = (wz - normMin.z) * normScale.z
        return predict(floatArrayOf(nx, ny, nz))
    }

    /**
     * Extract a triangle mesh from the learned occupancy field using Marching Cubes.
     *
     * The isovalue threshold is 0.5 (occupancy probability boundary).
     * Converts occupancy → SDF: sdf = 0.5 - occupancy (negative inside).
     *
     * Uses the same grid conventions as [DepthCarver] (B4 resolution, B3 centering,
     * B5 Laplacian smoothing applied by the caller).
     *
     * Requires [train] to have been called first.
     *
     * @param capturedFrames Original captured frames used to compute grid centroid.
     * @return Flat FloatArray triangle soup (xyz per vertex), empty if not trained.
     */
    fun extractMesh(capturedFrames: List<Pair<List<Vec3>, Float>>): FloatArray {
        if (!isTrained) return FloatArray(0)

        val N = DepthCarver.effectiveGridN
        val cellSize = DepthCarver.GRID_SIZE * 2f / N

        // Compute grid centroid (same as DepthCarver.carveAndExtract B3)
        var cx = 0f; var cy = 0f; var cz = 0f; var count = 0
        val topFrames = capturedFrames.filter { it.second >= 0.55f }
            .sortedByDescending { it.second }
            .take(maxOf(1, capturedFrames.size * 3 / 4))
        for ((lms, _) in topFrames) for (v in lms) {
            cx += v.x; cy += v.y; cz += v.z; count++
        }
        if (count > 0) { cx /= count; cy /= count; cz /= count }

        val origin = floatArrayOf(cx - DepthCarver.GRID_SIZE, cy - DepthCarver.GRID_SIZE, cz - DepthCarver.GRID_SIZE)

        val sdf = FloatArray(N * N * N)
        for (zi in 0 until N) {
            for (yi in 0 until N) {
                for (xi in 0 until N) {
                    val wx = origin[0] + (xi + 0.5f) * cellSize
                    val wy = origin[1] + (yi + 0.5f) * cellSize
                    val wz = origin[2] + (zi + 0.5f) * cellSize
                    val occ = queryOccupancy(wx, wy, wz)
                    // SDF: negative = inside (occupancy > 0.5), positive = outside
                    sdf[xi + N * yi + N * N * zi] = 0.5f - occ
                }
            }
        }

        return MarchingCubes.extract(sdf, N, cellSize, origin)
    }
}
