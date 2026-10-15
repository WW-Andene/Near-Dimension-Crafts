#!/usr/bin/env python3
"""Benchmark the Depth Anything v2 channel against TUM RGB-D ground truth.

Mirrors what the app does: the model outputs relative inverse depth, which the
app maps to metres with an affine fit in inverse-depth space
(depth = 1 / (a * raw + b), see DepthAnythingSource's XR calibration).

Two calibration modes are reported:
  oracle  — a, b fitted on all valid ground-truth pixels (upper bound of the channel)
  sparse  — a, b fitted on N random ground-truth pixels (what ARCore/SfM anchors give)

Usage:
  python da2_tum.py --seq <tum_sequence_dir> --models <dir with *.onnx> [--frames 60]

Requirements: numpy, pillow, onnxruntime.
"""
import argparse
import json
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)
TUM_DEPTH_FACTOR = 5000.0          # 16-bit PNG units per metre
VALID_MIN_M, VALID_MAX_M = 0.1, 10.0

# (label, width, height). Model needs multiples of 14; all but the first keep
# the 4:3 camera aspect ratio.
CONFIGS = [
    ("518x518 squash (current app)", 518, 518),
    ("518x392", 518, 392),
    ("364x280", 364, 280),
    ("266x196", 266, 196),
    ("182x140", 182, 140),
]


def read_list(path):
    out = []
    for line in Path(path).read_text().splitlines():
        if line.startswith("#") or not line.strip():
            continue
        ts, name = line.split()[:2]
        out.append((float(ts), name))
    return out


def associate(rgb, depth, max_dt=0.02):
    """Pair each RGB frame with the closest depth frame (TUM convention)."""
    d_ts = np.array([t for t, _ in depth])
    pairs = []
    for t, name in rgb:
        i = int(np.argmin(np.abs(d_ts - t)))
        if abs(d_ts[i] - t) <= max_dt:
            pairs.append((name, depth[i][1]))
    return pairs


def preprocess(img, w, h):
    arr = np.asarray(img.resize((w, h), Image.BICUBIC), dtype=np.float32) / 255.0
    arr = (arr - MEAN) / STD
    return arr.transpose(2, 0, 1)[None]


def fit_inverse_affine(raw, gt):
    """Least squares a, b such that a*raw + b ≈ 1/gt."""
    A = np.stack([raw, np.ones_like(raw)], axis=1)
    sol, *_ = np.linalg.lstsq(A, 1.0 / gt, rcond=None)
    return sol


def metrics(pred, gt):
    err = np.abs(pred - gt)
    ratio = np.maximum(pred / gt, gt / pred)
    out = {
        "abs_rel": float(np.mean(err / gt)),
        "rmse_m": float(np.sqrt(np.mean((pred - gt) ** 2))),
        "delta1": float(np.mean(ratio < 1.25)),
    }
    for centre in (1.0, 3.0):
        band = (gt > centre - 0.25) & (gt < centre + 0.25)
        out[f"mae_{centre:g}m_cm"] = float(np.mean(err[band]) * 100) if band.sum() > 100 else None
    return out


def evaluate(session, pairs, seq, w, h, sparse_n, rng):
    inp = session.get_inputs()[0].name
    per_frame = {"oracle": [], "sparse": []}
    times = []
    for rgb_name, depth_name in pairs:
        img = Image.open(seq / rgb_name).convert("RGB")
        gt = np.asarray(Image.open(seq / depth_name), dtype=np.float32) / TUM_DEPTH_FACTOR
        x = preprocess(img, w, h)
        t0 = time.perf_counter()
        raw = session.run(None, {inp: x})[0][0]
        times.append(time.perf_counter() - t0)
        raw = np.asarray(Image.fromarray(raw).resize(img.size, Image.BILINEAR), dtype=np.float32)

        valid = (gt > VALID_MIN_M) & (gt < VALID_MAX_M)
        r, g = raw[valid], gt[valid]
        for mode in ("oracle", "sparse"):
            if mode == "oracle":
                a, b = fit_inverse_affine(r, g)
            else:
                idx = rng.choice(r.size, size=min(sparse_n, r.size), replace=False)
                a, b = fit_inverse_affine(r[idx], g[idx])
            inv = a * r + b
            ok = inv > 1.0 / VALID_MAX_M
            per_frame[mode].append(metrics(1.0 / inv[ok], g[ok]))
    summary = {}
    for mode, rows in per_frame.items():
        summary[mode] = {
            k: (float(np.mean([v for v in (row[k] for row in rows) if v is not None]))
                if any(row[k] is not None for row in rows) else None)
            for k in rows[0]
        }
    summary["ms_per_frame"] = float(np.median(times) * 1000)
    return summary


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seq", type=Path, required=True)
    ap.add_argument("--models", type=Path, required=True)
    ap.add_argument("--frames", type=int, default=60)
    ap.add_argument("--sparse-points", type=int, default=50)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--out", type=Path, default=Path("da2_tum_results.json"))
    args = ap.parse_args()

    pairs = associate(read_list(args.seq / "rgb.txt"), read_list(args.seq / "depth.txt"))
    step = max(1, len(pairs) // args.frames)
    pairs = pairs[::step][: args.frames]
    print(f"{args.seq.name}: {len(pairs)} frames evaluated")

    opts = ort.SessionOptions()
    opts.intra_op_num_threads = args.threads
    results = {}
    for model in sorted(args.models.glob("*.onnx")):
        session = ort.InferenceSession(str(model), opts, providers=["CPUExecutionProvider"])
        for label, w, h in CONFIGS:
            rng = np.random.default_rng(0)
            r = evaluate(session, pairs, args.seq, w, h, args.sparse_points, rng)
            results[f"{model.stem} | {label}"] = r
            o, s = r["oracle"], r["sparse"]
            fmt = lambda v: "  n/a" if v is None else f"{v:5.1f}"
            print(f"{model.stem:16s} {label:30s} oracle AbsRel={o['abs_rel']:.3f} δ1={o['delta1']:.3f} "
                  f"| sparse AbsRel={s['abs_rel']:.3f} 1m={fmt(s['mae_1m_cm'])}cm "
                  f"3m={fmt(s['mae_3m_cm'])}cm | {r['ms_per_frame']:.0f} ms")
    args.out.write_text(json.dumps({"sequence": args.seq.name, "frames": len(pairs),
                                    "sparse_points": args.sparse_points, "results": results}, indent=2))


if __name__ == "__main__":
    main()
