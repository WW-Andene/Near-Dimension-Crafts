package com.arhand.depth

import com.arhand.tracking.HandLandmarks
import com.arhand.tracking.landmarkToWorld
import com.arhand.util.Vec3

/**
 * B7 — Hand segmentation mask for cleaner point cloud.
 *
 * Cheap alternative to MediaPipe Selfie Segmentation: builds the 2D convex hull of
 * the 21 landmarks (projected to world-space XY, matching [landmarkToWorld]) and
 * discards point-cloud points whose XY projection falls outside that hull.
 *
 * Used to filter the raw [ArCoreDepthSource] depth point cloud before it's handed to
 * [DepthApiCarver] / [Scanner.cloudPoints], removing background points (desk, wall,
 * other hand) that are not part of the scanned hand.
 */
object HandSegmentationMask {

    /**
     * Outward expansion of the hull, in world-space units, to avoid clipping
     * fingertip/wrist depth points that sit just outside the landmark hull
     * (landmarks are skeleton centerlines, not the hand's outer silhouette).
     */
    const val HULL_MARGIN = 0.06f

    /**
     * Build the 2D convex hull (world-space XY) of a hand's 21 landmarks.
     *
     * @return hull vertices in counter-clockwise order, or empty if fewer than 3
     *         distinct points are available.
     */
    fun buildHull(lms: HandLandmarks, aspect: Float, mirrorX: Boolean, camAspect: Float = aspect): List<Pair<Float, Float>> {
        if (lms.size < 3) return emptyList()
        val pts = lms.map { lm ->
            val (wx, wy, _) = landmarkToWorld(lm, aspect, mirrorX, camAspect)
            wx to wy
        }
        return convexHull(pts)
    }

    /**
     * Filter [points] (world-space) to only those whose XY projection lies inside
     * [hull] expanded outward by [HULL_MARGIN].
     *
     * @param hull convex hull as returned by [buildHull]; if empty (degenerate hand
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
