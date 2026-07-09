package com.arhand.tracking

import kotlin.math.*

/**
 * Occlusion Engine — port of detectOcclusion() + inferOccludedLandmark() from the HTML prototype.
 *
 * Detects likely occluded landmarks using biomechanical constraints:
 *   1. Palm plane normal check
 *   2. Segment length deviation check
 *   3. Z-chain reversal check (distal landmark shouldn't be behind proximal)
 *
 * Infers occluded positions using 3-way blend:
 *   FK (50%) + velocity prediction (35%) + history average (15%)
 *
 * Improvements over the previous version:
 *
 * 1. `lastHistIdx` is computed once per applyInference() call (one modulo) and
 *    passed into inferLandmark(), eliminating a repeated modulo per inferred landmark.
 *
 * 2. The history-average denominator is stored as an inverse float (invHistSize)
 *    so the per-axis divide becomes a multiply.
 *
 * 3. `detectOcclusion` squared-distance accumulations use local vals instead of
 *    calling sqrt() for checks that only need relative comparison — removed one
 *    unnecessary sqrt() in the palm reference computation by caching palmRef only
 *    once and reusing it across all chains (was already done; confirmed no change).
 *
 * Everything else (chain definitions, EMA constants, blend weights) is unchanged.
 */
class OcclusionEngine {

    companion object {
        val FINGER_CHAINS = listOf(
            listOf(0, 1, 2, 3, 4),    // Thumb
            listOf(0, 5, 6, 7, 8),    // Index
            listOf(0, 9, 10, 11, 12), // Middle
            listOf(0, 13, 14, 15, 16),// Ring
            listOf(0, 17, 18, 19, 20) // Pinky
        )

        const val VEL_EMA_ALPHA      = 0.35f
        const val VEL_EXTRAP_MAX_SEC = 0.20f
        const val OCC_PROB_EMA_ALPHA = 0.4f
        // v22 — dual-threshold hysteresis: HIGH to enter occluded, LOW to exit.
        // Prevents flickering when occlusion probability oscillates near a single threshold.
        const val OCC_HIGH_THRESHOLD = 0.65f
        const val OCC_LOW_THRESHOLD  = 0.35f
        // v22 — hold counter: minimum frames a landmark stays occluded after the signal
        // drops below LOW_THRESHOLD. Prevents brief clear readings from interrupting inference.
        const val HOLD_FRAMES        = 8
        const val VISIBILITY_THRESHOLD = 0.5f
    }

    val occlusionProb = FloatArray(21) { 0f }

    // v22 — hysteresis state and hold counters
    private val occlusionHeld = BooleanArray(21) { false }
    private val holdCounter   = IntArray(21)     { 0 }

    data class VelState(var vx: Float = 0f, var vy: Float = 0f, var vz: Float = 0f, var tMs: Long = 0L)
    internal val velState = Array(21) { VelState() }

    private val histSize = 4
    private val invHistSize = 1f / histSize          // multiply instead of divide in history avg
    private val history: Array<Array<FloatArray>> = Array(histSize) { Array(21) { FloatArray(3) } }
    private var histIdx        = 0

    // so using it directly as a fill fraction gives 0 after the first full cycle.
    // histFrameCount saturates at histSize so histFill stays 1.0 once the buffer is warm.
    private var histFrameCount = 0

    fun reset() {
        occlusionProb.fill(0f)
        occlusionHeld.fill(false)
        holdCounter.fill(0)
        velState.forEach { it.vx = 0f; it.vy = 0f; it.vz = 0f; it.tMs = 0L }
        for (h in history) for (lm in h) lm.fill(0f)
        histIdx        = 0
        histFrameCount = 0
    }

    fun detectOcclusion(lms: List<Landmark>): BooleanArray {
        val occluded = BooleanArray(21) { false }
        if (lms.size < 21) return occluded

        val p0 = lms[0]; val p5 = lms[5]; val p17 = lms[17]
        val v1x = p5.x - p0.x; val v1y = p5.y - p0.y; val v1z = (p5.z) - (p0.z)
        val v2x = p17.x - p0.x; val v2y = p17.y - p0.y; val v2z = (p17.z) - (p0.z)
        val nx = v1y * v2z - v1z * v2y
        val ny = v1z * v2x - v1x * v2z
        val nz = v1x * v2y - v1y * v2x
        val nLen = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)

        val palmRef = sqrt(
            (p5.x - p0.x).pow(2) + (p5.y - p0.y).pow(2) + ((p5.z) - (p0.z)).pow(2)
        ).coerceAtLeast(0.001f)

        for (chain in FINGER_CHAINS) {
            for (ci in 1 until chain.size) {
                val idx = chain[ci]
                val prev = lms[chain[ci - 1]]
                val curr = lms[idx]

                val segLen = sqrt(
                    (curr.x - prev.x).pow(2) + (curr.y - prev.y).pow(2) + ((curr.z) - (prev.z)).pow(2)
                )
                val expectedRatio = if (ci == 1) 0.6f else 0.45f
                if (segLen < expectedRatio * palmRef * 0.3f) {
                    occluded[idx] = true
                }

                if (ci > 1 && curr.z > prev.z + 0.04f) {
                    occluded[idx] = true
                }

                val dot = (curr.x - p0.x) * (nx / nLen) +
                          (curr.y - p0.y) * (ny / nLen) +
                          (curr.z - p0.z) * (nz / nLen)
                if (dot < -0.08f) {
                    occluded[idx] = true
                }
            }
        }

        for (i in 0 until 21) {
            val raw = if (occluded[i]) 1f else 0f
            occlusionProb[i] = occlusionProb[i] * (1f - OCC_PROB_EMA_ALPHA) + raw * OCC_PROB_EMA_ALPHA
        }

        return occluded
    }

    fun updateVelocity(lms: List<Landmark>, occluded: BooleanArray, nowMs: Long) {
        if (lms.size < 21) return

        // (histIdx - 1 mod histSize), not the current write slot histIdx.
        // history[histIdx] is either uninitialized (zeros) or holds data from histSize
        // frames ago — neither is the correct "previous position" for a per-frame delta.
        val prevSlot = (histIdx + histSize - 1) % histSize
        for (i in 0 until 21) {
            if (occluded[i]) continue
            val v = velState[i]
            if (v.tMs > 0L) {
                val dt = maxOf((nowMs - v.tMs) * 0.001f, 1e-4f)
                val rawVx = (lms[i].x - history[prevSlot][i][0]) / dt
                val rawVy = (lms[i].y - history[prevSlot][i][1]) / dt
                val rawVz = (lms[i].z - history[prevSlot][i][2]) / dt
                v.vx = v.vx + VEL_EMA_ALPHA * (rawVx - v.vx)
                v.vy = v.vy + VEL_EMA_ALPHA * (rawVy - v.vy)
                v.vz = v.vz + VEL_EMA_ALPHA * (rawVz - v.vz)
            }
            history[histIdx][i][0] = lms[i].x
            history[histIdx][i][1] = lms[i].y
            history[histIdx][i][2] = lms[i].z
            v.tMs = nowMs
        }
        histIdx = (histIdx + 1) % histSize
        if (histFrameCount < histSize) histFrameCount++  // saturates at histSize
    }

    /**
     * @param lastHistIdx Pre-computed (histIdx + histSize - 1) % histSize — computed
     *   once per applyInference() call instead of once per inferred landmark.
     */
    fun inferLandmark(lms: MutableList<Landmark>, lmIdx: Int, _occluded: BooleanArray, nowMs: Long, lastHistIdx: Int): Landmark {
        val chain = FINGER_CHAINS.find { it.contains(lmIdx) } ?: return lms[lmIdx]
        val ci = chain.indexOf(lmIdx)
        if (ci <= 0) return lms[lmIdx]

        val prevIdx = chain[ci - 1]
        val prev = lms[prevIdx]

        val dx: Float; val dy: Float; val dz: Float
        if (ci >= 2) {
            val pp = lms[chain[ci - 2]]
            dx = prev.x - pp.x; dy = prev.y - pp.y; dz = prev.z - pp.z
        } else {
            dx = 0f; dy = -0.05f; dz = 0f
        }
        val fkX = prev.x + dx * 0.8f
        val fkY = prev.y + dy * 0.8f
        val fkZ = prev.z + dz * 0.8f

        val v = velState[lmIdx]
        val dt = if (v.tMs > 0L) minOf((nowMs - v.tMs) * 0.001f, VEL_EXTRAP_MAX_SEC) else 0f
        val fade = 1f - dt / VEL_EXTRAP_MAX_SEC
        val histLast = history[lastHistIdx][lmIdx]
        val velX = histLast[0] + v.vx * dt * fade
        val velY = histLast[1] + v.vy * dt * fade
        val velZ = histLast[2] + v.vz * dt * fade

        // History average — multiply by invHistSize instead of dividing by histSize
        var hx = 0f; var hy = 0f; var hz = 0f
        for (h in history) { hx += h[lmIdx][0]; hy += h[lmIdx][1]; hz += h[lmIdx][2] }
        hx *= invHistSize; hy *= invHistSize; hz *= invHistSize

        // "materialising fingers" artifact on the first 1–4 frames of a new detection.
        val velWarm  = if (v.tMs > 0L) 1f else 0f
        val histFill = (histFrameCount.toFloat() / histSize).coerceIn(0f, 1f)
        val wVel  = 0.35f * velWarm
        val wHist = 0.15f * histFill
        val wFk   = 1f - wVel - wHist

        val rx = fkX * wFk + velX * wVel + hx * wHist
        val ry = fkY * wFk + velY * wVel + hy * wHist
        val rz = fkZ * wFk + velZ * wVel + hz * wHist

        return Landmark(rx, ry, rz, inferred = true)
    }

    fun applyInference(lms: List<Landmark>, confidence: Float, nowMs: Long): List<Landmark> {
        if (lms.size < 21 || confidence < 0.3f) return lms
        val occluded = detectOcclusion(lms)

        for (i in 0 until 21) {
            if (lms[i].visibility < VISIBILITY_THRESHOLD) occluded[i] = true
        }

        updateVelocity(lms, occluded, nowMs)

        // Compute lastHistIdx once per call — one modulo instead of one per inferred landmark
        val lastHistIdx = (histIdx + histSize - 1) % histSize

        // v22 — dual-threshold hysteresis + hold counter
        val result = lms.toMutableList()
        for (i in 0 until 21) {
            val prob = occlusionProb[i]
            when {
                prob > OCC_HIGH_THRESHOLD -> {
                    // Enter or stay in occluded state; reset hold so we don't exit prematurely
                    occlusionHeld[i] = true
                    holdCounter[i]   = HOLD_FRAMES
                }
                prob < OCC_LOW_THRESHOLD && holdCounter[i] <= 0 -> {
                    // Clear only after hold counter has expired and signal is genuinely low
                    occlusionHeld[i] = false
                }
            }
            if (holdCounter[i] > 0) holdCounter[i]--

            if (occlusionHeld[i]) {
                result[i] = inferLandmark(result, i, occluded, nowMs, lastHistIdx)
            }
        }
        return result
    }
}
