package com.arhand.render

import com.arhand.tracking.HandLandmarks
import com.arhand.util.Vec3
import com.arhand.tracking.landmarkToWorld
import kotlin.math.*

/**
 * Procedural hand mesh geometry builder.
 * Port of buildTube(), buildPalmGeo(), buildWebbingGeo(), buildNailGeo() from the HTML prototype.
 *
 * All algorithms are 1:1 ports — same parallel transport frames, same knuckle bulge profiles,
 * same palm fan triangulation, same webbing arch sinus curve.
 */
object HandMeshBuilder {

    // Finger radius profiles — same constants as HTML FINGER_PROFILES
    data class FingerProfile(
        val base: Float,
        val mid: Float,
        val tip: Float,
        val knuckleMult: Float = 1.15f
    )

    val FINGER_PROFILES = arrayOf(
        FingerProfile(0.028f, 0.022f, 0.016f), // Thumb
        FingerProfile(0.025f, 0.020f, 0.015f), // Index
        FingerProfile(0.026f, 0.021f, 0.016f), // Middle
        FingerProfile(0.024f, 0.019f, 0.015f), // Ring
        FingerProfile(0.020f, 0.016f, 0.013f)  // Pinky
    )

    val FINGER_CHAINS = arrayOf(
        intArrayOf(1, 2, 3, 4),    // Thumb  (skip wrist)
        intArrayOf(5, 6, 7, 8),    // Index
        intArrayOf(9, 10, 11, 12), // Middle
        intArrayOf(13, 14, 15, 16),// Ring
        intArrayOf(17, 18, 19, 20) // Pinky
    )

    data class MeshData(
        val positions: FloatArray,
        val normals: FloatArray,
        val indices: IntArray
    )

    // ─── buildTube ────────────────────────────────────────────────────────
    /**
     * Builds a tube geometry along a polyline with varying radius.
     * Uses parallel transport frames (same as HTML prototype).
     * @param pts        List of Vec3 control points
     * @param radii      Radius at each control point
     * @param radialSegs Number of sides (8–12 typical)
     */
    fun buildTube(pts: List<Vec3>, radii: List<Float>, radialSegs: Int = 10): MeshData {
        val n = pts.size
        if (n < 2) return MeshData(FloatArray(0), FloatArray(0), IntArray(0))

        // Compute tangents
        val tangents = Array(n) { Vec3(0f, 0f, 0f) }
        for (i in 0 until n) {
            tangents[i] = when (i) {
                0    -> (pts[1] - pts[0]).normalized()
                n-1  -> (pts[n-1] - pts[n-2]).normalized()
                else -> (pts[i+1] - pts[i-1]).normalized()
            }
        }

        // Parallel transport frame
        val normals2 = Array(n) { Vec3(0f, 0f, 0f) }
        val binormals = Array(n) { Vec3(0f, 0f, 0f) }

        // Initial frame
        var initN = Vec3(0f, 1f, 0f)
        if (abs(tangents[0].dot(initN)) > 0.99f) initN = Vec3(1f, 0f, 0f)
        normals2[0] = tangents[0].cross(initN).normalized()
        binormals[0] = tangents[0].cross(normals2[0]).normalized()

        for (i in 1 until n) {
            val b = tangents[i-1].cross(tangents[i])
            if (b.length() < 1e-6f) {
                normals2[i] = normals2[i-1]
            } else {
                val bN = b.normalized()
                val angle = acos(tangents[i-1].dot(tangents[i]).coerceIn(-1f, 1f))
                // Rodrigues rotation
                val c = cos(angle); val s = sin(angle)
                val n2 = normals2[i-1]
                normals2[i] = Vec3(
                    n2.x * c + (bN.y * n2.z - bN.z * n2.y) * s + bN.x * (bN.dot(n2)) * (1 - c),
                    n2.y * c + (bN.z * n2.x - bN.x * n2.z) * s + bN.y * (bN.dot(n2)) * (1 - c),
                    n2.z * c + (bN.x * n2.y - bN.y * n2.x) * s + bN.z * (bN.dot(n2)) * (1 - c)
                ).normalized()
            }
            binormals[i] = tangents[i].cross(normals2[i]).normalized()
        }

        val positions = mutableListOf<Float>()
        val normsOut  = mutableListOf<Float>()
        val indices   = mutableListOf<Int>()

        val segs = radialSegs
        for (i in 0 until n) {
            val r = radii[i]
            for (j in 0..segs) {
                val angle = (j.toFloat() / segs) * 2f * PI.toFloat()
                val c = cos(angle); val s = sin(angle)
                val nx = normals2[i].x * c + binormals[i].x * s
                val ny = normals2[i].y * c + binormals[i].y * s
                val nz = normals2[i].z * c + binormals[i].z * s
                positions.add(pts[i].x + nx * r)
                positions.add(pts[i].y + ny * r)
                positions.add(pts[i].z + nz * r)
                normsOut.add(nx); normsOut.add(ny); normsOut.add(nz)
            }
        }

        val stride = segs + 1
        for (i in 0 until n - 1) {
            for (j in 0 until segs) {
                val a = i * stride + j
                val b = a + 1
                val c = (i + 1) * stride + j
                val d = c + 1
                indices.add(a); indices.add(b); indices.add(c)
                indices.add(b); indices.add(d); indices.add(c)
            }
        }

        return MeshData(positions.toFloatArray(), normsOut.toFloatArray(), indices.toIntArray())
    }

    // ─── addKnuckleBulge ─────────────────────────────────────────────────
    fun addKnuckleBulge(radii: FloatArray, knuckleMult: Float = 1.15f): FloatArray {
        val out = radii.copyOf()
        if (out.size >= 3) out[1] = out[1] * knuckleMult
        return out
    }

    // ─── buildNailGeo ────────────────────────────────────────────────────
    fun buildNailGeo(unitR: Float): MeshData {
        val r = unitR * 0.9f
        val positions = mutableListOf<Float>()
        val normals   = mutableListOf<Float>()
        val indices   = mutableListOf<Int>()

        val rows = 8; val cols = 12
        for (i in 0..rows) {
            val phi = (i.toFloat() / rows) * PI.toFloat() * 0.5f
            for (j in 0..cols) {
                val theta = (j.toFloat() / cols) * PI.toFloat()
                val x = r * sin(phi) * cos(theta) * 1.2f
                val y = r * cos(phi)
                val z = r * sin(phi) * sin(theta) * 0.6f
                positions.add(x); positions.add(y); positions.add(z)
                val len = sqrt(x*x + y*y + z*z).coerceAtLeast(1e-6f)
                normals.add(x/len); normals.add(y/len); normals.add(z/len)
            }
        }
        val stride = cols + 1
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                val a = i * stride + j
                val b = a + 1
                val c = (i + 1) * stride + j
                val d = c + 1
                indices.add(a); indices.add(c); indices.add(b)
                indices.add(b); indices.add(c); indices.add(d)
            }
        }
        return MeshData(positions.toFloatArray(), normals.toFloatArray(), indices.toIntArray())
    }

    // ─── buildPalmGeo ────────────────────────────────────────────────────
    fun buildPalmGeo(pts: List<Vec3>, unitR: Float): MeshData {
        // Fan triangulation from wrist (index 0) through palm landmarks
        // Mirrors buildPalmGeo() in the HTML prototype
        val positions = mutableListOf<Float>()
        val normals   = mutableListOf<Float>()
        val indices   = mutableListOf<Int>()

        fun addV(v: Vec3, n: Vec3) {
            positions.add(v.x); positions.add(v.y); positions.add(v.z)
            normals.add(n.x);   normals.add(n.y);   normals.add(n.z)
        }
        fun tri(a: Int, b: Int, c: Int) {
            indices.add(a); indices.add(b); indices.add(c)
        }

        if (pts.size < 21) return MeshData(FloatArray(0), FloatArray(0), IntArray(0))

        // Palm plane normal via cross product of wrist→index, wrist→pinky
        val w  = pts[0]
        val i5 = pts[5]; val i17 = pts[17]
        val v1 = i5 - w; val v2 = i17 - w
        val palmN = v1.cross(v2).normalized()

        val palmIndices = intArrayOf(0, 5, 9, 13, 17, 0)
        val base = 0
        for (i in 0 until palmIndices.size - 1) {
            val a = pts[palmIndices[i]]
            val b = pts[palmIndices[i + 1]]
            val idx = positions.size / 3
            addV(w, palmN); addV(a, palmN); addV(b, palmN)
            tri(idx, idx + 1, idx + 2)
        }

        // Wrist extension
        val wristExt = w + Vec3(0f, -unitR * 2f, 0f)
        val we = positions.size / 3
        addV(w, palmN); addV(pts[5], palmN); addV(pts[17], palmN); addV(wristExt, palmN)
        tri(we, we + 3, we + 1); tri(we + 1, we + 3, we + 2)

        return MeshData(positions.toFloatArray(), normals.toFloatArray(), indices.toIntArray())
    }

    // ─── buildWebbingGeo ─────────────────────────────────────────────────
    fun buildWebbingGeo(pts: List<Vec3>, fingerIdx: Int): MeshData {
        // Webbing between adjacent fingers using sinus arch curve
        // Mirrors buildWebbingGeo() from HTML prototype
        val webPairs = arrayOf(
            intArrayOf(5, 9),   // index–middle
            intArrayOf(9, 13),  // middle–ring
            intArrayOf(13, 17)  // ring–pinky
        )
        if (fingerIdx >= webPairs.size) return MeshData(FloatArray(0), FloatArray(0), IntArray(0))

        val pair = webPairs[fingerIdx]
        val pA0 = pts[pair[0]]         // finger A MCP
        val pB0 = pts[pair[1]]         // finger B MCP
        val pA1 = pts[pair[0] + 1]     // finger A PIP
        val pB1 = pts[pair[1] + 1]     // finger B PIP

        val webDepth = 0.32f
        val steps = 8
        val positions = mutableListOf<Float>()
        val normals   = mutableListOf<Float>()
        val indices   = mutableListOf<Int>()

        fun addV(v: Vec3, n: Vec3) {
            positions.add(v.x); positions.add(v.y); positions.add(v.z)
            normals.add(n.x);   normals.add(n.y);   normals.add(n.z)
        }

        val up = Vec3(0f, 0f, 1f)

        for (ti in 0..steps) {
            val tv = ti.toFloat() / steps
            val arch = sin(tv * PI.toFloat()) * webDepth * 0.3f

            val lA = pA0.lerp(pA0.lerp(pA1, 1f - webDepth), tv)
            val lB = pB0.lerp(pB0.lerp(pB1, 1f - webDepth), tv)
            val mid = lA.lerp(lB, 0.5f) + Vec3(0f, -arch, 0f)

            val n = (lB - lA).cross(up).normalized()
            addV(lA, n); addV(mid, n); addV(lB, n)
        }

        val stride = 3
        for (i in 0 until steps) {
            val base = i * stride
            val next = base + stride
            indices.add(base); indices.add(next); indices.add(base + 1)
            indices.add(base + 1); indices.add(next); indices.add(next + 1)
            indices.add(base + 1); indices.add(next + 1); indices.add(base + 2)
            indices.add(base + 2); indices.add(next + 1); indices.add(next + 2)
        }

        return MeshData(positions.toFloatArray(), normals.toFloatArray(), indices.toIntArray())
    }
}
