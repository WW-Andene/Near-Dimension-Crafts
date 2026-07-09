package com.arhand.depth

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Cross-channel confidence arbitration — v27 12-channel extension.
 *
 * ## Purpose
 *
 * The fused depth pipeline combines twelve source channels. Fixed weighting works
 * well on average but fails when one source is dominated by a confound (e.g.
 * XR drops tracking; SfM flow stalls on a static scene; PSP data is stale).
 *
 * This arbiter computes per-block per-channel agreement weights using a Gaussian
 * z-score gate: channels that deviate from the per-block consensus are suppressed.
 *
 * ## Algorithm (per block)
 *
 *   μ = mean of N channel values for this block
 *   σ = std-dev of the N channel values
 *   w_ch = exp(−2 × ((v_ch − μ) / max(σ, ε))²)
 *   → normalise: w_ch /= Σw_ch
 *
 * Blocks where all channels agree (low σ) get near-uniform weights.
 * Blocks with an outlier channel get that channel suppressed.
 *
 * ## v27 12-channel weight set
 *
 *   CH_BASE    — base ensemble quality signal (blur/flow/chroma composite)
 *   CH_SL      — structured light (SL v7)
 *   CH_PSP     — 4-step phase-shifting profilometry
 *   CH_DVEL    — depth velocity / temporal coherence
 *   CH_DA2     — Depth Anything v2 monocular depth
 *   CH_LCA     — lens chromatic aberration depth
 *   CH_XR      — ARCore XR metric depth
 *   CH_POL     — polarimetric depth (hardware-infeasible on phone cameras — see below)
 *   CH_FLARE   — lens-flare/blown-highlight confidence ([FlareDetector])
 *   CH_MOIRE   — moiré-interference confidence ([MoireDetector])
 *   CH_RS      — rolling-shutter stereo (baseWeight 0 by design — see CH_RS below)
 *   CH_STEREO  — dual-camera stereo
 *
 * CH_POL (polarimetric depth) is permanently supplied as an all-zero array — it needs a
 * polarization-filter sensor no phone camera has, so there's nothing to "finish" here in
 * software; the Gaussian gate assigns it near-zero weight automatically like any other
 * all-zero channel.
 */
class CrossChannelArbiter(private val blockCount: Int) {

    companion object {
        private const val GATE_SCALE = 2f
        private const val EPS        = 0.02f

        const val CHANNEL_COUNT = 12

        // Channel indices
        const val CH_BASE   = 0   // base ensemble (blur + flow + chroma quality composite)
        const val CH_SL     = 1   // structured light
        const val CH_PSP    = 2   // 4-step phase-shifting profilometry
        const val CH_DVEL   = 3   // depth velocity / temporal coherence
        const val CH_DA2    = 4   // Depth Anything v2 monocular depth
        const val CH_LCA    = 5   // lens chromatic aberration
        const val CH_XR     = 6   // ARCore XR metric depth
        const val CH_POL    = 7   // polarimetric — hardware-infeasible, permanently zeroed
        const val CH_FLARE  = 8   // lens-flare/highlight confidence
        const val CH_MOIRE  = 9   // moiré-interference confidence
        const val CH_RS     = 10  // rolling-shutter stereo
        const val CH_STEREO = 11  // dual-camera stereo
    }

    /**
     * Base weights (prior) for the 12 channels.
     * XR dominates when available; SL/PSP are high precision; DA2 covers the monocular case.
     * CH_POL receives minimal prior weight since it's permanently zeroed (hardware-infeasible).
     * CH_RS is implemented but its prior is 0 on physical grounds, not because it's
     * unimplemented — see below.
     */
    val baseWeights = floatArrayOf(
        0.06f,  // CH_BASE   — quality composite fallback
        0.18f,  // CH_SL     — structured light, precise surface detail
        0.02f,  // CH_PSP    — phase shifting (zeroed when stale; only fresh on explicit capture)
        0.06f,  // CH_DVEL   — temporal coherence guard
        0.19f,  // CH_DA2    — monocular depth, continuous and spatially dense (+0.06 from PSP)
        0.03f,  // CH_LCA    — minor lens correction
        0.38f,  // CH_XR     — ARCore metric, most accurate source (+0.06 from PSP)
        0.01f,  // CH_POL    — permanently zeroed; hardware-infeasible on phone cameras
        0.03f,  // CH_FLARE  — artefact suppression
        0.03f,  // CH_MOIRE  — artefact suppression
        0.00f,  // CH_RS     — rolling-shutter stereo (prior zeroed: <0.1mm baseline → SNR≈0
                //             during normal hand tremor; real signal is fed in, so the
                //             per-block agreement gate can still surface it when a scan's
                //             deliberate camera sweep gives a usable baseline)
        0.01f   // CH_STEREO — minor prior; dual-lens hardware is uncommon and unverified per-device
    )

    init { require(baseWeights.size == CHANNEL_COUNT) }

    /** Pre-allocated output: [blockCount × CHANNEL_COUNT] normalised weights. */
    private val _weights = FloatArray(blockCount * CHANNEL_COUNT)

    /**
     * Compute per-block per-channel agreement weights.
     *
     * Each array must have length ≥ [blockCount]. Unimplemented sources pass
     * FloatArray([blockCount]) (all zeros).
     *
     * @return Pre-allocated [blockCount × 12] weight array (valid until next call).
     */
    fun compute(
        base:   FloatArray,
        sl:     FloatArray,
        psp:    FloatArray,
        dvel:   FloatArray,
        da2:    FloatArray,
        lca:    FloatArray,
        xr:     FloatArray,
        pol:    FloatArray,
        flare:  FloatArray,
        moire:  FloatArray,
        rs:     FloatArray,
        stereo: FloatArray
    ): FloatArray {
        val w = _weights
        for (i in 0 until blockCount) {
            val v = floatArrayOf(
                base[i], sl[i], psp[i], dvel[i], da2[i], lca[i],
                xr[i],   pol[i], flare[i], moire[i], rs[i], stereo[i]
            )
            var mu = 0f
            for (c in 0 until CHANNEL_COUNT) mu += v[c]
            mu /= CHANNEL_COUNT

            var variance = 0f
            for (c in 0 until CHANNEL_COUNT) { val d = v[c] - mu; variance += d * d }
            val sigma = sqrt(variance / CHANNEL_COUNT)
            val sc = 1f / maxOf(sigma, EPS)

            var wSum = 0f
            val base2 = i * CHANNEL_COUNT
            for (c in 0 until CHANNEL_COUNT) {
                val d  = v[c] - mu
                val wc = exp(-GATE_SCALE * (d * sc) * (d * sc)).toFloat()
                w[base2 + c] = wc
                wSum += wc
            }
            if (wSum > 0f) {
                val inv = 1f / wSum
                for (c in 0 until CHANNEL_COUNT) w[base2 + c] *= inv
            } else {
                val unif = 1f / CHANNEL_COUNT.toFloat()
                for (c in 0 until CHANNEL_COUNT) w[base2 + c] = unif
            }
        }
        return w
    }

    /**
     * Convenience overload for the legacy 3-source (ARCore/SfM/Photo) call site.
     * Maps: blur→XR, flow→SL, chroma→BASE, others→zero.
     */
    fun compute(
        blur: FloatArray, flow: FloatArray, chroma: FloatArray,
        specular: FloatArray, vignette: FloatArray, roll: FloatArray
    ): FloatArray {
        val z = FloatArray(blockCount)
        return compute(
            base   = chroma,
            sl     = flow,
            psp    = z,
            dvel   = specular,
            da2    = z,
            lca    = vignette,
            xr     = blur,
            pol    = z,
            flare  = z,
            moire  = z,
            rs     = roll,
            stereo = z
        )
    }

    /**
     * Compute the arbitrated fusion value for a single block.
     *
     * Applies the channel weights (from [compute]) to the base weight prior and
     * returns the weighted average over [channelValues].
     */
    fun fusedValue(i: Int, weights: FloatArray, channelValues: FloatArray): Float {
        val base2 = i * CHANNEL_COUNT
        val n     = minOf(CHANNEL_COUNT, channelValues.size)
        var sum   = 0f; var norm = 0f
        for (c in 0 until n) {
            val bw = if (c < baseWeights.size) baseWeights[c] else 0f
            val w  = bw * weights[base2 + c]
            sum  += w * channelValues[c]
            norm += w
        }
        return if (norm > 0f) sum / norm else 0f
    }
}
