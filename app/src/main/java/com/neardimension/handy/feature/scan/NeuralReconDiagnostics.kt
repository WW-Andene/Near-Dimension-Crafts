package com.neardimension.handy.feature.scan

/**
 * Training diagnostics for the neural implicit reconstruction pass.
 *
 * @param trainLoss       Final binary cross-entropy loss after MLP training.
 * @param trainEpochs     Number of epochs trained.
 * @param shapeScale      Per-segment radius multipliers from MANO shape fitting.
 *                        Null when [com.neardimension.handy.BuildConfig.FEATURE_SMPL_BODY] is false.
 * @param meshVertexCount Number of triangle vertices in the extracted neural mesh.
 */
data class NeuralReconDiagnostics(
    val trainLoss:       Float,
    val trainEpochs:     Int,
    val shapeScale:      FloatArray?,
    val meshVertexCount: Int
)
