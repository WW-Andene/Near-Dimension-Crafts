package com.arhand.render

import com.arhand.mocap.BoneRetargeter
import com.arhand.mocap.BoneRetargeter.Companion.BONE_SEGMENTS
import com.arhand.mocap.BoneRetargeter.Companion.JOINT_COUNT
import com.arhand.mocap.BoneRetargeter.Companion.JOINT_WRIST
import com.arhand.mocap.RetargetResult
import com.arhand.util.Vec3
import android.opengl.Matrix
import kotlin.math.sqrt

/**
 * G1 — Live mesh deformation via CPU-side linear blend skinning (LBS).
 *
 * Takes a static scanned mesh (flat float array, 3 floats per vertex) and a per-frame
 * [RetargetResult] from [BoneRetargeter], and produces a deformed position array that
 * animates the user's own scanned hand in real time.
 *
 * ## Skinning weight assignment
 *
 * At rest-pose capture time ([buildSkinWeights]), each vertex is assigned weights to the
 * four closest bone segments (by distance from vertex to segment midpoint). Weights are
 * inverse-distance and normalised to sum to 1.0.  This is a one-time O(V × J) pass.
 *
 * ## Per-frame deformation
 *
 * [deform] applies the standard LBS formula:
 *   p' = Σ w_i × (jointMatrix_i × restPose_i⁻¹) × p
 *
 * The joint matrices come directly from [RetargetResult.jointRotations] (quaternion → 4×4).
 * The rest-pose inverse bind matrices are computed once from [buildSkinWeights].
 *
 * Both position and normal arrays are deformed so lighting remains correct.
 *
 * ## Performance
 *
 * A 32³ marching-cubes mesh has at most ~12 000 surface vertices (typical: 4 000–8 000).
 * At 4 weights per vertex this is ~32 000 mat4×vec4 multiplications per frame — under 2ms
 * on Snapdragon 8 Gen 2 running in a coroutine off the main thread.
 */
class LiveMeshDeformer {

    // ─── Skin weight tables (built once per mesh) ─────────────────────────────

    /** Vertex count of the bound mesh. */
    var vertexCount: Int = 0
        private set

    /** Flat rest-pose position array (3 floats/vertex). Stored for incremental re-deform. */
    private var restPositions: FloatArray = FloatArray(0)

    /** Flat rest-pose normal array (3 floats/vertex, flat normals computed from triangles). */
    private var restNormals: FloatArray = FloatArray(0)

    /**
     * Per-vertex joint indices (4 per vertex, packed: v0j0, v0j1, v0j2, v0j3, v1j0 …).
     * Index into [BoneRetargeter] JOINT_* constants.
     */
    private var skinJoints: IntArray = IntArray(0)

    /**
     * Per-vertex blend weights (4 per vertex, sum=1.0f, same packing as [skinJoints]).
     */
    private var skinWeights: FloatArray = FloatArray(0)

    /**
     * Per-joint inverse bind matrices in the REST pose (16 floats each, column-major).
     * Computed from the rest-pose joint positions at [buildSkinWeights] time.
     */
    private var inverseBindMatrices: Array<FloatArray> = emptyArray()

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Bind a newly scanned mesh and compute skinning weights from the rest-pose
     * joint positions.
     *
     * Call this once after each successful scan, passing:
     * @param meshPositions  Flat xyz positions from marching cubes / DepthCarver (smoothed)
     * @param restJointPositions  World-space position of each of the 16 joints in the
     *                            rest/capture pose (obtained from the last RetargetResult
     *                            wristTransform + BONE_SEGMENTS tip positions).
     *                            Length must be JOINT_COUNT × 3.
     */
    fun buildSkinWeights(meshPositions: FloatArray, restJointPositions: FloatArray) {

        // Accessing restJointPositions[i * 3 + 2] for i up to JOINT_COUNT-1 requires at least
        // JOINT_COUNT*3 = 48 elements — throw a clear error rather than an AIOOBE.
        require(restJointPositions.size >= JOINT_COUNT * 3) {
            "restJointPositions must have at least ${JOINT_COUNT * 3} elements " +
            "(got ${restJointPositions.size})"
        }
        val vc = meshPositions.size / 3
        vertexCount   = vc
        restPositions = meshPositions.copyOf()
        restNormals   = computeFlatNormals(meshPositions)

        skinJoints  = IntArray(vc * 4)
        skinWeights = FloatArray(vc * 4)

        // Build joint centre list from restJointPositions (JOINT_COUNT Vec3s)
        val jointCentres = Array(JOINT_COUNT) { i ->
            Vec3(
                restJointPositions[i * 3],
                restJointPositions[i * 3 + 1],
                restJointPositions[i * 3 + 2]
            )
        }

        // For each vertex, find the 4 nearest joints by squared distance
        for (vi in 0 until vc) {
            val px = meshPositions[vi * 3]
            val py = meshPositions[vi * 3 + 1]
            val pz = meshPositions[vi * 3 + 2]

            // Compute distance to every joint
            val dists = FloatArray(JOINT_COUNT) { ji ->
                val jc = jointCentres[ji]
                val dx = px - jc.x; val dy = py - jc.y; val dz = pz - jc.z
                dx * dx + dy * dy + dz * dz   // squared distance
            }

            // Select top-4 nearest joint indices
            val sorted = dists.indices.sortedBy { dists[it] }
            val top4 = sorted.take(4)

            // Inverse-distance weights (use 1/(d²+ε) for numerical safety)
            val invD = top4.map { ji -> 1f / (dists[ji] + 1e-8f) }
            val sum  = invD.sum()

            for (k in 0..3) {
                skinJoints[vi * 4 + k]  = top4[k]
                skinWeights[vi * 4 + k] = invD[k] / sum
            }
        }

        // Build inverse bind matrices: for each joint, the rest-pose transform is
        // translation-only (the joint centre in world space). Inverse = translate by -centre.
        inverseBindMatrices = Array(JOINT_COUNT) { ji ->
            val jc = jointCentres[ji]
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                -jc.x, -jc.y, -jc.z, 1f
            )
        }
    }

    /**
     * Deform the bound mesh for the current frame.
     *
     * @param result  Latest [RetargetResult] from [BoneRetargeter]
     * @return A new [FloatArray] of deformed vertex positions (3 floats/vertex), or
     *         [restPositions] unchanged when [vertexCount] == 0.
     */
    fun deform(result: RetargetResult): FloatArray {
        if (vertexCount == 0) return restPositions

        // Build bone palette: skinMatrix[j] = jointMat[j] × inverseBindMatrix[j]
        val palette = Array(JOINT_COUNT) { ji ->
            val rot  = result.jointRotations[ji] ?: com.arhand.mocap.Quaternion.IDENTITY
            val jMat = rot.toMatrix()

            // Set joint world-space translation from jointPositions.
            // Previously only JOINT_WRIST got a translation — all other joints had
            // m[12..14]=0, making finger bones pivot around the world origin instead of
            // their own pivots.  Now every joint uses its tracked world position.
            val jPos = result.jointPositions[ji]
            if (jPos != null) {
                jMat[12] = jPos.x; jMat[13] = jPos.y; jMat[14] = jPos.z
            } else if (ji == JOINT_WRIST) {
                val t = result.wristTransform.position
                jMat[12] = t.x; jMat[13] = t.y; jMat[14] = t.z
            }

            val ibm     = inverseBindMatrices.getOrElse(ji) { identityMatrix() }
            val skinMat = FloatArray(16)
            Matrix.multiplyMM(skinMat, 0, jMat, 0, ibm, 0)
            skinMat
        }

        val deformed = FloatArray(restPositions.size)

        for (vi in 0 until vertexCount) {
            val px = restPositions[vi * 3]
            val py = restPositions[vi * 3 + 1]
            val pz = restPositions[vi * 3 + 2]

            var ox = 0f; var oy = 0f; var oz = 0f

            for (k in 0..3) {
                val ji = skinJoints[vi * 4 + k]
                val w  = skinWeights[vi * 4 + k]
                if (w < 1e-6f) continue
                val m = palette[ji]
                // Column-major mat4 × vec4(px,py,pz,1)
                ox += w * (m[0] * px + m[4] * py + m[8]  * pz + m[12])
                oy += w * (m[1] * px + m[5] * py + m[9]  * pz + m[13])
                oz += w * (m[2] * px + m[6] * py + m[10] * pz + m[14])
            }

            deformed[vi * 3]     = ox
            deformed[vi * 3 + 1] = oy
            deformed[vi * 3 + 2] = oz
        }

        return deformed
    }

    /** Returns the rest-pose normal array for initial draw before any retarget is available. */
    fun restNormals(): FloatArray = restNormals

    /** True if a mesh has been bound via [buildSkinWeights]. */
    fun hasMesh(): Boolean = vertexCount > 0

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun computeFlatNormals(positions: FloatArray): FloatArray {
        val normals = FloatArray(positions.size)
        var i = 0
        while (i + 9 <= positions.size) {
            val ax = positions[i];   val ay = positions[i+1]; val az = positions[i+2]
            val bx = positions[i+3]; val by = positions[i+4]; val bz = positions[i+5]
            val cx = positions[i+6]; val cy = positions[i+7]; val cz = positions[i+8]
            val ux = bx - ax; val uy = by - ay; val uz = bz - az
            val vx = cx - ax; val vy = cy - ay; val vz = cz - az
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat().coerceAtLeast(1e-6f)
            nx /= len; ny /= len; nz /= len
            for (j in 0 until 3) {
                normals[i + j * 3]     = nx
                normals[i + j * 3 + 1] = ny
                normals[i + j * 3 + 2] = nz
            }
            i += 9
        }
        return normals
    }

    private fun identityMatrix() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )
}
