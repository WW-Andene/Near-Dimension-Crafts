#!/usr/bin/env python3
"""Rig the "Ball Joint Doll Rigged Basemesh (male)" for Handy.

The Sketchfab FBX ships without an armature: 90 loose rigid parts, three dolls
side by side. A ball-jointed doll is rigid segments meeting at balls, so a rigid
skin (every vertex 100 % on one bone) is the natural rig:

  * keep the complete neutral-pose doll standing at x ≈ 0,
  * put each joint at the centre of its ball part (knee, elbow, wrist/finger knots…),
  * name bones after Unity HumanBodyBones (what AssetLoader / VMC expect),
  * split left/right per vertex — many parts hold both sides in one mesh;
    the doll faces +Z, so +X is the character's left (glTF/VRM convention).

Output: one mesh, primitive 0 = body ("check" material), primitive 1 = outline,
both skinned to one skin. Input is the GLB produced by FBX2glTF from the source FBX.

Usage: python rig_doll.py <input.glb> <output.glb>
Requirements: numpy.
"""
import json
import re
import struct
import sys

import numpy as np

CT = {5121: np.uint8, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
NC = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}
DOLL_X_RANGE = (-0.3, 0.3)          # the complete neutral-pose doll
FINGERS = {"thumb": "Thumb", "index": "Index", "middle": "Middle", "ring": "Ring", "pinky": "Little"}


# ─── glTF reading ────────────────────────────────────────────────────────────

def load_glb(path):
    b = open(path, "rb").read()
    jl = struct.unpack("<I", b[12:16])[0]
    g = json.loads(b[20:20 + jl])
    o = 20 + jl
    bl = struct.unpack("<I", b[o:o + 4])[0]
    return g, b[o + 8:o + 8 + bl]


def accessor(g, binc, i):
    a = g["accessors"][i]
    bv = g["bufferViews"][a["bufferView"]]
    dt, n = CT[a["componentType"]], NC[a["type"]]
    off = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    stride = bv.get("byteStride", 0)
    item = np.dtype(dt).itemsize * n
    if stride and stride != item:
        raw = np.frombuffer(binc, np.uint8, count=stride * (a["count"] - 1) + item, offset=off)
        rows = [np.frombuffer(raw[k * stride:k * stride + item].tobytes(), dt) for k in range(a["count"])]
        return np.stack(rows).astype(np.float64 if dt == np.float32 else dt)
    out = np.frombuffer(binc, dt, count=a["count"] * n, offset=off).reshape(a["count"], n)
    return out.astype(np.float64) if dt == np.float32 else out.copy()


def quat_to_mat(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def local_matrix(n):
    if "matrix" in n:
        return np.array(n["matrix"], dtype=np.float64).reshape(4, 4).T
    m = np.eye(4)
    m[:3, :3] = quat_to_mat(n.get("rotation", [0, 0, 0, 1])) @ np.diag(n.get("scale", [1, 1, 1]))
    m[:3, 3] = n.get("translation", [0, 0, 0])
    return m


def world_matrices(g):
    out = {}

    def rec(i, parent):
        out[i] = parent @ local_matrix(g["nodes"][i])
        for c in g["nodes"][i].get("children", []):
            rec(c, out[i])

    for r in g["scenes"][g.get("scene", 0)]["nodes"]:
        rec(r, np.eye(4))
    return out


# ─── Parts ───────────────────────────────────────────────────────────────────

def part_kind(node_name):
    """'upperArmMesh.006' → 'upperArm'; 'Cube' (shoulder ball, left side) → 'shoulder'."""
    base = re.sub(r"\.\d+$", "", node_name)
    base = re.sub(r"Mesh$", "", base)
    return "shoulder" if base == "Cube" else base


def collect_parts(g, binc):
    world = world_matrices(g)
    parts = []
    for i, n in enumerate(g["nodes"]):
        if "mesh" not in n:
            continue
        w = world[i]
        nrm_m = np.linalg.inv(w[:3, :3]).T
        prims = []
        for p in g["meshes"][n["mesh"]]["primitives"]:
            pos = accessor(g, binc, p["attributes"]["POSITION"])
            pos = pos @ w[:3, :3].T + w[:3, 3]
            nrm = accessor(g, binc, p["attributes"]["NORMAL"]) @ nrm_m.T
            nrm /= np.linalg.norm(nrm, axis=1, keepdims=True) + 1e-12
            uv = accessor(g, binc, p["attributes"]["TEXCOORD_0"]) if "TEXCOORD_0" in p["attributes"] \
                else np.zeros((len(pos), 2))
            idx = accessor(g, binc, p["indices"]).reshape(-1).astype(np.uint32)
            prims.append({"pos": pos, "nrm": nrm, "uv": uv, "idx": idx, "material": p.get("material", 0)})
        allpos = np.vstack([p["pos"] for p in prims])
        cx = (allpos[:, 0].min() + allpos[:, 0].max()) / 2
        if DOLL_X_RANGE[0] < cx < DOLL_X_RANGE[1]:
            parts.append({"name": n["name"], "kind": part_kind(n["name"]), "prims": prims})
    return parts


def side_verts(parts, kind, side):
    """All body-material vertices of `kind`, restricted to one side (None = whole part)."""
    pts = np.vstack([p["pos"] for part in parts if part["kind"] == kind for p in part["prims"]
                     if p["material"] == 0])
    if side == "Left":
        pts = pts[pts[:, 0] > 0]
    elif side == "Right":
        pts = pts[pts[:, 0] < 0]
    if len(pts) == 0:
        raise ValueError(f"no vertices for {kind}/{side}")
    return pts


def centroid(pts):
    return pts.mean(axis=0)


def end_centroid(pts, toward, frac=0.08):
    """Centroid of the `frac` of points lying furthest along direction `toward`."""
    d = pts @ toward
    return pts[d >= np.quantile(d, 1 - frac)].mean(axis=0)


# ─── Skeleton ────────────────────────────────────────────────────────────────

def build_skeleton(parts):
    """Returns ordered [(bone, parent, pivot)] and {(kind, side): bone}."""
    up = np.array([0.0, 1.0, 0.0])
    bones, assign = [], {}

    def add(name, parent, pivot):
        bones.append((name, parent, np.asarray(pivot, dtype=np.float64)))

    hip, waist, head = side_verts(parts, "hip", None), side_verts(parts, "waist", None), side_verts(parts, "head", None)
    add("Hips", None, centroid(hip))
    add("Spine", "Hips", end_centroid(waist, -up))
    add("Chest", "Spine", end_centroid(waist, up))
    neck = end_centroid(head, -up)
    add("Neck", "Chest", neck)
    add("Head", "Neck", neck + np.array([0.0, 0.04, 0.0]))
    for k, bone in (("hip", "Hips"), ("waist", "Spine"), ("chest", "Chest"), ("head", "Head")):
        assign[(k, None)] = bone

    for side in ("Left", "Right"):
        thigh = side_verts(parts, "tight", side)
        calf = side_verts(parts, "calf", side)
        add(f"{side}UpperLeg", "Hips", end_centroid(thigh, up))
        add(f"{side}LowerLeg", f"{side}UpperLeg", centroid(side_verts(parts, "knee", side)))
        add(f"{side}Foot", f"{side}LowerLeg", end_centroid(calf, -up))
        assign.update({("tight", side): f"{side}UpperLeg", ("knee", side): f"{side}LowerLeg",
                       ("calf", side): f"{side}LowerLeg", ("foot", side): f"{side}Foot"})

        add(f"{side}Shoulder", "Chest", centroid(side_verts(parts, "clavicleKnot", side)))
        add(f"{side}UpperArm", f"{side}Shoulder", centroid(side_verts(parts, "shoulder", side)))
        add(f"{side}LowerArm", f"{side}UpperArm", centroid(side_verts(parts, "elbow", side)))
        wrist = centroid(side_verts(parts, "forearmKnot", side))
        add(f"{side}Hand", f"{side}LowerArm", wrist)
        assign.update({("clavicleKnot", side): f"{side}Shoulder", ("shoulder", side): f"{side}UpperArm",
                       ("upperArm", side): f"{side}UpperArm", ("elbow", side): f"{side}LowerArm",
                       ("foreArm", side): f"{side}LowerArm", ("forearmKnot", side): f"{side}Hand",
                       ("palm", side): f"{side}Hand"})

        for f, unity in FINGERS.items():
            knot_kind = "thunbKnot" if f == "thumb" else f + "Knot"      # typos are the source's
            mid_kind = "inderMid" if f == "index" else f + "Mid"
            knot = centroid(side_verts(parts, knot_kind, side))
            mid, tip = side_verts(parts, mid_kind, side), side_verts(parts, f + "Tip", side)
            along = centroid(tip) - knot
            along /= np.linalg.norm(along)
            add(f"{side}{unity}Proximal", f"{side}Hand", knot)
            add(f"{side}{unity}Intermediate", f"{side}{unity}Proximal", end_centroid(mid, -along))
            add(f"{side}{unity}Distal", f"{side}{unity}Intermediate", end_centroid(tip, -along))
            assign.update({(knot_kind, side): f"{side}Hand", (f + "Root", side): f"{side}{unity}Proximal",
                           (mid_kind, side): f"{side}{unity}Intermediate",
                           (f + "Tip", side): f"{side}{unity}Distal"})
    return bones, assign


def bone_for_vertex(kind, x, assign):
    if (kind, None) in assign:
        return assign[(kind, None)]
    return assign[(kind, "Left" if x > 0 else "Right")]


# ─── glTF writing ────────────────────────────────────────────────────────────

class Bin:
    def __init__(self):
        self.data, self.views, self.accessors = bytearray(), [], []

    def add(self, arr, ctype, gtype, target=None, minmax=False):
        while len(self.data) % 4:
            self.data.append(0)
        raw = np.ascontiguousarray(arr).tobytes()
        view = {"buffer": 0, "byteOffset": len(self.data), "byteLength": len(raw)}
        if target:
            view["target"] = target
        self.data += raw
        self.views.append(view)
        acc = {"bufferView": len(self.views) - 1, "componentType": ctype,
               "count": int(arr.shape[0]), "type": gtype}
        if minmax:
            acc["min"] = arr.min(axis=0).tolist()
            acc["max"] = arr.max(axis=0).tolist()
        self.accessors.append(acc)
        return len(self.accessors) - 1


def main(src, dst):
    g, binc = load_glb(src)
    parts = collect_parts(g, binc)
    bones, assign = build_skeleton(parts)
    index = {name: i for i, (name, _, _) in enumerate(bones)}

    merged = {0: [], 1: []}   # material → list of (pos, nrm, uv, idx, joint)
    for part in parts:
        for p in part["prims"]:
            joints = np.array([index[bone_for_vertex(part["kind"], x, assign)] for x in p["pos"][:, 0]],
                              dtype=np.uint16)
            merged[min(p["material"], 1)].append((p["pos"], p["nrm"], p["uv"], p["idx"], joints))

    # Bone nodes: identity rest rotation, translation relative to parent pivot.
    nodes = []
    for name, parent, pivot in bones:
        parent_pivot = bones[index[parent]][2] if parent else np.zeros(3)
        nodes.append({"name": name, "translation": (pivot - parent_pivot).tolist()})
    for name, parent, _ in bones:
        if parent:
            nodes[index[parent]].setdefault("children", []).append(index[name])
    mesh_node = len(nodes)
    nodes.append({"name": "BallJointDoll", "mesh": 0, "skin": 0})

    out = Bin()
    ibm = np.stack([np.eye(4) for _ in bones])
    for i, (_, _, pivot) in enumerate(bones):
        ibm[i][:3, 3] = -pivot
    ibm_acc = out.add(ibm.transpose(0, 2, 1).reshape(-1, 16).astype(np.float32), 5126, "MAT4")

    primitives = []
    for mat in (0, 1):
        chunks = merged[mat]
        if not chunks:
            continue
        pos = np.vstack([c[0] for c in chunks]).astype(np.float32)
        nrm = np.vstack([c[1] for c in chunks]).astype(np.float32)
        uv = np.vstack([c[2] for c in chunks]).astype(np.float32)
        offsets = np.cumsum([0] + [len(c[0]) for c in chunks[:-1]])
        idx = np.concatenate([c[3] + o for c, o in zip(chunks, offsets)]).astype(np.uint32)
        j = np.zeros((len(pos), 4), np.uint16)
        j[:, 0] = np.concatenate([c[4] for c in chunks])
        w = np.zeros((len(pos), 4), np.float32)
        w[:, 0] = 1.0
        primitives.append({
            "attributes": {
                "POSITION": out.add(pos, 5126, "VEC3", 34962, minmax=True),
                "NORMAL": out.add(nrm, 5126, "VEC3", 34962),
                "TEXCOORD_0": out.add(uv, 5126, "VEC2", 34962),
                "JOINTS_0": out.add(j, 5123, "VEC4", 34962),
                "WEIGHTS_0": out.add(w, 5126, "VEC4", 34962),
            },
            "indices": out.add(idx, 5125, "SCALAR", 34963),
            "material": mat,
        })

    credit = ('Based on "Ball Joint Doll Rigged Basemesh" by ChamberSu1996 (Sketchfab). '
              'Rigged for Handy by tools/rigging/rig_doll.py.')
    doc = {
        "asset": {"version": "2.0", "generator": "Handy rig_doll.py", "copyright": credit},
        "scene": 0,
        "scenes": [{"nodes": [index["Hips"], mesh_node]}],
        "nodes": nodes,
        "meshes": [{"name": "BallJointDoll", "primitives": primitives}],
        "skins": [{"name": "Humanoid", "joints": list(range(len(bones))),
                   "inverseBindMatrices": ibm_acc, "skeleton": index["Hips"]}],
        "materials": [
            {"name": "check", "pbrMetallicRoughness": {"baseColorFactor": [0.8, 0.8, 0.8, 1], "metallicFactor": 0.4, "roughnessFactor": 0.23}},
            {"name": "outline", "pbrMetallicRoughness": {"baseColorFactor": [0, 0, 0, 1], "metallicFactor": 0.4, "roughnessFactor": 0.27}},
        ],
        "accessors": out.accessors,
        "bufferViews": out.views,
        "buffers": [{"byteLength": len(out.data)}],
    }
    js = json.dumps(doc, separators=(",", ":")).encode()
    js += b" " * (-len(js) % 4)
    binb = bytes(out.data) + b"\0" * (-len(out.data) % 4)
    total = 12 + 8 + len(js) + 8 + len(binb)
    with open(dst, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total))
        f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
        f.write(struct.pack("<II", len(binb), 0x004E4942) + binb)
    print(f"{len(parts)} parts, {len(bones)} bones, "
          + ", ".join(f"prim{i}: {len(p['attributes'])} attrs" for i, p in enumerate(primitives)))
    for name, parent, pivot in bones:
        print(f"  {name:28s} parent={parent or '-':24s} pivot={np.round(pivot, 3).tolist()}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
