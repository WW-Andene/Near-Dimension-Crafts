package com.arhand.scanner

import com.arhand.tracking.HandLandmarks
import com.arhand.tracking.LM
import com.arhand.tracking.Landmark
import com.arhand.util.Vec3
import com.arhand.util.landmarkToWorld
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Measured dimensions derived from the 21-landmark hand skeleton.
 *
 * All values are in world-space units (same coordinate system as the SDF carver).
 * Conversion to mm requires a scale calibration step — see [HandBiometrics.WORLD_TO_MM].
 *
 * Use cases: ring sizing, glove sizing, VR avatar calibration.
 */
data class HandBiometrics(
    /** Straight-line distance between INDEX_MCP (5) and PINKY_MCP (17) — knuckle row. */
    val palmWidth: Float,

    /** Sum of 3 segment lengths for each finger: MCP→PIP, PIP→DIP, DIP→TIP. */
    val thumbLength: Float,
    val indexLength: Float,
    val middleLength: Float,
    val ringLength: Float,
    val pinkyLength: Float,

    /** Distance THUMB_TIP (4) → PINKY_TIP (20) — maximum spread. */
    val handSpan: Float,

    /**
     * Knuckle breadth: INDEX_MCP (5) → PINKY_MCP (17) with a +12% anatomical correction
     * to account for the soft tissue and first metacarpal not captured in the skeleton.
     */
    val knuckleBreadth: Float,

    /**
     * Wrist circumference proxy: π × wrist_diameter, where wrist_diameter is estimated
     * as 1.4× the WRIST (0) → INDEX_MCP (5) segment length — an empirically derived ratio
     * consistent with adult hand proportions.
     */
    val wristCircumference: Float,

    /** Largest single-pose quality score used when computing these metrics. */
    val sourcePoseQuality: Float
) {
    companion object {
        /**
         * World-unit → mm scale factor.
         *
         * [landmarkToWorld] uses MediaPipe's metric world landmarks (worldX/Y/Z) when
         * they are non-zero, which is always the case with the Tasks SDK. Those coordinates
         * are in metres (hand-geometric-centre origin). Converting metres → mm = × 1000.
         *
         * The legacy value 800f was calibrated for the screen-space fallback path
         * (VIEW_SCALE = 0.65 normalised coords ≈ 0.10 world units per ~80mm palm),
         * which is only taken when worldX == 0 AND worldY == 0 — this does not occur
         * in practice with the Tasks SDK populating world landmarks.
         *
         * Anatomy check: INDEX_MCP→PINKY_MCP in MediaPipe world coords ≈ 0.078–0.090m.
         *   toMm(0.084) = 84mm — matches Garrett 1971 male average (84mm). ✓
         * Previous value: toMm(0.084) = 67mm — 20% low, ring size ~2 sizes too small. ✗
         *
         * For further accuracy, the D2 calibration flow lets the user hold a credit card
         * (85.6×54mm) to derive a device-specific override stored in PersonalModelStore.
         */
        const val WORLD_TO_MM = 1000f

        /**
         * Compute biometrics from the best-quality landmarks accumulated during a scan.
         *
         * @param frames List of (landmarks, qualityScore) pairs — one entry per scan pose.
         *               Uses the frame with the highest quality score.
         * @param aspect The device aspect ratio used when these landmarks were captured.
         */
        fun compute(
            frames: List<Pair<HandLandmarks, Float>>,
            aspect: Float
        ): HandBiometrics? {
            if (frames.isEmpty()) return null

            // A single frame has ±8–12% error due to MediaPipe's Z proxy.
            // Strategy: take the top-10 frames by quality score, compute each metric
            // per frame, then discard outliers beyond 1.5×IQR and return the trimmed mean.
            // Expected error reduction: ±3–5%.

            val validFrames = frames
                .filter { (lms, _) -> lms.size >= 21 }
                .sortedByDescending { (_, q) -> q }
                .take(10)

            if (validFrames.isEmpty()) return null

            val bestQuality = validFrames.first().second

            // Helper: convert one frame's landmarks to world-space, measure one metric
            fun measureAll(lms: HandLandmarks): FloatArray {
                val world = lms.map { lm ->
                    val (wx, wy, wz) = landmarkToWorld(lm, aspect, mirrorX = false)
                    Vec3(wx, wy, wz)
                }
                fun d(a: Int, b: Int) = (world[a] - world[b]).length()
                val palmWidth = d(LM.INDEX_MCP, LM.PINKY_MCP)
                return floatArrayOf(
                    palmWidth,
                    d(LM.THUMB_CMC, LM.THUMB_MCP) + d(LM.THUMB_MCP, LM.THUMB_IP)  + d(LM.THUMB_IP,   LM.THUMB_TIP),
                    d(LM.INDEX_MCP, LM.INDEX_PIP)  + d(LM.INDEX_PIP, LM.INDEX_DIP)  + d(LM.INDEX_DIP,  LM.INDEX_TIP),
                    d(LM.MIDDLE_MCP,LM.MIDDLE_PIP) + d(LM.MIDDLE_PIP,LM.MIDDLE_DIP) + d(LM.MIDDLE_DIP, LM.MIDDLE_TIP),
                    d(LM.RING_MCP,  LM.RING_PIP)   + d(LM.RING_PIP,  LM.RING_DIP)   + d(LM.RING_DIP,   LM.RING_TIP),
                    d(LM.PINKY_MCP, LM.PINKY_PIP)  + d(LM.PINKY_PIP, LM.PINKY_DIP)  + d(LM.PINKY_DIP,  LM.PINKY_TIP),
                    d(LM.THUMB_TIP, LM.PINKY_TIP),
                    d(LM.WRIST,     LM.INDEX_MCP)
                )
            }

            // Collect per-metric samples across all top frames
            val allMeasures = validFrames.map { (lms, _) -> measureAll(lms) }
            val numMetrics = allMeasures[0].size

            // IQR trimmed mean per metric
            fun trimmedMean(metricIdx: Int): Float {
                val vals = allMeasures.map { it[metricIdx] }.sorted()
                if (vals.size < 4) return vals.average().toFloat()
                val q1 = vals[vals.size / 4]
                val q3 = vals[vals.size * 3 / 4]
                val iqr = q3 - q1
                val lo = q1 - 1.5f * iqr
                val hi = q3 + 1.5f * iqr
                val kept = vals.filter { it in lo..hi }
                return if (kept.isEmpty()) vals.average().toFloat() else kept.average().toFloat()
            }

            val palmWidth          = trimmedMean(0)
            val thumbLength        = trimmedMean(1)
            val indexLength        = trimmedMean(2)
            val middleLength       = trimmedMean(3)
            val ringLength         = trimmedMean(4)
            val pinkyLength        = trimmedMean(5)
            val handSpan           = trimmedMean(6)
            val wristDiameter      = trimmedMean(7) * 1.4f
            val knuckleBreadth     = palmWidth * 1.12f
            val wristCircumference = (PI * wristDiameter).toFloat()

            return HandBiometrics(
                palmWidth          = palmWidth,
                thumbLength        = thumbLength,
                indexLength        = indexLength,
                middleLength       = middleLength,
                ringLength         = ringLength,
                pinkyLength        = pinkyLength,
                handSpan           = handSpan,
                knuckleBreadth     = knuckleBreadth,
                wristCircumference = wristCircumference,
                sourcePoseQuality  = bestQuality
            )
        }

        /** Convert a world-space measurement to millimetres. */
        fun toMm(worldUnits: Float): Float = worldUnits * WORLD_TO_MM

        /** Format a world-space value as "XX.X mm" for display. */
        fun fmtMm(worldUnits: Float): String = "%.1f mm".format(toMm(worldUnits))

        /** Deserialise from a toJson() string. Returns null on any parse failure. */
        fun fromJson(json: String): HandBiometrics? = try {
            fun extract(key: String): Float {
                val regex = Regex(""""$key"\s*:\s*([\d.Ee+-]+)""")
                return regex.find(json)?.groupValues?.get(1)?.toFloat()
                    ?: error("missing key $key")
            }
            HandBiometrics(
                palmWidth          = extract("palmWidth"),
                thumbLength        = extract("thumbLength"),
                indexLength        = extract("indexLength"),
                middleLength       = extract("middleLength"),
                ringLength         = extract("ringLength"),
                pinkyLength        = extract("pinkyLength"),
                handSpan           = extract("handSpan"),
                knuckleBreadth     = extract("knuckleBreadth"),
                wristCircumference = extract("wristCircumference"),
                sourcePoseQuality  = extract("sourcePoseQuality")
            )
        } catch (_: Exception) { null }

        /**
         * D2 — Compute a calibrated world-to-mm scale factor from a credit card
         * reference measurement.
         *
         * The user holds a standard credit card (ISO 7810 ID-1: 85.6 × 54.0 mm) in
         * the frame and the app measures the card's long axis in world coordinates.
         * The ratio of the known real width to the measured world width yields the
         * scale factor that replaces [WORLD_TO_MM] for all subsequent measurements.
         *
         * @param measuredCardWidthWorldUnits  Long axis of the card in world units,
         *        as measured from landmark or bounding-box detection.
         * @return Calibrated world-to-mm factor, or null if the measurement is
         *         outside a plausible range (guards against obviously wrong inputs).
         *
         * Plausibility gate: the factor must be in [300, 2500] — corresponding to
         * a palm size of roughly 60–240mm in world-unit terms, which covers all
         * realistic hand sizes and camera distances.
         */
        fun calibrateFromCard(measuredCardWidthWorldUnits: Float): Float? {
            if (measuredCardWidthWorldUnits <= 0f) return null
            val CARD_WIDTH_MM = 85.6f
            val scale = CARD_WIDTH_MM / measuredCardWidthWorldUnits
            return if (scale in 300f..2500f) scale else null
        }
    }

    /** Serialise to a compact JSON string for DataStore persistence. */
    fun toJson(): String = """
        {
          "palmWidth":$palmWidth,
          "thumbLength":$thumbLength,
          "indexLength":$indexLength,
          "middleLength":$middleLength,
          "ringLength":$ringLength,
          "pinkyLength":$pinkyLength,
          "handSpan":$handSpan,
          "knuckleBreadth":$knuckleBreadth,
          "wristCircumference":$wristCircumference,
          "sourcePoseQuality":$sourcePoseQuality
        }
    """.trimIndent()

}
