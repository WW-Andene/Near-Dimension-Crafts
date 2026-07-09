package com.arhand.tracking

/**
 * Single hand landmark.
 * @param x          Normalized [0,1] horizontal position
 * @param y          Normalized [0,1] vertical position
 * @param z          Relative depth (MediaPipe proxy, not metric) — used by TDF
 * @param worldX     Metric X position in meters from MediaPipe world landmarks (hand-center origin).
 *                   Removes the projection distortion present in the normalized [x] coordinate.
 *                   Defaults to 0f when world landmarks are unavailable.
 * @param worldY     Metric Y position in meters from MediaPipe world landmarks (hand-center origin).
 *                   Removes the projection distortion present in the normalized [y] coordinate.
 *                   Defaults to 0f when world landmarks are unavailable.
 * @param worldZ     Metric depth in meters from MediaPipe world landmarks (hand-center origin).
 *                   More stable and geometrically correct than the normalized z proxy.
 *                   Used by landmarkToWorld() for rendering, scanning, and mocap.
 *                   Defaults to 0f when world landmarks are unavailable.
 * @param inferred   True if this landmark was reconstructed by OcclusionEngine
 * @param visibility A6: MediaPipe model-confidence score [0,1] for this landmark.
 *                   0 = model considers the point unreliable; 1 = fully confident.
 *                   Defaults to 1.0 (fully visible) when not provided.
 */
data class Landmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val worldX: Float = 0f,
    val worldY: Float = 0f,
    val worldZ: Float = 0f,
    val inferred: Boolean = false,
    val visibility: Float = 1.0f
)

/** 21-landmark hand, one entry per MediaPipe landmark index */
typealias HandLandmarks = List<Landmark>

/** Named landmark indices — matches MediaPipe HandLandmarker topology */
object LM {
    const val WRIST         = 0
    const val THUMB_CMC     = 1
    const val THUMB_MCP     = 2
    const val THUMB_IP      = 3
    const val THUMB_TIP     = 4
    const val INDEX_MCP     = 5
    const val INDEX_PIP     = 6
    const val INDEX_DIP     = 7
    const val INDEX_TIP     = 8
    const val MIDDLE_MCP    = 9
    const val MIDDLE_PIP    = 10
    const val MIDDLE_DIP    = 11
    const val MIDDLE_TIP    = 12
    const val RING_MCP      = 13
    const val RING_PIP      = 14
    const val RING_DIP      = 15
    const val RING_TIP      = 16
    const val PINKY_MCP     = 17
    const val PINKY_PIP     = 18
    const val PINKY_DIP     = 19
    const val PINKY_TIP     = 20

    val NAMES = arrayOf(
        "Wrist",
        "Thumb CMC", "Thumb MCP", "Thumb IP", "Thumb Tip",
        "Index MCP", "Index PIP", "Index DIP", "Index Tip",
        "Middle MCP", "Middle PIP", "Middle DIP", "Middle Tip",
        "Ring MCP", "Ring PIP", "Ring DIP", "Ring Tip",
        "Pinky MCP", "Pinky PIP", "Pinky DIP", "Pinky Tip"
    )

    val TIPS = setOf(THUMB_TIP, INDEX_TIP, MIDDLE_TIP, RING_TIP, PINKY_TIP)
    val KNUCKLES = setOf(INDEX_MCP, MIDDLE_MCP, RING_MCP, PINKY_MCP)
}

/** Skeleton connection pairs — mirrors CONN in the HTML prototype */
val CONNECTIONS = listOf(
    // Thumb
    0 to 1, 1 to 2, 2 to 3, 3 to 4,
    // Index
    0 to 5, 5 to 6, 6 to 7, 7 to 8,
    // Middle
    0 to 9, 9 to 10, 10 to 11, 11 to 12,
    // Ring
    0 to 13, 13 to 14, 14 to 15, 15 to 16,
    // Pinky
    0 to 17, 17 to 18, 18 to 19, 19 to 20,
    // Palm cross-connections
    5 to 9, 9 to 13, 13 to 17
)
