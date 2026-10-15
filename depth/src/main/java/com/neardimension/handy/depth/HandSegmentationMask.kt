package com.arhand.depth

import com.arhand.tracking.HandLandmarks
import com.arhand.util.Vec3

/**
 * B7 — Hand segmentation mask for cleaner point cloud.
 *
 * Cheap alternative to MediaPipe Selfie Segmentation: builds the 2D convex hull of
 * the 21 landmarks and discards point-cloud points whose XY projection falls outside
 * that hull.
 *
 * Used to filter the raw ARCore/SfM depth point cloud ([com.arhand.util.PointCloudStore.snapshot])
 * before it's handed to [DepthCarver] / TSDF integration, removing background points (desk,
 * wall, other hand) that are not part of the scanned hand.
 *
 * ENGINE_ARCHITECTURE.md §4.11 — the hull used to be built from
 * [com.arhand.tracking.landmarkToWorld]'s output (MediaPipe world landmarks, hand-centred
 * origin) and compared directly against the point cloud (ARCore-world-anchored coordinates)
 * — two different coordinate frames, so the comparison was meaningless. [buildHullMetric]
 * fixes this by unprojecting each landmark's own 2D pixel + a real measured depth into the
 * *same* ARCore world frame the cloud is already in, via [SpatialLayer.unprojectLandmarkToWorld].
 * The old `landmarkToWorld`-based `buildHull` is gone — nothing else in the codebase used it
 * for this purpose (verified by repo-wide grep before removal), so there's no reason to keep
 * the broken version around alongside the correct one.
 */
object HandSegmentationMask {

    /**
     * Outward expansion of the hull, in world-space units, to avoid clipping
     * fingertip/wrist depth points that sit just outside the landmark hull
     * (landmarks are skeleton centerlines, not the hand's outer silhouette).
     */
    const val HULL_MARGIN = 0.06f

    /**
     * Build the 2D convex hull (ARCore world-space XY) of a hand's 21 landmarks, using
     * real per-landmark depth rather than MediaPipe's hand-centred world landmarks.
     *
     * @param unproject Typically [SpatialLayer.unprojectLandmarkToWorld]. Takes a landmark's
     *                  normalised (x, y) and returns its ARCore-world position, or null when
     *                  metric depth isn't currently available for that pixel.
     * @return hull vertices in counter-clockwise order, or null if metric depth wasn't
     *         available for at least 3 landmarks (caller should skip filtering that frame
     *         rather than fall back to a hull in the wrong coordinate frame).
     */
    fun buildHullMetric(lms: HandLandmarks, unproject: (Float, Float) -> Vec3?): List<Pair<Float, Float>>? {
        if (lms.size < 3) return null
        val pts = ArrayList<Pair<Float, Float>>(lms.size)
        for (lm in lms) {
            val p = unproject(lm.x, lm.y) ?: return null
            pts.add(p.x to p.y)
        }
        return convexHull(pts)
    }

    /**
     * Filter [points] (world-space) to only those whose XY projection lies inside
     * [hull] expanded outward by [HULL_MARGIN].
     *
     * @param hull convex hull as returned by [buildHullMetric]; if empty (degenerate hand
     *             pose), all points are kept (no filtering).
     */
    fun filterPointCloud(points: List<Vec3>, hull: List<Pair<Float, Float>>): List<Vec3> {
        if (hull.size < 3) return points
        val expanded = expandHull(hull, HULL_MARGIN)
        return points.filter { p -> pointInPolygon(p.x, p.y, expanded) }
    }

    /**
     * Andrew's monotone chain convex hull, O(n log n).
     * Returns vertices in counter-clockwise order with no duplicate endpoints.
     */
    private fun convexHull(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val sorted = points.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (sorted.size < 3) return sorted

        fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)

        val lower = ArrayList<Pair<Float, Float>>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }

        val upper = ArrayList<Pair<Float, Float>>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }

        // Concatenate, dropping the last point of each (it's the first point of the other)
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /**
     * Expand a convex polygon outward by [margin] along each vertex's outward
     * (away-from-centroid) direction. Approximate but sufficient for a soft
     * foreground/background cutoff.
     */
    private fun expandHull(hull: List<Pair<Float, Float>>, margin: Float): List<Pair<Float, Float>> {
        if (margin <= 0f) return hull
        var cx = 0f; var cy = 0f
        for ((x, y) in hull) { cx += x; cy += y }
        cx /= hull.size; cy /= hull.size

        return hull.map { (x, y) ->
            val dx = x - cx; val dy = y - cy
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            if (len < 1e-6f) x to y
            else (x + dx / len * margin) to (y + dy / len * margin)
        }
    }

    /** Standard ray-casting point-in-polygon test. */
    private fun pointInPolygon(px: Float, py: Float, poly: List<Pair<Float, Float>>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, yi) = poly[i]
            val (xj, yj) = poly[j]
            if ((yi > py) != (yj > py) &&
                px < (xj - xi) * (py - yi) / (yj - yi) + xi
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
