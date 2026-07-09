package com.arhand.scanner

import com.arhand.tracking.HandLandmarks
import com.arhand.tracking.LM
import com.arhand.tracking.lmDist
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Quality gating engine — port of SCANNER._qualityGate() from the HTML prototype.
 *
 * Computes a composite quality score [0,1] from:
 *   1. Spread score — how far apart the fingers are (normalized)
 *   2. Depth variance — how much Z variation exists (proxy for 3D pose info)
 *   3. Position score — hand is centered and not at extremes
 *   4. G6 — Temporal consistency score — current frame vs OEF-smoothed history
 *
 * Also evaluates motion completions (fingerCurl, wristRoll, fingerWave, thumbWave).
 *
 * Improvement over the previous version:
 * The depth-variance computation previously used:
 *   zVals.map { (it - zMean)² }.average()
 * which allocates a List<Float> for the squared deltas and a List<Double> for average().
 * Both are replaced by a single accumulator loop — no allocations, same result.
 *
 * The centroid (cx, cy) and zMean computations similarly used .map{}.average() — all
 * replaced with direct accumulator loops.
 */
object QualityEngine {

    const val QUALITY_THRESHOLD = 0.55f

    data class QualityResult(
        val score: Float,
        val spreadScore: Float,
        val depthVariance: Float,
        val positionScore: Float,
        val temporalConsistency: Float,
        val passed: Boolean
    )

    fun temporalConsistencyScore(lms: HandLandmarks, prevLms: HandLandmarks?): Float {
        if (prevLms == null || lms.size != prevLms.size || lms.size < 21) return 1f
        val palmSize = lmDist(lms[LM.WRIST], lms[LM.MIDDLE_MCP]).coerceAtLeast(0.001f)
        var totalDisplacement = 0f
        for (i in lms.indices) {
            val dx = lms[i].x - prevLms[i].x
            val dy = lms[i].y - prevLms[i].y
            val dz = lms[i].z - prevLms[i].z
            totalDisplacement += sqrt(dx*dx + dy*dy + dz*dz)
        }
        val meanDisplacement = totalDisplacement / lms.size
        return (1f - (meanDisplacement / (palmSize * 0.25f))).coerceIn(0f, 1f)
    }

    fun evaluate(lms: HandLandmarks, claheContrast: Float = 1f, prevLms: HandLandmarks? = null): QualityResult {
        if (lms.size < 21) return QualityResult(0f, 0f, 0f, 0f, 1f, false)

        // 1. Spread score
        val palmSize = lmDist(lms[LM.WRIST], lms[LM.MIDDLE_MCP]).coerceAtLeast(0.001f)
        val tips = listOf(LM.INDEX_TIP, LM.MIDDLE_TIP, LM.RING_TIP, LM.PINKY_TIP)
        var spreadSum = 0f
        var spreadCount = 0
        for (i in tips.indices) {
            for (j in i + 1 until tips.size) {
                spreadSum += lmDist(lms[tips[i]], lms[tips[j]])
                spreadCount++
            }
        }
        val avgSpread = if (spreadCount > 0) spreadSum / spreadCount else 0f
        val spreadScore = (avgSpread / (palmSize * 1.8f)).coerceIn(0f, 1f)

        // 2. Depth variance — accumulator loop, no List allocation
        val n = lms.size
        var zSum = 0f
        for (lm in lms) zSum += lm.z
        val zMean = zSum / n
        var zVarAcc = 0f
        for (lm in lms) { val d = lm.z - zMean; zVarAcc += d * d }
        val rawDepthVariance = (zVarAcc / n * 200f).coerceIn(0f, 1f)

        // Weight depth variance by CLAHE contrast before compositing.
        // High contrast → strong texture → reliable landmark Z → trust the variance score.
        // Low contrast → flat lighting → noisy Z → discount it so spread/position dominate.
        // claheContrast is already in [0,1] from CLAHEAnalyzer (1.0 = ideal, 0.0 = flat grey).
        val contrastWeight = claheContrast.coerceIn(0f, 1f)
        val depthVariance  = rawDepthVariance * contrastWeight

        // 3. Position score — accumulator loop, no List allocation
        var cx = 0f; var cy = 0f
        for (lm in lms) { cx += lm.x; cy += lm.y }
        cx /= n; cy /= n
        val distFromCenter = sqrt((cx - 0.5f) * (cx - 0.5f) + (cy - 0.5f) * (cy - 0.5f))
        val positionScore = (1f - distFromCenter * 2.5f).coerceIn(0f, 1f)

        // 4. G6: Temporal consistency
        val temporalScore = temporalConsistencyScore(lms, prevLms)

        // depthVariance weight redistributed — the CLAHE term previously shared
        // 0.15 of the score as a multiplier on the final sum. Now contrast gates the
        // depth variance contribution directly, so the CLAHE weight shifts to temporal
        // consistency (more stable than a global contrast multiplier).
        val score = (spreadScore   * 0.30f +
                     depthVariance * 0.20f +   // already contrast-weighted above
                     positionScore * 0.20f +
                     temporalScore * 0.30f)

        return QualityResult(
            score = score,
            spreadScore = spreadScore,
            depthVariance = rawDepthVariance,  // raw for display; contrast-weighted used only in score
            positionScore = positionScore,
            temporalConsistency = temporalScore,
            passed = score >= QUALITY_THRESHOLD
        )
    }

    // ─── Motion detection ─────────────────────────────────────────────────

    fun fingerCurlScore(lms: HandLandmarks): Float {
        if (lms.size < 21) return 0f
        val palmCenter = lms[LM.MIDDLE_MCP]
        val tips = listOf(LM.INDEX_TIP, LM.MIDDLE_TIP, LM.RING_TIP, LM.PINKY_TIP)
        val palmSize = lmDist(lms[LM.WRIST], lms[LM.MIDDLE_MCP]).coerceAtLeast(0.001f)
        var distSum = 0f
        for (t in tips) distSum += lmDist(lms[t], palmCenter)
        val avgDist = distSum / tips.size
        return (avgDist / (palmSize * 1.5f)).coerceIn(0f, 1f)
    }

    fun wristRollScore(lms: HandLandmarks): Float {
        if (lms.size < 18) return 0f
        val wrist = lms[LM.WRIST]
        val indexMcp = lms[LM.INDEX_MCP]
        val pinkyMcp = lms[LM.PINKY_MCP]
        val palmMidX = (indexMcp.x + pinkyMcp.x) / 2f
        return (wrist.x - palmMidX) * 5f
    }

    fun fingerWaveScore(lms: HandLandmarks): Float {
        if (lms.size < 21) return 0f
        val tips = listOf(LM.INDEX_TIP, LM.MIDDLE_TIP, LM.RING_TIP, LM.PINKY_TIP)
        var diff = 0f
        for (i in 0 until tips.size - 1) {
            diff += abs(lms[tips[i]].y - lms[tips[i + 1]].y)
        }
        return (diff * 8f).coerceIn(0f, 1f)
    }

    fun thumbWaveScore(lms: HandLandmarks): Float {
        if (lms.size < 9) return 0f
        val palmSize = lmDist(lms[LM.WRIST], lms[LM.MIDDLE_MCP]).coerceAtLeast(0.001f)
        val dist = lmDist(lms[LM.THUMB_TIP], lms[LM.INDEX_MCP])
        return (dist / (palmSize * 1.2f)).coerceIn(0f, 1f)
    }
}
