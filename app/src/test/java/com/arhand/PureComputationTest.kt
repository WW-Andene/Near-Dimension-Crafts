package com.arhand

import com.arhand.depth.MarchingCubes
import com.arhand.mocap.BoneRetargeter
import com.arhand.mocap.BindPose
import com.arhand.mocap.Quaternion
import com.arhand.mocap.MotionRecorder
import com.arhand.mocap.RetargetResult
import com.arhand.mocap.WristTransform
import com.arhand.scanner.HandBiometrics
import com.arhand.scanner.QualityEngine
import com.arhand.tracking.HandLandmarks
import com.arhand.tracking.LM
import com.arhand.tracking.Landmark
import com.arhand.util.Vec3
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Flat 21-landmark hand at canonical positions — palm facing camera, centred. */
private fun syntheticHand(
    palmWidth: Float = 0.10f,
    spread: Boolean = true,
    cx: Float = 0.5f,
    cy: Float = 0.5f
): HandLandmarks {
    // Wrist at bottom, MIDDLE_MCP at top — approx anatomical proportions
    val palmHeight = palmWidth * 1.4f
    val fingerLen  = palmWidth * 0.7f
    val zBase      = 0f

    fun lm(x: Float, y: Float, z: Float = zBase) = Landmark(cx + x, cy + y, z)

    val spreadFactor = if (spread) 1.0f else 0.1f

    return listOf(
        // 0 Wrist
        lm(0f, palmHeight * 0.5f),
        // 1 Thumb CMC
        lm(-palmWidth * 0.45f, palmHeight * 0.2f, -0.01f),
        // 2 Thumb MCP
        lm(-palmWidth * 0.52f, 0f, -0.02f),
        // 3 Thumb IP
        lm(-palmWidth * 0.55f, -palmHeight * 0.15f, -0.03f),
        // 4 Thumb Tip
        lm(-palmWidth * 0.55f, -palmHeight * 0.28f, -0.04f),
        // 5 Index MCP
        lm(-palmWidth * 0.25f, -palmHeight * 0.1f, -0.02f),
        // 6 Index PIP
        lm(-palmWidth * 0.25f - spreadFactor * 0.01f, -palmHeight * 0.1f - fingerLen * 0.35f, -0.03f),
        // 7 Index DIP
        lm(-palmWidth * 0.25f - spreadFactor * 0.015f, -palmHeight * 0.1f - fingerLen * 0.65f, -0.04f),
        // 8 Index Tip
        lm(-palmWidth * 0.25f - spreadFactor * 0.02f, -palmHeight * 0.1f - fingerLen, -0.05f),
        // 9 Middle MCP
        lm(0f, -palmHeight * 0.12f, -0.02f),
        // 10 Middle PIP
        lm(0f, -palmHeight * 0.12f - fingerLen * 0.38f, -0.03f),
        // 11 Middle DIP
        lm(0f, -palmHeight * 0.12f - fingerLen * 0.68f, -0.04f),
        // 12 Middle Tip
        lm(0f, -palmHeight * 0.12f - fingerLen * 1.0f, -0.05f),
        // 13 Ring MCP
        lm(palmWidth * 0.22f, -palmHeight * 0.10f, -0.02f),
        // 14 Ring PIP
        lm(palmWidth * 0.22f + spreadFactor * 0.01f, -palmHeight * 0.10f - fingerLen * 0.35f, -0.03f),
        // 15 Ring DIP
        lm(palmWidth * 0.22f + spreadFactor * 0.015f, -palmHeight * 0.10f - fingerLen * 0.65f, -0.04f),
        // 16 Ring Tip
        lm(palmWidth * 0.22f + spreadFactor * 0.02f, -palmHeight * 0.10f - fingerLen * 0.95f, -0.05f),
        // 17 Pinky MCP
        lm(palmWidth * 0.44f, -palmHeight * 0.05f, -0.02f),
        // 18 Pinky PIP
        lm(palmWidth * 0.44f + spreadFactor * 0.01f, -palmHeight * 0.05f - fingerLen * 0.28f, -0.03f),
        // 19 Pinky DIP
        lm(palmWidth * 0.44f + spreadFactor * 0.015f, -palmHeight * 0.05f - fingerLen * 0.52f, -0.04f),
        // 20 Pinky Tip
        lm(palmWidth * 0.44f + spreadFactor * 0.02f, -palmHeight * 0.05f - fingerLen * 0.75f, -0.05f)
    )
}

private fun assertNear(expected: Float, actual: Float, tolerance: Float = 1e-4f) {
    assertTrue(
        "Expected $expected ± $tolerance but was $actual",
        abs(actual - expected) <= tolerance
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// MarchingCubes
// ─────────────────────────────────────────────────────────────────────────────

class MarchingCubesTest {

    /** An empty SDF (all positive) should produce zero triangles. */
    @Test fun emptyFieldProducesNoVertices() {
        val N = 4
        val sdf = FloatArray(N * N * N) { 1f }   // all outside
        val result = MarchingCubes.extract(sdf, N, 1f / N)
        assertEquals(0, result.size)
    }

    /** An inverted SDF (all negative) should also produce zero triangles — fully inside. */
    @Test fun fullyInsideFieldProducesNoVertices() {
        val N = 4
        val sdf = FloatArray(N * N * N) { -1f }
        val result = MarchingCubes.extract(sdf, N, 1f / N)
        assertEquals(0, result.size)
    }

    /**
     * A sphere SDF centred at (0.5, 0.5, 0.5) with radius 0.35 in a 16³ grid.
     * Result must have a positive vertex count, be divisible by 9 (3 floats × 3 verts per tri),
     * and all vertices must lie reasonably close to radius 0.35 from centre.
     */
    @Test fun sphereSdfProducesClosedMesh() {
        val N = 16
        val cellSize = 1f / N
        val sdf = FloatArray(N * N * N)
        for (z in 0 until N) for (y in 0 until N) for (x in 0 until N) {
            val cx = (x + 0.5f) * cellSize - 0.5f
            val cy = (y + 0.5f) * cellSize - 0.5f
            val cz = (z + 0.5f) * cellSize - 0.5f
            sdf[x + N * y + N * N * z] = sqrt(cx*cx + cy*cy + cz*cz) - 0.35f
        }
        val result = MarchingCubes.extract(sdf, N, cellSize, floatArrayOf(-0.5f, -0.5f, -0.5f))

        assertTrue("Expected vertices, got none", result.isNotEmpty())
        assertEquals("Vertex array must be divisible by 9", 0, result.size % 9)

        // All generated vertices should be within ±2 cell widths of the sphere surface
        val tolerance = cellSize * 2f
        for (i in result.indices step 3) {
            val vx = result[i]; val vy = result[i + 1]; val vz = result[i + 2]
            val r = sqrt(vx*vx + vy*vy + vz*vz)
            assertTrue("Vertex at ($vx,$vy,$vz) r=$r too far from sphere surface",
                abs(r - 0.35f) < tolerance)
        }
    }

    /** A single-voxel sign change (one corner negative) must produce exactly one triangle. */
    @Test fun singleCornerNegativeProducesOneTriangle() {
        val N = 3
        val sdf = FloatArray(N * N * N) { 1f }
        sdf[0] = -1f   // idx(0,0,0) = 0
        val result = MarchingCubes.extract(sdf, N, 1f / N)
        // cube index 1 → one triangle → 9 floats
        assertEquals(9, result.size)
    }

    /** Origin offset is applied correctly — all verts shifted by the offset vector. */
    @Test fun originOffsetIsApplied() {
        val N = 4
        val cellSize = 0.25f
        val sdf = FloatArray(N * N * N)
        for (z in 0 until N) for (y in 0 until N) for (x in 0 until N) {
            val cx = (x + 0.5f) * cellSize - 0.5f
            val cy = (y + 0.5f) * cellSize - 0.5f
            val cz = (z + 0.5f) * cellSize - 0.5f
            sdf[x + N * y + N * N * z] = sqrt(cx*cx + cy*cy + cz*cz) - 0.3f
        }

        val originA = floatArrayOf(0f, 0f, 0f)
        val originB = floatArrayOf(1f, 2f, 3f)

        val resultA = MarchingCubes.extract(sdf, N, cellSize, originA)
        val resultB = MarchingCubes.extract(sdf, N, cellSize, originB)

        assertEquals("Both extractions must produce same vertex count", resultA.size, resultB.size)
        assertTrue("Must have vertices", resultA.isNotEmpty())

        for (i in resultA.indices step 3) {
            assertNear(resultA[i]     + 1f, resultB[i],     tolerance = 1e-4f)
            assertNear(resultA[i + 1] + 2f, resultB[i + 1], tolerance = 1e-4f)
            assertNear(resultA[i + 2] + 3f, resultB[i + 2], tolerance = 1e-4f)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HandBiometrics
// ─────────────────────────────────────────────────────────────────────────────

class HandBiometricsTest {

    private val aspect = 1.0f

    /** Null when no frames provided. */
    @Test fun returnsNullForEmptyFrameList() {
        assertNull(HandBiometrics.compute(emptyList(), aspect))
    }

    /** A synthetic hand with a known palm width should yield a palmWidth close to the real value. */
    @Test fun palmWidthReasonableForSyntheticHand() {
        val hand = syntheticHand(palmWidth = 0.10f)
        val frames = List(10) { hand to 0.9f }
        val bio = HandBiometrics.compute(frames, aspect)!!

        // palmWidth in world units — should reflect INDEX_MCP to PINKY_MCP separation
        // The synthetic hand places them ~palmWidth * 0.69 apart in normalised coords,
        // which after landmarkToWorld (VIEW_SCALE=0.65, aspect=1) gives a world distance.
        // We just verify it's positive and non-zero.
        assertTrue("palmWidth should be positive", bio.palmWidth > 0f)
        assertTrue("knuckleBreadth ≥ palmWidth (12% correction)", bio.knuckleBreadth >= bio.palmWidth)
    }

    /** Middle finger should be >= ring and >= index (standard proportions). */
    @Test fun middleFingerLongestOrNearLongest() {
        val hand = syntheticHand()
        val frames = List(10) { hand to 0.85f }
        val bio = HandBiometrics.compute(frames, aspect)!!
        // Middle is constructed as the longest in the synthetic hand
        assertTrue("middle >= ring",  bio.middleLength >= bio.ringLength  - 1e-4f)
        assertTrue("middle >= pinky", bio.middleLength >= bio.pinkyLength - 1e-4f)
    }

    /** Wrist circumference = π × diameter, so it must exceed palm width. */
    @Test fun wristCircumferenceExceedsPalmWidth() {
        val hand = syntheticHand()
        val frames = List(10) { hand to 0.9f }
        val bio = HandBiometrics.compute(frames, aspect)!!
        assertTrue("wristCircumference > palmWidth", bio.wristCircumference > bio.palmWidth)
    }

    /** Low-quality frames mixed with high-quality: result should still be valid. */
    @Test fun worksWithMixedQualityFrames() {
        val goodHand = syntheticHand()
        val frames = List(10) { i -> goodHand to if (i < 3) 0.9f else 0.2f }
        val bio = HandBiometrics.compute(frames, aspect)
        assertNotNull("Should still compute from top frames", bio)
    }

    /** Only 1 valid frame — returns a result (no IQR trimming needed). */
    @Test fun singleFrameReturnsResult() {
        val hand = syntheticHand()
        val bio = HandBiometrics.compute(listOf(hand to 0.8f), aspect)
        assertNotNull(bio)
    }

    /** handSpan ≥ palmWidth — thumb tip to pinky tip is always wider than knuckle row. */
    @Test fun handSpanExceedsPalmWidth() {
        val hand = syntheticHand(spread = true)
        val frames = List(10) { hand to 0.9f }
        val bio = HandBiometrics.compute(frames, aspect)!!
        assertTrue("handSpan >= palmWidth", bio.handSpan >= bio.palmWidth - 1e-4f)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BoneRetargeter — shortestArcQuaternion edge cases (tested via reflection)
// ─────────────────────────────────────────────────────────────────────────────

class BoneRetargeterTest {

    /** Access private shortestArcQuaternion via reflection so the test lives outside the class. */
    private fun shortestArc(from: Vec3, to: Vec3): Quaternion {
        val bindPose = BoneRetargeter.symmetricBindPose()
        val retargeter = BoneRetargeter(bindPose)
        val method = BoneRetargeter::class.java.getDeclaredMethod(
            "shortestArcQuaternion",
            Vec3::class.java, Vec3::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(retargeter, from, to) as Quaternion
    }

    /** Parallel vectors → identity quaternion (w=1, xyz=0). */
    @Test fun parallelVectorsYieldIdentity() {
        val q = shortestArc(Vec3(0f, 1f, 0f), Vec3(0f, 1f, 0f))
        assertNear(0f, q.x)
        assertNear(0f, q.y)
        assertNear(0f, q.z)
        assertNear(1f, q.w)
    }

    /** Anti-parallel vectors → 180° rotation, |q|=1, w≈0. */
    @Test fun antiParallelVectorsYield180Rotation() {
        val q = shortestArc(Vec3(0f, 1f, 0f), Vec3(0f, -1f, 0f))
        val len = sqrt(q.x*q.x + q.y*q.y + q.z*q.z + q.w*q.w)
        assertNear(1f, len, tolerance = 1e-4f)
        assertNear(0f, q.w, tolerance = 1e-4f)
    }

    /** 90° rotation: result must be unit quaternion. */
    @Test fun ninety_degreesProducesUnitQuaternion() {
        val q = shortestArc(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f))
        val len = sqrt(q.x*q.x + q.y*q.y + q.z*q.z + q.w*q.w)
        assertNear(1f, len, tolerance = 1e-4f)
    }

    /** Rotating (1,0,0) by 90° around Z should produce (0,1,0). */
    @Test fun ninety_degreesAroundZ_rotatesCorrectly() {
        val q = shortestArc(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f))
        // q should have axis ≈ (0,0,1), angle = 90° → q = (0, 0, sin45°, cos45°)
        assertNear(0f, q.x, tolerance = 1e-4f)
        assertNear(0f, q.y, tolerance = 1e-4f)
        assertNear(sqrt(0.5f), abs(q.z), tolerance = 1e-4f)
        assertNear(sqrt(0.5f), abs(q.w), tolerance = 1e-4f)
    }

    /** Anti-parallel with X-axis: fallback axis must be non-degenerate. */
    @Test fun antiParallelXAxisNonDegenerate() {
        val q = shortestArc(Vec3(1f, 0f, 0f), Vec3(-1f, 0f, 0f))
        val len = sqrt(q.x*q.x + q.y*q.y + q.z*q.z + q.w*q.w)
        assertNear(1f, len, tolerance = 1e-4f)
        // Axis must not be zero
        val axisLen = sqrt(q.x*q.x + q.y*q.y + q.z*q.z)
        assertTrue("Fallback axis must be non-zero", axisLen > 0.5f)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// QualityEngine
// ─────────────────────────────────────────────────────────────────────────────

class QualityEngineTest {

    /** Fewer than 21 landmarks → score 0, not passed. */
    @Test fun shortLandmarkListScoresZero() {
        val partial = List(10) { Landmark(0.5f, 0.5f, 0f) }
        val result = QualityEngine.evaluate(partial)
        assertNear(0f, result.score)
        assertFalse(result.passed)
    }

    /** A well-formed hand (centred, spread, some Z variance) should exceed threshold. */
    @Test fun goodHandExceedsThreshold() {
        val hand = syntheticHand(cx = 0f, cy = 0f)  // centred in normalised space
        val result = QualityEngine.evaluate(hand, claheContrast = 0.9f)
        assertTrue("Good hand should exceed QUALITY_THRESHOLD (${QualityEngine.QUALITY_THRESHOLD}); got ${result.score}",
            result.score >= QualityEngine.QUALITY_THRESHOLD)
    }

    /** All landmarks at the same point → spread=0, depth variance=0 → very low score. */
    @Test fun collapsedHandScoresVeryLow() {
        val collapsed = List(21) { Landmark(0.5f, 0.5f, 0f) }
        val result = QualityEngine.evaluate(collapsed)
        assertTrue("Collapsed hand should score below threshold; got ${result.score}",
            result.score < QualityEngine.QUALITY_THRESHOLD)
    }

    /** Score is in [0, 1]. */
    @Test fun scoreIsNormalised() {
        val hand = syntheticHand()
        val result = QualityEngine.evaluate(hand, claheContrast = 0.8f)
        assertTrue("score >= 0", result.score >= 0f)
        assertTrue("score <= 1", result.score <= 1f)
    }

    /** temporalConsistencyScore == 1.0 when prevLms is null. */
    @Test fun temporalConsistencyIsOneWithNoPreviousFrame() {
        val hand = syntheticHand()
        assertNear(1f, QualityEngine.temporalConsistencyScore(hand, null))
    }

    /** temporalConsistencyScore → 0 when current frame is wildly different from previous. */
    @Test fun temporalConsistencyIsLowForLargeJump() {
        val hand = syntheticHand(cx = 0.5f, cy = 0.5f)
        val jumped = syntheticHand(cx = 0.0f, cy = 0.0f)   // large translation
        val score = QualityEngine.temporalConsistencyScore(jumped, hand)
        assertTrue("Large jump should give low temporal consistency; got $score", score < 0.5f)
    }

    /** temporalConsistencyScore ≈ 1.0 when both frames are identical. */
    @Test fun temporalConsistencyIsMaxForIdenticalFrames() {
        val hand = syntheticHand()
        assertNear(1f, QualityEngine.temporalConsistencyScore(hand, hand))
    }

    /** Hand off-centre → positionScore lower. */
    @Test fun offCentreHandHasLowerScore() {
        val centred  = syntheticHand(cx = 0f, cy = 0f)
        val offEdge  = syntheticHand(cx = 0.45f, cy = 0.45f)  // near corner of normalised space
        val rCentred = QualityEngine.evaluate(centred,  claheContrast = 0.8f)
        val rOffEdge = QualityEngine.evaluate(offEdge,  claheContrast = 0.8f)
        assertTrue(
            "Centred hand should score >= off-edge hand; centred=${rCentred.score} offEdge=${rOffEdge.score}",
            rCentred.score >= rOffEdge.score
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MotionRecorder — quaternionToZXY
// ─────────────────────────────────────────────────────────────────────────────

class MotionRecorderTest {

    /** Access private quaternionToZXY via reflection. */
    private fun toZXY(q: Quaternion): Triple<Float, Float, Float> {
        val recorder = MotionRecorder()
        val method = MotionRecorder::class.java.getDeclaredMethod(
            "quaternionToZXY", Quaternion::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(recorder, q) as Triple<Float, Float, Float>
    }

    /** Identity quaternion → (0°, 0°, 0°). */
    @Test fun identityQuaternionProducesZeroAngles() {
        val (ez, ex, ey) = toZXY(Quaternion(0f, 0f, 0f, 1f))
        assertNear(0f, ez, tolerance = 0.01f)
        assertNear(0f, ex, tolerance = 0.01f)
        assertNear(0f, ey, tolerance = 0.01f)
    }

    /** 90° rotation around X → ex ≈ 90°, ey ≈ 0°, ez ≈ 0°. */
    @Test fun ninetyDegreesAroundX() {
        // q = (sin45°, 0, 0, cos45°)
        val s = sqrt(0.5f)
        val (ez, ex, ey) = toZXY(Quaternion(s, 0f, 0f, s))
        assertNear(90f, ex, tolerance = 0.5f)
        assertNear(0f,  ey, tolerance = 0.5f)
        assertNear(0f,  ez, tolerance = 0.5f)
    }

    /** 90° rotation around Y → ey ≈ 90°, ex ≈ 0°, ez ≈ 0°. */
    @Test fun ninetyDegreesAroundY() {
        val s = sqrt(0.5f)
        val (ez, ex, ey) = toZXY(Quaternion(0f, s, 0f, s))
        assertNear(0f,  ex, tolerance = 0.5f)
        assertNear(90f, ey, tolerance = 0.5f)
        assertNear(0f,  ez, tolerance = 0.5f)
    }

    /** 90° rotation around Z → ez ≈ 90°, ex ≈ 0°, ey ≈ 0°. */
    @Test fun ninetyDegreesAroundZ() {
        val s = sqrt(0.5f)
        val (ez, ex, ey) = toZXY(Quaternion(0f, 0f, s, s))
        assertNear(0f,  ex, tolerance = 0.5f)
        assertNear(0f,  ey, tolerance = 0.5f)
        assertNear(90f, ez, tolerance = 0.5f)
    }

    /** Output angles are in degrees, so all values in [-180, 180]. */
    @Test fun anglesAreInDegreesRange() {
        // Test several quaternions
        val quats = listOf(
            Quaternion(0f, 0f, 0f, 1f),
            Quaternion(sqrt(0.5f), 0f, 0f, sqrt(0.5f)),
            Quaternion(0f, sqrt(0.5f), 0f, sqrt(0.5f)),
            Quaternion(0.5f, 0.5f, 0.5f, 0.5f)
        )
        for (q in quats) {
            val (ez, ex, ey) = toZXY(q)
            assertTrue("ex in degrees range: $ex", ex in -181f..181f)
            assertTrue("ey in degrees range: $ey", ey in -181f..181f)
            assertTrue("ez in degrees range: $ez", ez in -181f..181f)
        }
    }

    // ─── MotionRecorder ring buffer ───────────────────────────────────────────

    /** pushFrame respects maxFrames cap. */
    @Test fun ringBufferCapsAtMaxFrames() {
        val recorder = MotionRecorder(maxFrames = 5)
        recorder.start()
        val fakeResult = makeRetargetResult()
        repeat(10) { recorder.pushFrame(fakeResult) }
        assertEquals(5, recorder.frameCount)
    }

    /** reset clears the buffer. */
    @Test fun resetClearsBuffer() {
        val recorder = MotionRecorder(maxFrames = 10)
        recorder.start()
        repeat(3) { recorder.pushFrame(makeRetargetResult()) }
        recorder.reset()
        assertEquals(0, recorder.frameCount)
    }

    /** isRecording is false by default. */
    @Test fun isNotRecordingByDefault() {
        assertFalse(MotionRecorder().isRecording)
    }

    /** isRecording becomes true after start(). */
    @Test fun isRecordingAfterStart() {
        val recorder = MotionRecorder()
        recorder.start()
        assertTrue(recorder.isRecording)
    }

    /** Frames are not accepted when not recording. */
    @Test fun pushFrameIgnoredWhenNotRecording() {
        val recorder = MotionRecorder(maxFrames = 10)
        repeat(5) { recorder.pushFrame(makeRetargetResult()) }
        assertEquals(0, recorder.frameCount)
    }

    private fun makeRetargetResult(): RetargetResult {
        val identity = Quaternion(0f, 0f, 0f, 1f)
        val joints = (0 until 16).associateWith { identity }
        return RetargetResult(
            jointRotations = joints,
            wristTransform = WristTransform(Vec3(0f, 0f, 0f), identity)
        )
    }
}
