package com.neardimension.handy.mocap

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

// ─── Humanoid bones ──────────────────────────────────────────────────────────

/**
 * Humanoid bones the body skinning pipeline understands — a superset of [BodyJoint]
 * (adds shoulders, hands, toes and upper chest) used to classify a rig's joints and to
 * measure its rest pose. Fingers are deliberately absent: they ride rigidly on the hand.
 */
enum class HumanBone(val bodyJoint: BodyJoint? = null) {
    HIPS(BodyJoint.HIPS), SPINE(BodyJoint.SPINE), CHEST(BodyJoint.CHEST), UPPER_CHEST,
    NECK(BodyJoint.NECK), HEAD(BodyJoint.HEAD),
    LEFT_SHOULDER, LEFT_UPPER_ARM(BodyJoint.LEFT_UPPER_ARM), LEFT_LOWER_ARM(BodyJoint.LEFT_LOWER_ARM), LEFT_HAND,
    RIGHT_SHOULDER, RIGHT_UPPER_ARM(BodyJoint.RIGHT_UPPER_ARM), RIGHT_LOWER_ARM(BodyJoint.RIGHT_LOWER_ARM), RIGHT_HAND,
    LEFT_UPPER_LEG(BodyJoint.LEFT_UPPER_LEG), LEFT_LOWER_LEG(BodyJoint.LEFT_LOWER_LEG), LEFT_FOOT(BodyJoint.LEFT_FOOT), LEFT_TOES,
    RIGHT_UPPER_LEG(BodyJoint.RIGHT_UPPER_LEG), RIGHT_LOWER_LEG(BodyJoint.RIGHT_LOWER_LEG), RIGHT_FOOT(BodyJoint.RIGHT_FOOT), RIGHT_TOES
}

/**
 * Classifies a rig joint name as a [HumanBone], across the common naming schemes:
 * Unity/VRM 1.0 (`LeftUpperArm`, `leftUpperArm`), VRM 0.x (`J_Bip_L_UpperArm`),
 * Mixamo (`mixamorig:LeftForeArm`), 3ds Max Biped (`Bip001 L UpperArm`), Unreal
 * (`upperarm_l`, `calf_r`), plus the `_123` suffix Sketchfab appends to every node.
 *
 * Unknown names (skirt, hair, fingers, roots, IK helpers…) return null — on purpose:
 * `Root` is *not* the hips, it is usually a ground-level parent of the whole rig.
 */
object HumanBoneMapper {

    private enum class Side { CENTER, LEFT, RIGHT }

    private val CENTER: Map<String, HumanBone> = mapOf(
        "hips" to HumanBone.HIPS, "hip" to HumanBone.HIPS, "pelvis" to HumanBone.HIPS,
        // Biped / Mixamo: Spine, Spine1, Spine2. Unreal: spine_01, spine_02, spine_03.
        "spine" to HumanBone.SPINE, "spine01" to HumanBone.SPINE,
        "spine1" to HumanBone.CHEST, "spine02" to HumanBone.CHEST, "chest" to HumanBone.CHEST,
        "spine2" to HumanBone.UPPER_CHEST, "spine03" to HumanBone.UPPER_CHEST,
        "upperchest" to HumanBone.UPPER_CHEST,
        "neck" to HumanBone.NECK, "neck1" to HumanBone.NECK, "neck01" to HumanBone.NECK,
        "head" to HumanBone.HEAD
    )

    /** Core term → (left bone, right bone). */
    private val SIDED: Map<String, Pair<HumanBone, HumanBone>> = buildMap {
        fun put(bones: Pair<HumanBone, HumanBone>, vararg keys: String) { for (k in keys) put(k, bones) }
        put(HumanBone.LEFT_SHOULDER to HumanBone.RIGHT_SHOULDER, "shoulder", "clavicle")
        put(HumanBone.LEFT_UPPER_ARM to HumanBone.RIGHT_UPPER_ARM, "upperarm", "arm")
        put(HumanBone.LEFT_LOWER_ARM to HumanBone.RIGHT_LOWER_ARM, "lowerarm", "forearm")
        put(HumanBone.LEFT_HAND to HumanBone.RIGHT_HAND, "hand", "wrist")
        put(HumanBone.LEFT_UPPER_LEG to HumanBone.RIGHT_UPPER_LEG, "upperleg", "upleg", "thigh")
        put(HumanBone.LEFT_LOWER_LEG to HumanBone.RIGHT_LOWER_LEG, "lowerleg", "leg", "calf", "shin")
        put(HumanBone.LEFT_FOOT to HumanBone.RIGHT_FOOT, "foot", "ankle")
        put(HumanBone.LEFT_TOES to HumanBone.RIGHT_TOES, "toes", "toe", "toe0", "toebase")
    }

    fun map(rawName: String): HumanBone? {
        var s = rawName.lowercase()
            // Sketchfab node-index suffix ("Bip001LHand_070", "Hips_02") — but not Unreal's spine_01…05
            .let { if (Regex("spine_0[1-5]$").containsMatchIn(it)) it else it.replace(Regex("_\\d+$"), "") }
            .replace(Regex("\\.\\d+$"), "")        // Blender duplicate suffix: "Hand.001"
        var side: Side? = null
        // VRM 0.x encodes the side in the prefix.
        when {
            s.startsWith("j_bip_l_") -> { side = Side.LEFT; s = s.removePrefix("j_bip_l_") }
            s.startsWith("j_bip_r_") -> { side = Side.RIGHT; s = s.removePrefix("j_bip_r_") }
            s.startsWith("j_bip_c_") -> { side = Side.CENTER; s = s.removePrefix("j_bip_c_") }
        }
        s = s.substringAfterLast(':')               // "mixamorig:LeftArm", "Armature:Hips"
            .substringAfterLast('|')
        s = s.replace(Regex("[^a-z0-9]"), "")
        for (prefix in listOf("mixamorig", "bip001", "bip01", "def", "orgs")) {
            if (s.startsWith(prefix) && s.length > prefix.length) { s = s.removePrefix(prefix); break }
        }
        if (s.isEmpty()) return null

        if (side != null) return resolve(s, side)
        // Try the explicit forms first, then single-letter sides ("lthigh", "upperarml").
        val candidates = listOfNotNull(
            s.takeIf { it.startsWith("left") }?.let { it.removePrefix("left") to Side.LEFT },
            s.takeIf { it.startsWith("right") }?.let { it.removePrefix("right") to Side.RIGHT },
            s.takeIf { it.endsWith("left") }?.let { it.removeSuffix("left") to Side.LEFT },
            s.takeIf { it.endsWith("right") }?.let { it.removeSuffix("right") to Side.RIGHT },
            s to Side.CENTER,
            s.takeIf { it.endsWith("l") }?.let { it.dropLast(1) to Side.LEFT },
            s.takeIf { it.endsWith("r") }?.let { it.dropLast(1) to Side.RIGHT },
            s.takeIf { it.startsWith("l") }?.let { it.drop(1) to Side.LEFT },
            s.takeIf { it.startsWith("r") }?.let { it.drop(1) to Side.RIGHT }
        )
        for ((core, sd) in candidates) resolve(core, sd)?.let { return it }
        return null
    }

    private fun resolve(core: String, side: Side): HumanBone? = when (side) {
        Side.CENTER -> CENTER[core]
        Side.LEFT -> SIDED[core]?.first
        Side.RIGHT -> SIDED[core]?.second
    }
}

// ─── Skeleton + geometry ─────────────────────────────────────────────────────

/**
 * The reduced skeleton a body asset is rendered with: humanoid joints and their
 * ancestors only (at most [MAX_BONES]). Every other rig joint — skirt, hair, fingers,
 * twist/helper bones — has its skin weights folded into its nearest kept ancestor,
 * so it follows that bone rigidly.
 *
 * Everything is expressed in the glTF *scene* space at the file's rest pose (Y-up,
 * metres), not the raw skin-bind space: exporters such as Sketchfab put the Z-up → Y-up
 * conversion and unit scale on a parent node, which only the scene transforms carry.
 * Matrices are column-major 4×4.
 */
class BodySkeleton(
    val names: List<String>,
    /** Parent bone index, or -1 for a root. Parents always precede children. */
    val parent: IntArray,
    /** Rest-pose scene-space matrix of each bone. */
    val restWorld: List<FloatArray>,
    val inverseBind: List<FloatArray>,
    val human: Map<HumanBone, Int>
) {
    val boneCount: Int get() = names.size

    fun restPosition(bone: Int): FloatArray =
        restWorld[bone].let { floatArrayOf(it[12], it[13], it[14]) }

    companion object {
        /** Must fit the renderer's uniform palette (SkinnedMeshRenderer.MAX_SKIN_JOINTS). */
        const val MAX_BONES = 56
    }
}

/** Merged, render-ready triangle geometry of a skinned GLB. */
class SkinnedGeometry(
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: IntArray,
    /** 4 bone indices per vertex, into [BodySkeleton]. */
    val joints: IntArray,
    /** 4 weights per vertex, summing to 1. */
    val weights: FloatArray,
    val skeleton: BodySkeleton,
    /** Axis-aligned bounds of the bind pose: minX, minY, minZ, maxX, maxY, maxZ. */
    val bounds: FloatArray
)

/**
 * Builds [SkinnedGeometry] from a parsed GLB (JSON + BIN chunk): every triangle
 * primitive of every mesh bound to skin 0 (or attached under one of its joints),
 * real JOINTS_0 / WEIGHTS_0, indices and normals. Outline shells (inverted-hull
 * primitives whose material name contains "outline") are skipped — the app draws a
 * single shaded surface, and an enclosing shell would hide the body.
 */
object SkinnedGlbBuilder {

    fun build(root: JSONObject, bin: ByteArray?): SkinnedGeometry? {
        if (bin == null) return null
        val skins = root.optJSONArray("skins") ?: return null
        if (skins.length() == 0) return null
        val nodes = root.optJSONArray("nodes") ?: return null
        val reader = Accessors(root, bin)

        val skin = skins.getJSONObject(0)
        val jointArr = skin.getJSONArray("joints")
        val joints = IntArray(jointArr.length()) { jointArr.getInt(it) }
        val ibms = if (skin.has("inverseBindMatrices")) reader.floats(skin.getInt("inverseBindMatrices"))
                   else FloatArray(joints.size * 16).also { a -> for (k in joints.indices) identity().copyInto(a, k * 16) }

        val nodeParent = IntArray(nodes.length()) { -1 }
        for (i in 0 until nodes.length()) {
            nodes.getJSONObject(i).optJSONArray("children")?.let { c ->
                for (k in 0 until c.length()) nodeParent[c.getInt(k)] = i
            }
        }
        val slotOfNode = HashMap<Int, Int>().apply { joints.forEachIndexed { k, n -> put(n, k) } }
        fun jointParent(k: Int): Int {
            var p = nodeParent[joints[k]]
            while (p >= 0) { slotOfNode[p]?.let { return it }; p = nodeParent[p] }
            return -1
        }
        val fullParent = IntArray(joints.size) { jointParent(it) }

        // Classify joints, first match wins (parents precede children in sane rigs).
        val humanFull = LinkedHashMap<HumanBone, Int>()
        for (k in joints.indices) {
            val name = nodes.getJSONObject(joints[k]).optString("name", "")
            HumanBoneMapper.map(name)?.let { humanFull.putIfAbsent(it, k) }
        }

        // Keep humanoid joints and their ancestors; fold everything else.
        val keep = BooleanArray(joints.size)
        for (k in humanFull.values) {
            var j = k
            while (j >= 0 && !keep[j]) { keep[j] = true; j = fullParent[j] }
        }
        if (humanFull.isEmpty()) { for (k in 0 until minOf(joints.size, BodySkeleton.MAX_BONES)) keep[k] = true }
        // Order kept joints parents-first.
        val order = ArrayList<Int>()
        val visited = BooleanArray(joints.size)
        fun visit(k: Int) {
            if (visited[k]) return
            visited[k] = true
            if (fullParent[k] >= 0 && keep[fullParent[k]]) visit(fullParent[k])
            order += k
        }
        for (k in joints.indices) if (keep[k]) visit(k)
        while (order.size > BodySkeleton.MAX_BONES) order.removeAt(order.lastIndex)
        val reducedOf = IntArray(joints.size) { -1 }
        order.forEachIndexed { r, k -> reducedOf[k] = r }
        fun fold(k: Int): Int {
            var j = k
            while (j >= 0) { if (reducedOf[j] >= 0) return reducedOf[j]; j = fullParent[j] }
            return 0
        }
        val redirect = IntArray(joints.size) { fold(it) }

        val world = worldMatrices(root, nodes)
        // Per-joint bind→scene transform: jointWorld × inverseBind (glTF skinning formula).
        val bindToScene = Array(joints.size) { k -> mul(world[joints[k]], ibms.copyOfRange(k * 16, k * 16 + 16)) }
        val restWorld = order.map { k -> world[joints[k]] }
        val skeleton = BodySkeleton(
            names = order.map { nodes.getJSONObject(joints[it]).optString("name", "joint$it") },
            parent = IntArray(order.size) { r -> fullParent[order[r]].let { if (it >= 0) fold(it) else -1 } },
            restWorld = restWorld,
            inverseBind = restWorld.map { invert(it) },
            human = humanFull.mapNotNull { (bone, k) -> reducedOf[k].takeIf { it >= 0 }?.let { bone to it } }.toMap()
        )

        // Geometry
        val pos = FloatList(); val nrm = FloatList(); val idx = IntList()
        val jnt = IntList(); val wgt = FloatList()
        val materials = root.optJSONArray("materials")
        val meshes = root.optJSONArray("meshes") ?: return null
        for (n in 0 until nodes.length()) {
            val node = nodes.getJSONObject(n)
            if (!node.has("mesh")) continue
            val skinned = node.optInt("skin", -1) == 0
            // Unskinned meshes parented under a joint (props) follow that joint rigidly.
            var anchor = -1
            if (!skinned) {
                var p = nodeParent[n]
                while (p >= 0 && anchor < 0) { slotOfNode[p]?.let { anchor = redirect[it] }; p = nodeParent[p] }
                if (anchor < 0) continue
            }
            val prims = meshes.getJSONObject(node.getInt("mesh")).getJSONArray("primitives")
            for (pi in 0 until prims.length()) {
                val prim = prims.getJSONObject(pi)
                if (prim.optInt("mode", 4) != 4) continue
                val matName = prim.optInt("material", -1).takeIf { it >= 0 }
                    ?.let { materials?.optJSONObject(it)?.optString("name", "") } ?: ""
                if (matName.contains("outline", ignoreCase = true)) continue
                val attrs = prim.getJSONObject("attributes")
                val p = reader.floats(attrs.getInt("POSITION"))
                val vCount = p.size / 3
                val base = pos.size / 3
                val hasWeights = skinned && attrs.has("JOINTS_0") && attrs.has("WEIGHTS_0")
                val ja = if (hasWeights) reader.ints(attrs.getInt("JOINTS_0")) else null
                val wa = if (hasWeights) reader.floats(attrs.getInt("WEIGHTS_0")) else null
                val srcN = if (attrs.has("NORMAL")) reader.floats(attrs.getInt("NORMAL")) else null
                val blend = FloatArray(16)
                for (v in 0 until vCount) {
                    // Bake to scene space: skinned → Σ wᵢ·(jointWorld·IBM)ᵢ, unskinned → node world.
                    val m: FloatArray = if (ja != null && wa != null) {
                        blend.fill(0f)
                        var wsum = 0f
                        for (c in 0 until 4) {
                            val w = wa[v * 4 + c]; val k = ja[v * 4 + c]
                            if (w <= 0f || k !in bindToScene.indices) continue
                            val bm = bindToScene[k]
                            for (e in 0 until 16) blend[e] += w * bm[e]
                            wsum += w
                        }
                        if (wsum > 0f) { for (e in 0 until 16) blend[e] /= wsum; blend } else bindToScene[0]
                    } else if (skinned) bindToScene[0] else world[n]
                    val x = p[v * 3]; val y = p[v * 3 + 1]; val z = p[v * 3 + 2]
                    pos.add(m[0] * x + m[4] * y + m[8] * z + m[12],
                            m[1] * x + m[5] * y + m[9] * z + m[13],
                            m[2] * x + m[6] * y + m[10] * z + m[14])
                    if (srcN != null) {
                        val nx = srcN[v * 3]; val ny = srcN[v * 3 + 1]; val nz = srcN[v * 3 + 2]
                        val ox = m[0] * nx + m[4] * ny + m[8] * nz
                        val oy = m[1] * nx + m[5] * ny + m[9] * nz
                        val oz = m[2] * nx + m[6] * ny + m[10] * nz
                        val l = sqrt(ox * ox + oy * oy + oz * oz).takeIf { it > 1e-12f } ?: 1f
                        nrm.add(ox / l, oy / l, oz / l)
                    } else nrm.add(0f, 0f, 0f)
                }
                val tri = if (prim.has("indices")) reader.ints(prim.getInt("indices")) else IntArray(vCount) { it }
                for (t in tri) idx.add(base + t)
                if (srcN == null) smoothNormals(pos, nrm, tri, base)

                if (ja != null && wa != null) {
                    for (v in 0 until vCount) appendInfluences(ja, wa, v, redirect, jnt, wgt)
                } else {
                    val bone = if (skinned) 0 else anchor
                    repeat(vCount) { jnt.add(bone, 0, 0, 0); wgt.add(1f, 0f, 0f, 0f) }
                }
            }
        }
        if (pos.size == 0) return null
        val positions = pos.toArray()
        return SkinnedGeometry(positions, nrm.toArray(), idx.toArray(), jnt.toArray(), wgt.toArray(),
                               skeleton, bounds(positions))
    }

    /** Folds a vertex's influences onto reduced bones, merges duplicates, keeps the top 4. */
    private fun appendInfluences(ja: IntArray, wa: FloatArray, v: Int, redirect: IntArray,
                                 jnt: IntList, wgt: FloatList) {
        val bones = IntArray(4); val ws = FloatArray(4); var n = 0
        for (c in 0 until 4) {
            val w = wa[v * 4 + c]
            if (w <= 0f) continue
            val src = ja[v * 4 + c]
            val b = if (src in redirect.indices) redirect[src] else 0
            val existing = (0 until n).firstOrNull { bones[it] == b }
            if (existing != null) ws[existing] += w else { bones[n] = b; ws[n] = w; n++ }
        }
        val sum = ws.sum()
        if (n == 0 || sum <= 0f) { jnt.add(0, 0, 0, 0); wgt.add(1f, 0f, 0f, 0f); return }
        jnt.add(bones[0], bones[1], bones[2], bones[3])
        wgt.add(ws[0] / sum, ws[1] / sum, ws[2] / sum, ws[3] / sum)
    }

    private fun smoothNormals(pos: FloatList, nrm: FloatList, tri: IntArray, base: Int) {
        var t = 0
        while (t + 2 < tri.size) {
            val a = base + tri[t]; val b = base + tri[t + 1]; val c = base + tri[t + 2]
            val ux = pos[b * 3] - pos[a * 3]; val uy = pos[b * 3 + 1] - pos[a * 3 + 1]; val uz = pos[b * 3 + 2] - pos[a * 3 + 2]
            val vx = pos[c * 3] - pos[a * 3]; val vy = pos[c * 3 + 1] - pos[a * 3 + 1]; val vz = pos[c * 3 + 2] - pos[a * 3 + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (i in intArrayOf(a, b, c)) { nrm[i * 3] += nx; nrm[i * 3 + 1] += ny; nrm[i * 3 + 2] += nz }
            t += 3
        }
        for (v in base until pos.size / 3) {
            val l = sqrt(nrm[v * 3] * nrm[v * 3] + nrm[v * 3 + 1] * nrm[v * 3 + 1] + nrm[v * 3 + 2] * nrm[v * 3 + 2])
            if (l > 1e-12f) { nrm[v * 3] /= l; nrm[v * 3 + 1] /= l; nrm[v * 3 + 2] /= l } else nrm[v * 3 + 1] = 1f
        }
    }

    private fun bounds(p: FloatArray): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in p.indices step 3) for (a in 0..2) {
            if (p[i + a] < b[a]) b[a] = p[i + a]
            if (p[i + a] > b[a + 3]) b[a + 3] = p[i + a]
        }
        return b
    }

    // ─── Node transforms ─────────────────────────────────────────────────────

    private fun worldMatrices(root: JSONObject, nodes: JSONArray): Array<FloatArray> {
        val out = Array(nodes.length()) { identity() }
        val done = BooleanArray(nodes.length())
        fun rec(i: Int, parent: FloatArray) {
            if (done[i]) return
            done[i] = true
            out[i] = mul(parent, localMatrix(nodes.getJSONObject(i)))
            nodes.getJSONObject(i).optJSONArray("children")?.let { c ->
                for (k in 0 until c.length()) rec(c.getInt(k), out[i])
            }
        }
        val scenes = root.optJSONArray("scenes")
        val sceneRoots = scenes?.optJSONObject(root.optInt("scene", 0))?.optJSONArray("nodes")
        if (sceneRoots != null) for (k in 0 until sceneRoots.length()) rec(sceneRoots.getInt(k), identity())
        for (i in 0 until nodes.length()) rec(i, identity())
        return out
    }

    private fun localMatrix(n: JSONObject): FloatArray {
        n.optJSONArray("matrix")?.let { a -> return FloatArray(16) { a.getDouble(it).toFloat() } }
        val t = n.optJSONArray("translation"); val r = n.optJSONArray("rotation"); val s = n.optJSONArray("scale")
        val m = (if (r != null) Quaternion(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(),
                                           r.getDouble(2).toFloat(), r.getDouble(3).toFloat()).toMatrix()
                 else identity())
        if (s != null) for (c in 0..2) for (row in 0..2) m[c * 4 + row] *= s.getDouble(c).toFloat()
        if (t != null) { m[12] = t.getDouble(0).toFloat(); m[13] = t.getDouble(1).toFloat(); m[14] = t.getDouble(2).toFloat() }
        return m
    }

    // ─── Accessor reading ────────────────────────────────────────────────────

    private class Accessors(root: JSONObject, private val bin: ByteArray) {
        private val accessors = root.getJSONArray("accessors")
        private val views = root.getJSONArray("bufferViews")
        private val buf = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)

        private fun components(type: String) = when (type) {
            "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> 1
        }

        private fun read(index: Int, convert: (offset: Int, ctype: Int, normalized: Boolean) -> Unit): Int {
            val a = accessors.getJSONObject(index)
            val view = views.getJSONObject(a.getInt("bufferView"))
            val ctype = a.getInt("componentType")
            val size = when (ctype) { 5120, 5121 -> 1; 5122, 5123 -> 2; else -> 4 }
            val n = components(a.getString("type"))
            val stride = view.optInt("byteStride", 0).takeIf { it > 0 } ?: (size * n)
            val start = view.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
            val count = a.getInt("count")
            val normalized = a.optBoolean("normalized", false)
            for (e in 0 until count) for (c in 0 until n) convert(start + e * stride + c * size, ctype, normalized)
            return count * n
        }

        fun floats(index: Int): FloatArray {
            val out = FloatList()
            read(index) { off, ctype, norm ->
                out.add(when (ctype) {
                    5126 -> buf.getFloat(off)
                    5121 -> (bin[off].toInt() and 0xFF).let { if (norm) it / 255f else it.toFloat() }
                    5123 -> (buf.getShort(off).toInt() and 0xFFFF).let { if (norm) it / 65535f else it.toFloat() }
                    5120 -> bin[off].toFloat().let { if (norm) maxOf(it / 127f, -1f) else it }
                    5122 -> buf.getShort(off).toFloat().let { if (norm) maxOf(it / 32767f, -1f) else it }
                    else -> buf.getInt(off).toFloat()
                })
            }
            return out.toArray()
        }

        fun ints(index: Int): IntArray {
            val out = IntList()
            read(index) { off, ctype, _ ->
                out.add(when (ctype) {
                    5121 -> bin[off].toInt() and 0xFF
                    5123 -> buf.getShort(off).toInt() and 0xFFFF
                    else -> buf.getInt(off)
                })
            }
            return out.toArray()
        }
    }
}

// ─── Small math / containers ─────────────────────────────────────────────────

internal fun identity(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

/** Column-major a × b. */
internal fun mul(a: FloatArray, b: FloatArray): FloatArray {
    val o = FloatArray(16)
    for (c in 0..3) for (r in 0..3) {
        var s = 0f
        for (k in 0..3) s += a[k * 4 + r] * b[c * 4 + k]
        o[c * 4 + r] = s
    }
    return o
}

/** General 4×4 inverse (column-major). Returns identity for a singular matrix. */
internal fun invert(m: FloatArray): FloatArray {
    val a = DoubleArray(16) { m[it].toDouble() }
    val inv = DoubleArray(16)
    inv[0] = a[5]*a[10]*a[15]-a[5]*a[11]*a[14]-a[9]*a[6]*a[15]+a[9]*a[7]*a[14]+a[13]*a[6]*a[11]-a[13]*a[7]*a[10]
    inv[4] = -a[4]*a[10]*a[15]+a[4]*a[11]*a[14]+a[8]*a[6]*a[15]-a[8]*a[7]*a[14]-a[12]*a[6]*a[11]+a[12]*a[7]*a[10]
    inv[8] = a[4]*a[9]*a[15]-a[4]*a[11]*a[13]-a[8]*a[5]*a[15]+a[8]*a[7]*a[13]+a[12]*a[5]*a[11]-a[12]*a[7]*a[9]
    inv[12] = -a[4]*a[9]*a[14]+a[4]*a[10]*a[13]+a[8]*a[5]*a[14]-a[8]*a[6]*a[13]-a[12]*a[5]*a[10]+a[12]*a[6]*a[9]
    inv[1] = -a[1]*a[10]*a[15]+a[1]*a[11]*a[14]+a[9]*a[2]*a[15]-a[9]*a[3]*a[14]-a[13]*a[2]*a[11]+a[13]*a[3]*a[10]
    inv[5] = a[0]*a[10]*a[15]-a[0]*a[11]*a[14]-a[8]*a[2]*a[15]+a[8]*a[3]*a[14]+a[12]*a[2]*a[11]-a[12]*a[3]*a[10]
    inv[9] = -a[0]*a[9]*a[15]+a[0]*a[11]*a[13]+a[8]*a[1]*a[15]-a[8]*a[3]*a[13]-a[12]*a[1]*a[11]+a[12]*a[3]*a[9]
    inv[13] = a[0]*a[9]*a[14]-a[0]*a[10]*a[13]-a[8]*a[1]*a[14]+a[8]*a[2]*a[13]+a[12]*a[1]*a[10]-a[12]*a[2]*a[9]
    inv[2] = a[1]*a[6]*a[15]-a[1]*a[7]*a[14]-a[5]*a[2]*a[15]+a[5]*a[3]*a[14]+a[13]*a[2]*a[7]-a[13]*a[3]*a[6]
    inv[6] = -a[0]*a[6]*a[15]+a[0]*a[7]*a[14]+a[4]*a[2]*a[15]-a[4]*a[3]*a[14]-a[12]*a[2]*a[7]+a[12]*a[3]*a[6]
    inv[10] = a[0]*a[5]*a[15]-a[0]*a[7]*a[13]-a[4]*a[1]*a[15]+a[4]*a[3]*a[13]+a[12]*a[1]*a[7]-a[12]*a[3]*a[5]
    inv[14] = -a[0]*a[5]*a[14]+a[0]*a[6]*a[13]+a[4]*a[1]*a[14]-a[4]*a[2]*a[13]-a[12]*a[1]*a[6]+a[12]*a[2]*a[5]
    inv[3] = -a[1]*a[6]*a[11]+a[1]*a[7]*a[10]+a[5]*a[2]*a[11]-a[5]*a[3]*a[10]-a[9]*a[2]*a[7]+a[9]*a[3]*a[6]
    inv[7] = a[0]*a[6]*a[11]-a[0]*a[7]*a[10]-a[4]*a[2]*a[11]+a[4]*a[3]*a[10]+a[8]*a[2]*a[7]-a[8]*a[3]*a[6]
    inv[11] = -a[0]*a[5]*a[11]+a[0]*a[7]*a[9]+a[4]*a[1]*a[11]-a[4]*a[3]*a[9]-a[8]*a[1]*a[7]+a[8]*a[3]*a[5]
    inv[15] = a[0]*a[5]*a[10]-a[0]*a[6]*a[9]-a[4]*a[1]*a[10]+a[4]*a[2]*a[9]+a[8]*a[1]*a[6]-a[8]*a[2]*a[5]
    val det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12]
    if (kotlin.math.abs(det) < 1e-12) return identity()
    return FloatArray(16) { (inv[it] / det).toFloat() }
}

internal class FloatList {
    private var a = FloatArray(1024); var size = 0; private set
    fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun add(x: Float, y: Float, z: Float) { add(x); add(y); add(z) }
    fun add(x: Float, y: Float, z: Float, w: Float) { add(x); add(y); add(z); add(w) }
    fun addAll(v: FloatArray) { for (f in v) add(f) }
    operator fun get(i: Int) = a[i]
    operator fun set(i: Int, v: Float) { a[i] = v }
    fun toArray() = a.copyOf(size)
}

internal class IntList {
    private var a = IntArray(1024); var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun add(x: Int, y: Int, z: Int, w: Int) { add(x); add(y); add(z); add(w) }
    fun toArray() = a.copyOf(size)
}
