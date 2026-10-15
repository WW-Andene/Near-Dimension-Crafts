package com.arhand.feature.scan

import android.graphics.Bitmap
import com.arhand.depth.DepthApiCarver
import com.arhand.depth.DepthCarver
import com.arhand.depth.ManoShapeFitter
import com.arhand.depth.NeuralImplicitCarver
import com.arhand.depth.TSDFVolume
import com.arhand.mocap.BoneRetargeter
import com.arhand.scanner.HandBiometrics
import com.arhand.scanner.PersonalModelStore
import com.arhand.tracking.HandLandmarks
import com.arhand.util.Vec3

/**
 * All inputs needed to process a completed scan.
 *
 * Constructed by [com.arhand.ui.AppViewModel] at scan completion time from
 * thread-safe snapshots of all mutable buffers. Once constructed this object
 * is immutable and safe to pass to [ScanPipeline.process] on any dispatcher.
 */
data class ScanInput(
    val cloudPoints:        List<Vec3>,
    val capturedFrames:     List<Pair<List<Vec3>, Float>>,
    val biometricFrames:    List<Pair<HandLandmarks, Float>>,
    val capturedDepth:      List<List<Vec3>>,
    val capturedBitmaps:    List<Bitmap>,
    val normalMap:          com.arhand.scanner.PhotometricNormalMap?,
    val tsdfVolume:         TSDFVolume,
    val incrementalCarver:  NeuralImplicitCarver?,
    val neuralReconEnabled: Boolean,
    val smplEnabled:        Boolean,
    val aspect:             Float,
    val isFrontCamera:      Boolean,
    val modelStore:         PersonalModelStore
)

/**
 * All outputs produced by a completed scan.
 *
 * Every field is nullable — a field is null when its computation was skipped
 * (e.g. neuralReconEnabled = false → neuralDiagnostics = null) or failed
 * (e.g. no depth frames → depthMesh is empty).
 */
data class ScanResult(
    val meshPositions:       FloatArray,
    val restJointPositions:  FloatArray?,
    val colorTexturePng:     ByteArray?,
    val glbFile:             java.io.File?,
    val biometrics:          HandBiometrics?,
    val biometricHistory:    List<com.arhand.scanner.BiometricHistoryEntry>,
    val neuralDiagnostics:   NeuralReconDiagnostics?,
    val jointRomData:        com.arhand.scanner.JointRomData?,
    val calibratedOefCutoff: Float?,
    val calibratedOefBeta:   Float?
)

/**
 * Processes a completed scan into mesh, biometrics, and export files.
 *
 * This is a pure function class — no Android framework dependencies,
 * no ViewModel references. All side effects (updating the renderer,
 * persisting the GLB path) are performed by [com.arhand.ui.AppViewModel]
 * after it receives the [ScanResult].
 *
 * [process] is a suspend function and must be called from a coroutine.
 * It does not dispatch internally — call it on [kotlinx.coroutines.Dispatchers.Default].
 */
class ScanPipeline {

    /**
     * Run the full scan processing pipeline on [input] and return a [ScanResult].
     *
     * Never throws — all exceptions are caught and produce a partial result.
     * Callers should check which fields are non-null to determine what succeeded.
     */
    suspend fun process(
        input:   ScanInput,
        context: android.content.Context
    ): ScanResult {

        // ── Step 1: Depth mesh ────────────────────────────────────────────────
        val depthMesh: FloatArray = runCatching {
            when {
                input.tsdfVolume.frameCount >= 4 ->
                    input.tsdfVolume.extractMesh()
                input.capturedDepth.isNotEmpty() ->
                    DepthApiCarver.carveAndExtract(input.capturedDepth, input.normalMap)
                else -> FloatArray(0)
            }
        }.onFailure { android.util.Log.e("ScanPipeline", "Step 1 (depth mesh) failed", it) }
         .getOrDefault(FloatArray(0))

        // ── Step 2: Neural / MANO reconstruction ─────────────────────────────
        var neuralDiagnostics: NeuralReconDiagnostics? = null
        var poseMeshesForBlendShapes: List<Pair<String, FloatArray>>? = null

        val neuralOrDepthMesh: FloatArray = runCatching {
            when {
                depthMesh.isNotEmpty() -> depthMesh

                input.neuralReconEnabled && input.capturedFrames.isNotEmpty() -> {
                    val shapeParams = if (input.smplEnabled)
                        ManoShapeFitter().fit(input.capturedFrames.map { it.first })
                    else ManoShapeFitter.ShapeParams.IDENTITY

                    val carver = if (input.incrementalCarver?.isTrained == true) {
                        input.incrementalCarver.trainIncremental(
                            newFrames = input.capturedFrames,
                            epochs    = NeuralImplicitCarver.INCREMENTAL_EPOCHS,
                            normalMap = input.normalMap
                        )
                        input.incrementalCarver
                    } else {
                        NeuralImplicitCarver().also {
                            it.train(input.capturedFrames, NeuralImplicitCarver.DEFAULT_EPOCHS, input.normalMap)
                        }
                    }

                    val neuralMesh = if (carver.isTrained)
                        carver.extractMesh(input.capturedFrames) else FloatArray(0)

                    neuralDiagnostics = NeuralReconDiagnostics(
                        trainLoss       = carver.lastTrainLoss,
                        trainEpochs     = carver.lastTrainEpochs,
                        shapeScale      = shapeParams.segmentRadiusScale.copyOf(),
                        meshVertexCount = neuralMesh.size / 3
                    )

                    // Per-pose blend shapes (HAND-7)
                    if (neuralMesh.isNotEmpty()) {
                        poseMeshesForBlendShapes = com.arhand.scanner.ScanPoses.ALL
                            .mapIndexed { poseIdx, pose ->
                                val framesPerPose = maxOf(1, input.capturedFrames.size / com.arhand.scanner.ScanPoses.COUNT)
                                val start = poseIdx * framesPerPose
                                val end   = minOf(start + framesPerPose, input.capturedFrames.size)
                                if (start >= input.capturedFrames.size) return@mapIndexed null
                                val pf = input.capturedFrames.subList(start, end)
                                val pc = NeuralImplicitCarver().also { it.train(pf, 80) }
                                if (pc.isTrained) pose.id to pc.extractMesh(pf) else null
                            }
                            .filterNotNull()
                            .filter { it.second.isNotEmpty() }
                    }

                    if (neuralMesh.isNotEmpty()) neuralMesh
                    else if (input.smplEnabled)
                        ManoShapeFitter().carveWithShapeParams(input.capturedFrames, shapeParams)
                            .takeIf { it.isNotEmpty() }
                            ?: DepthCarver.carveAndExtract(input.capturedFrames)
                    else DepthCarver.carveAndExtract(input.capturedFrames)
                }

                input.capturedFrames.isNotEmpty() ->
                    DepthCarver.carveAndExtract(input.capturedFrames)

                else -> FloatArray(0)
            }
        }.onFailure { android.util.Log.e("ScanPipeline", "Step 2 (neural/MANO recon) failed", it) }
         .getOrDefault(FloatArray(0))
        val meshPos = if (neuralOrDepthMesh.isNotEmpty())
            com.arhand.depth.MeshSmoother.smooth(neuralOrDepthMesh, iterations = 3, lambda = 0.5f)
        else FloatArray(0)

        // ── Step 4: Rest joint positions ──────────────────────────────────────
        val restJoints: FloatArray? = runCatching {
            if (meshPos.isEmpty() || input.biometricFrames.isEmpty()) return@runCatching null
            val rjp = FloatArray(BoneRetargeter.JOINT_COUNT * 3)
            val best = input.biometricFrames.maxByOrNull { it.second }
            if (best != null && best.first.size >= 21) {
                // Real camera capture aspect (bitmap width/height), distinct from input.aspect
                // (the screen-space aspect) — see landmarkToWorld's camAspect parameter.
                val camAspect = input.capturedBitmaps.firstOrNull()
                    ?.let { it.width.toFloat() / it.height.toFloat() } ?: input.aspect
                for ((jointIdx, baseIdx, tipIdx) in BoneRetargeter.BONE_SEGMENTS) {
                    val bl = best.first[baseIdx]; val tl = best.first[tipIdx]
                    val (bx, by, bz) = com.arhand.tracking.landmarkToWorld(bl, input.aspect, input.isFrontCamera, camAspect)
                    val (tx, ty, tz) = com.arhand.tracking.landmarkToWorld(tl, input.aspect, input.isFrontCamera, camAspect)
                    rjp[jointIdx * 3]     = (bx + tx) * 0.5f
                    rjp[jointIdx * 3 + 1] = (by + ty) * 0.5f
                    rjp[jointIdx * 3 + 2] = (bz + tz) * 0.5f
                }
            }
            rjp
        }.onFailure { android.util.Log.e("ScanPipeline", "Step 4 (rest joints) failed", it) }
         .getOrNull()

        // ── Step 5: Texture bake (R1) ─────────────────────────────────────────
        val colorTexturePng: ByteArray? = runCatching {
            if (input.capturedBitmaps.isNotEmpty() && meshPos.isNotEmpty())
                com.arhand.export.TextureBaker.bakeFromTriangles(meshPos, input.capturedBitmaps)
            else null
        }.onFailure { android.util.Log.e("ScanPipeline", "Step 5 (texture bake) failed", it) }
         .getOrNull()

        // ── Step 6: GLB export ────────────────────────────────────────────────
        val scannedOffsets = restJoints?.let { rjp ->
            buildMap {
                for (jointIdx in 0 until BoneRetargeter.JOINT_COUNT) {
                    val base = jointIdx * 3
                    if (base + 2 < rjp.size)
                        put(jointIdx, Vec3(rjp[base], rjp[base + 1], rjp[base + 2]))
                }
            }
        }

        val glbFile: java.io.File? = runCatching {
            val outputDir = context.getExternalFilesDir("scans") ?: return@runCatching null
            outputDir.mkdirs()
            com.arhand.export.GLBExporter.export(
                context         = context,
                positions       = meshPos,
                cloudPoints     = input.cloudPoints,
                normalMap       = input.normalMap,
                poseMeshes      = poseMeshesForBlendShapes,
                colorTexturePng = colorTexturePng
            )
        }.onFailure { android.util.Log.e("ScanPipeline", "Step 6 (GLB export) failed", it) }
         .getOrNull()

        // ── Step 7: Biometrics ────────────────────────────────────────────────
        val biometrics: HandBiometrics? = runCatching {
            if (input.biometricFrames.isEmpty()) null
            else HandBiometrics.compute(input.biometricFrames, input.aspect)
        }.onFailure { android.util.Log.e("ScanPipeline", "Step 7 (biometrics) failed", it) }
         .getOrNull()

        val biometricHistory = runCatching {
            if (biometrics != null) input.modelStore.saveToHistory(biometrics)
            input.modelStore.loadHistory()
        }.onFailure { android.util.Log.e("ScanPipeline", "Step 7b (biometric history) failed", it) }
         .getOrDefault(emptyList())

        // ── Step 8: OEF calibration ────────────────────────────────────────────
        var calibCutoff: Float? = null
        var calibBeta:   Float? = null
        if (input.biometricFrames.size >= 8) {
            runCatching {
                val (c, b) = calibrateOef(input.biometricFrames) ?: return@runCatching
                calibCutoff = c; calibBeta = b
            }.onFailure { android.util.Log.e("ScanPipeline", "Step 8 (OEF calibration) failed", it) }
        }

        return ScanResult(
            meshPositions       = meshPos,
            restJointPositions  = restJoints,
            colorTexturePng     = colorTexturePng,
            glbFile             = glbFile,
            biometrics          = biometrics,
            biometricHistory    = biometricHistory,
            neuralDiagnostics   = neuralDiagnostics,
            jointRomData        = null,   // set by AppViewModel from scanner.romData
            calibratedOefCutoff = calibCutoff,
            calibratedOefBeta   = calibBeta
        )
    }

}

fun calibrateOef(
    frames: List<Pair<com.arhand.tracking.HandLandmarks, Float>>
): Pair<Float, Float>? {
    if (frames.size < 4) return null
    val lmCount = frames.first().first.size
    if (lmCount == 0) return null

    var totalVariance = 0f
    var totalVelocity = 0f
    var velocityCount = 0

    for (lmIdx in 0 until lmCount) {
        val mx = frames.map { it.first[lmIdx].x }.average().toFloat()
        val my = frames.map { it.first[lmIdx].y }.average().toFloat()
        val vx = frames.map { (lm, _) -> val d = lm[lmIdx].x - mx; d * d }.average().toFloat()
        val vy = frames.map { (lm, _) -> val d = lm[lmIdx].y - my; d * d }.average().toFloat()
        totalVariance += kotlin.math.sqrt(vx + vy)
        for (i in 1 until frames.size) {
            val dx = frames[i].first[lmIdx].x - frames[i - 1].first[lmIdx].x
            val dy = frames[i].first[lmIdx].y - frames[i - 1].first[lmIdx].y
            totalVelocity += kotlin.math.sqrt(dx * dx + dy * dy)
            velocityCount++
        }
    }

    val sigma   = totalVariance / lmCount
    val meanVel = if (velocityCount > 0) totalVelocity / velocityCount else 0.01f
    val cutoff  = if (sigma > 1e-6f)
        (1f / (2f * Math.PI.toFloat() * sigma))
        else com.arhand.tracking.HandPipeline.OEF_MIN_CUTOFF_XY

    val beta = (meanVel * 120f).coerceIn(
        com.arhand.tracking.HandPipeline.OEF_BETA_MIN_CLAMP,
        com.arhand.tracking.HandPipeline.OEF_BETA_MAX_CLAMP
    )

    return Pair(
        cutoff.coerceIn(com.arhand.tracking.HandPipeline.OEF_CUTOFF_MIN_CLAMP,
                        com.arhand.tracking.HandPipeline.OEF_CUTOFF_MAX_CLAMP),
        beta
    )
}
