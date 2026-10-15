package com.arhand.scanner

/**
 * 8 scan poses — direct port of SCANNER.POSES from the HTML prototype.
 * Each pose defines the instruction shown to the user and whether it
 * requires a motion (repetition detection) or a held position.
 */
enum class PoseType { STATIC, MOTION }

data class ScanPose(
    val id: String,
    val label: String,
    val instruction: String,
    val type: PoseType,
    val drawableRes: Int = 0,       // set at runtime via Resources.getIdentifier
    val holdMs: Int = 1200,         // ms to hold for STATIC
    val reps: Int = 3,              // repetitions needed for MOTION
    val motionKey: String = ""      // "fingerCurl" | "wristRoll" — evaluated by QualityEngine
)

object ScanPoses {
    val ALL = listOf(
        ScanPose(
            id = "palm_open",
            label = "Palm Open",
            instruction = "Hold palm flat, facing camera",
            type = PoseType.STATIC,
            holdMs = 1200
        ),
        ScanPose(
            id = "fingers_spread",
            label = "Fingers Spread",
            instruction = "Spread fingers wide apart",
            type = PoseType.STATIC,
            holdMs = 1200
        ),
        ScanPose(
            id = "open_close",
            label = "Open & Close",
            instruction = "Open and close your hand 3×",
            type = PoseType.MOTION,
            reps = 3,
            motionKey = "fingerCurl"
        ),
        ScanPose(
            id = "finger_wave",
            label = "Finger Wave",
            instruction = "Wave fingers up and down",
            type = PoseType.MOTION,
            reps = 3,
            motionKey = "fingerWave"
        ),
        ScanPose(
            id = "wrist_rotate",
            label = "Wrist Rotate",
            instruction = "Rotate wrist left and right 3×",
            type = PoseType.MOTION,
            reps = 3,
            motionKey = "wristRoll"
        ),
        ScanPose(
            id = "thumb_wave",
            label = "Thumb Wave",
            instruction = "Move thumb in and out 3×",
            type = PoseType.MOTION,
            reps = 3,
            motionKey = "thumbWave"
        ),
        ScanPose(
            id = "side_view",
            label = "Side View",
            instruction = "Turn hand to show side profile",
            type = PoseType.STATIC,
            holdMs = 1500
        ),
        ScanPose(
            id = "fist",
            label = "Closed Fist",
            instruction = "Make a tight fist",
            type = PoseType.STATIC,
            holdMs = 1200
        )
    )

    val COUNT = ALL.size
}
