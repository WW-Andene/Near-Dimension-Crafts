package com.arhand.mocap

import com.arhand.tracking.FaceExpressions
import org.json.JSONObject

/**
 * FACE-1 — Parses VRM blend shape groups from a loaded GLB and maps
 * [FaceExpressions] values to morph target indices on the loaded mesh.
 *
 * VRM 0.x format stores blend shape data in:
 *   `extensions.VRM.blendShapeMaster.blendShapeGroups[]`
 * Each group has a `presetName` and a `binds[]` array of mesh+index+weight triples.
 *
 * This parser builds a [BlendShapeMap] that the renderer can use to apply
 * expression-driven vertex deformation at runtime.
 *
 * ## ARKit → VRM preset name mapping
 *
 * | ARKit name          | VRM presetName |
 * |---------------------|----------------|
 * | eyeBlinkLeft        | blink_l        |
 * | eyeBlinkRight       | blink_r        |
 * | jawOpen             | o              |
 * | mouthSmile          | joy            |
 * | browInnerUp         | angry (closest)|
 *
 * ## Thread safety
 * [parse] is called once on the IO thread during asset load. The returned
 * [BlendShapeMap] is immutable and safe to read from any thread.
 */
object VrmBlendShapeParser {

    /** ARKit expression field name → VRM presetName (lowercase). */
    private val ARKIT_TO_VRM = mapOf(
        "leftBlink"      to "blink_l",
        "rightBlink"     to "blink_r",
        "jawOpen"        to "o",
        "mouthSmile"     to "joy",
        "leftBrowRaise"  to "angry",
        "rightBrowRaise" to "angry"
    )

    /**
     * Parse the VRM extension from [glbJson] and build a [BlendShapeMap].
     *
     * Returns null if the JSON has no VRM extension, no blendShapeMaster,
     * or no recognisable blend shape groups — so callers can fall back to
     * expression-only OSC streaming gracefully.
     */
    fun parse(glbJson: JSONObject): BlendShapeMap? {
        val vrmExt = glbJson
            .optJSONObject("extensions")
            ?.optJSONObject("VRM") ?: return null

        val groups = vrmExt
            .optJSONObject("blendShapeMaster")
            ?.optJSONArray("blendShapeGroups") ?: return null

        val map = mutableMapOf<String, List<MorphBinding>>()

        for (i in 0 until groups.length()) {
            val group = groups.getJSONObject(i)
            val preset = group.optString("presetName", "").lowercase()
            if (preset.isEmpty()) continue

            val binds = group.optJSONArray("binds") ?: continue
            val bindings = mutableListOf<MorphBinding>()

            for (b in 0 until binds.length()) {
                val bind = binds.getJSONObject(b)
                val meshIdx    = bind.optInt("mesh", -1)
                val targetIdx  = bind.optInt("index", -1)
                val weight     = bind.optDouble("weight", 100.0).toFloat() / 100f  // VRM: 0–100
                if (meshIdx < 0 || targetIdx < 0) continue
                bindings.add(MorphBinding(meshIdx, targetIdx, weight))
            }

            if (bindings.isNotEmpty()) {
                map[preset] = bindings
            }
        }

        if (map.isEmpty()) return null
        return BlendShapeMap(map)
    }

    /**
     * Convert [FaceExpressions] to a list of morph target applications
     * using the given [BlendShapeMap].
     *
     * Returns a list of [MorphApplication]s — one per affected mesh×target pair.
     * The caller (renderer) applies these weights to its morph target buffers.
     */
    fun resolve(fe: FaceExpressions, map: BlendShapeMap): List<MorphApplication> {
        val result = mutableListOf<MorphApplication>()

        fun apply(arkitField: String, value: Float) {
            val vrmPreset = ARKIT_TO_VRM[arkitField] ?: return
            val bindings  = map.groups[vrmPreset] ?: return
            for (binding in bindings) {
                result.add(MorphApplication(
                    meshIndex        = binding.meshIndex,
                    morphTargetIndex = binding.morphTargetIndex,
                    weight           = value * binding.weight
                ))
            }
        }

        apply("leftBlink",      fe.leftBlink)
        apply("rightBlink",     fe.rightBlink)
        apply("jawOpen",        fe.jawOpen)
        apply("mouthSmile",     fe.mouthSmile)
        apply("leftBrowRaise",  fe.leftBrowRaise)
        apply("rightBrowRaise", fe.rightBrowRaise)

        return result
    }
}

// ─── Data classes ─────────────────────────────────────────────────────────────

/**
 * Parsed VRM blend shape map: VRM preset name (lowercase) → list of mesh bindings.
 * Immutable after construction.
 */
data class BlendShapeMap(
    val groups: Map<String, List<MorphBinding>>
)

/**
 * One mesh × morph target binding from a VRM blend shape group.
 *
 * @param meshIndex         glTF mesh index this binding applies to.
 * @param morphTargetIndex  Index into the mesh's morph target array.
 * @param weight            Normalised weight multiplier (0–1). VRM stores 0–100; parser divides.
 */
data class MorphBinding(
    val meshIndex:        Int,
    val morphTargetIndex: Int,
    val weight:           Float
)

/**
 * A resolved morph target application for a single frame.
 *
 * Produced by [VrmBlendShapeParser.resolve]; consumed by [com.arhand.render.SkinnedMeshRenderer]
 * via its [com.arhand.render.SkinnedMeshRenderer.applyMorphWeights] method.
 */
data class MorphApplication(
    val meshIndex:        Int,
    val morphTargetIndex: Int,
    val weight:           Float
)
