package com.arhand.tracking

import com.arhand.util.Vec3
import com.arhand.util.VIEW_SCALE
import kotlin.math.sqrt

/**
 * Landmark utility functions — extracted from util/MathUtils.kt to fix the
 * inverted dependency (util must not import tracking).
 *
 * Callers that previously used `com.arhand.util.lmDist` etc. now import from
 * `com.arhand.tracking.LandmarkUtils` (or use the package-level functions here).
 */

/** Distance between two landmarks in 3D space. */
fun lmDist(a: Landmark, b: Landmark): Float {
    val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/** Clamp per-frame landmark delta to prevent jumps (mirrors clampDelta() in HTML). */
fun clampDelta(prev: List<Landmark>, next: List<Landmark>, maxDelta: Float = 0.15f): List<Landmark> =
    next.mapIndexed { i, n ->
        if (i >= prev.size) n
        else {
            val dx = (n.x - prev[i].x).coerceIn(-maxDelta, maxDelta)
            val dy = (n.y - prev[i].y).coerceIn(-maxDelta, maxDelta)
            val dz = (n.z - prev[i].z).coerceIn(-maxDelta, maxDelta)
            n.copy(x = prev[i].x + dx, y = prev[i].y + dy, z = prev[i].z + dz)
        }
    }

/**
 * Convert normalised landmark coordinates to world space.
 *
 * Uses MediaPipe metric world landmarks when available (worldX/worldY non-zero),
 * otherwise falls back to screen-space reconstruction. See MathUtils.kt for the
 * full rationale — this is an identical port with the import direction fixed.
 */
fun landmarkToWorld(lm: Landmark, aspect: Float, mirrorX: Boolean, camAspect: Float = aspect): Triple<Float, Float, Float> {
    if (lm.worldX != 0f || lm.worldY != 0f) {
        val wx = if (mirrorX) -lm.worldX else lm.worldX
        return Triple(wx, -lm.worldY, -lm.worldZ)
    }
    // Fallback: image-space reconstruction anchored to center-cropped camera region.
    // scaleX/scaleY derived from the same crop parameters used by CameraPassthroughRenderer.
    val sx = if (mirrorX) 1f - lm.x else lm.x
    val scaleX = maxOf(camAspect, aspect)
    val scaleY = maxOf(1f, aspect / camAspect)
    val wx = (sx - 0.5f) * 2f * scaleX
    val wy = -(lm.y - 0.5f) * 2f * scaleY
    val wz = if (lm.worldZ != 0f) -lm.worldZ else lm.z * -0.6f
    return Triple(wx, wy, wz)
}

/** Convert HandLandmarks to a list of [Vec3] in world space. */
fun HandLandmarks.toWorldVec3List(aspect: Float, mirrorX: Boolean = false): List<Vec3> =
    map { lm ->
        val (wx, wy, wz) = landmarkToWorld(lm, aspect, mirrorX)
        Vec3(wx, wy, wz)
    }
