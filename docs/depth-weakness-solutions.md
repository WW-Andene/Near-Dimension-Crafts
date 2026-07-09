# Depth Pipeline — Weakness Engineering Solutions

**Project:** Near Dimension Crafts — Handy LiDAR Replacement  
**Date:** 2026-06-17  
**Scope:** Passive-only solutions. No torch, no flash, no active illumination.

---

## 1. Outdoor Use

### Problem

Direct sunlight overwhelms structured light and photometric depth: the SL
projection pattern becomes invisible in bright ambient conditions. DA2 was
predominantly trained on indoor data, so its inverse-depth distribution shifts
significantly outdoors where scene depth can range 0.5 m to 50 m. SfM scale
calibration works well indoors with bounded depth variance but drifts outdoors.
The rPPG heart-rate estimator collapses when global illumination swings from
clouds or moving shade.

### Solutions

**S1.1 — ALS-Gated Source Suppression**

The ambient light sensor (ALS) provides a single lux value read via
`SensorManager`. When lux exceeds a calibrated daylight threshold (~15 000 lux),
zero out `CH_SL` and `CH_PSP` in the arbiter and redistribute their weight to
`CH_XR` (ARCore) and `CH_DA2`. The SL projector is physically invisible outdoors;
continuing to run it wastes CPU and pollutes the fused output with noise. The
arbiter weights revert automatically when lux drops back below threshold. One
sensor read per second is sufficient — ALS polling is negligible CPU.

```
lux < 2 000   → normal weights
lux 2k–15k    → CH_SL × 0.5, CH_PSP × 0.5, surplus → CH_XR
lux > 15 000  → CH_SL = 0, CH_PSP = 0, surplus → CH_XR + CH_DA2
```

**S1.2 — Dual-Anchor Log-Linear DA2 Calibration**

A single linear scale factor (`da2Scale = arcoreDepth / da2InvDepth`) is
accurate only when scene depth is approximately uniform. Outdoors, a near
object (0.8 m) and a far wall (12 m) must both fit the same calibration.

Use two ARCore-grounded anchor points, one near and one far, to fit a
log-linear model:

```
metric_z = exp(a × da2_inv_depth + b)
```

Solve `a` and `b` from two anchor pairs using the closed-form log solution:

```
a = (ln(z_far) - ln(z_near)) / (inv_far - inv_near)
b = ln(z_near) - a × inv_near
```

Update anchors whenever ARCore provides a TRACKING frame with two scene points
that differ in depth by > 1 m. Lock the calibration once set; re-solve only when
ARCore re-acquires after loss.

**S1.3 — SLAM Scale Auto-Calibration**

When ARCore is TRACKING (metric grounded), continuously update the SfM scale
factor using an EMA:

```
sfmScale = sfmScale × (1 - β) + (arcoreMetricDepth / sfmDepth) × β
β = 0.05 (slow lock, ignores per-frame outliers)
```

Once the scale has converged (< 5% change over 30 frames), freeze it. If ARCore
loses tracking, the frozen scale keeps SfM output approximately metric for the
remainder of the session. This is the degradation ladder already described in
`SpatialLayer` — this solution automates the calibration rather than relying on a
preset default of 0.001.

**S1.4 — Differential rPPG**

Outdoor global illumination oscillates when clouds pass or the user moves between
sun and shade. Absolute green-channel rPPG collapses immediately. Replace the
absolute-value estimator with a frame-to-frame differential:

```
signal(t) = mean_green(t) - mean_green(t-1)
```

The cardiac waveform is still present in the derivative (it appears as a
first-derivative sine), and global illumination steps cancel out entirely because
they affect `t` and `t-1` equally. Bandpass filter (0.6–4 Hz, i.e. 36–240 BPM)
applied to the differential series. This requires no structural change to
`rPPGSource` — just a one-sample delay and subtraction before the bandpass.

---

## 2. Dark Environments

### Problem

At low luma the camera runs high ISO. DA2, given a noise-dominated input, produces
confident but wrong depth estimates — the model cannot distinguish high-frequency
noise from real surface texture. SL and photometric sources are equally blind.
ARCore may survive if the environment has sufficient ambient light for its visual
feature tracker, but its depth hardware has a lower SNR limit as well. The goal
is to extract maximum signal from available photons without disturbing the user.

### Solutions

**S2.1 — Luma-Gated Source Hierarchy**

Compute per-frame mean luma from the bitmap in `processBitmap()` — one
`Bitmap.getPixel` scan at 1/4 resolution is ~0.1 ms. Gate source weights in
three tiers:

| Mean luma | Action |
|---|---|
| > 0.25 (normal) | No change |
| 0.08–0.25 (dim) | DA2 confidence ceiling 0.65, SfM FAST threshold halved |
| < 0.08 (dark) | DA2 weight = 0, SfM suspended, ARCore primary |

The luma gate operates continuously in `recomputeArbiter()` — it is not a
user-visible mode, just an automatic weight adjustment.

**S2.2 — DA2 Luma-Proportional Confidence Scalar**

Within the arbiter, multiply the raw DA2 confidence by a luma-derived scalar
before it enters `srcSignals[CH_DA2]`:

```
da2_conf = da2_raw_conf × clamp(luma / 0.20f, 0f, 1f)
```

At luma 0.10 the DA2 signal is halved. At luma 0.04 it is one-fifth. The model
still runs (it is cheap), but its vote in the fusion is proportionally
discounted. ARCore and SfM (when they can still find features) inherit the
surplus trust.

**S2.3 — Intensity-Normalised Feature Detection**

Before passing a bitmap to `SlamLite`, apply a fast per-frame histogram stretch:

```
scale = 0.5f / max(0.001f, bitmap.meanLuma())
pixel_out = clamp(pixel_in × scale, 0, 1)
```

This is not gamma correction or tone mapping — it is a linear stretch that
amplifies the available contrast without clipping. The Harris corner detector in
SlamLite then finds features that would be invisible in the raw dark image.
Landmark detection (MediaPipe) operates on the original bitmap — only SlamLite
receives the stretched version. No added light; better use of existing contrast.

**S2.4 — Multi-Frame Noise Averaging (Stationary Mode)**

When SlamLite detects near-zero optical flow (mean feature displacement < 2 px
over 10 consecutive frames — device held still), switch DA2 accumulation from
EMA to arithmetic mean over N frames:

```
N = 16
averaged_depth[i] = Σ(frame[k][i]) / N  for k in last N frames
```

Read noise in CMOS sensors is statistically independent frame-to-frame; averaging
16 frames yields 4× SNR improvement — equivalent to two f-stops of additional
light. The averaged result is held until optical flow resumes.

Constraint: N-frame latency (~530 ms at 30 fps). Acceptable for stationary use
(architectural survey, posed scanning, biometric capture). The SpatialFrame gets
a `stationaryDepth: FloatArray?` field; when non-null, it replaces the live DA2
per-block output in downstream consumers.

**S2.5 — Graceful Degradation HUD**

When luma < 0.08, set `sourceLabel = "LOW LIGHT — HANDS ONLY"` in `SpatialState`.
Hand landmark tracking (MediaPipe) continues with near-full accuracy in dim
conditions because the model operates on a high-contrast ROI with its own
internal normalisation. Metric grounding via ARCore also continues if any feature
texture is visible. The degradation is *real* and should be communicated rather
than hidden.

---

## 3. Long-Range Detection

### Problem

DA2 is most accurate at 0.3–3 m. Beyond 3 m inverse-depth values compress
significantly and small model errors translate to large metric errors. ARCore's
ToF hardware (where present) is rated to ~5 m. SfM triangulation accuracy drops
proportionally to range squared for a fixed camera baseline. Hand tracking itself
remains accurate regardless of range, but attaching metric depth to distant scene
geometry is unreliable with the current single-scale calibration.

### Solutions

**S3.1 — Dual-Lens Stereo (Where Available)**

Modern phones carry two or three rear cameras with a physical baseline of 15–30 mm
(wide + telephoto) or 10–20 mm (wide + ultra-wide). Stereo disparity gives
metric depth without any depth hardware or model inference:

```
depth = (baseline × focal_length) / disparity_px
```

At 5 m range with a 25 mm baseline and 12 MP sensor (focal ≈ 4000 px equiv.),
disparity ≈ 20 px — reliably detectable. At 10 m, disparity ≈ 10 px — still
measurable with sub-pixel refinement.

**Implementation path:** Open the second camera via Camera2 in slave mode
(synchronized shutter via `CaptureRequest.CONTROL_SYNC_FRAMEWORK`). Rectify the
stereo pair using pre-calibrated intrinsics (one-time factory calibration stored
in device EXIF or computed at app startup with a chessboard frame). Compute
disparity via semi-global matching on a 320×240 downscale. Feed the resulting
sparse-to-dense disparity map as a new `CH_STEREO` source with the current
weight (0.01 → raise to 0.12 for the >3 m regime).

Activation: enabled only when `ALS < 15 000` (not needed outdoors where ARCore
dominates) and ARCore depth is absent or stale.

**S3.2 — Hierarchical Depth Grid (Spatial Pyramid)**

Replace the flat 8×6 block grid with a three-level pyramid that allocates
precision where it matters:

| Zone | Block size | Coverage | Block count |
|---|---|---|---|
| Near (0–2 m) | 2×2 (fine) | Center 60% of frame | 32 blocks |
| Mid (2–5 m) | 4×4 (medium) | Full frame | 16 blocks |
| Far (> 5 m) | 8×8 (coarse) | Full frame | 4 blocks |

Total: 52 blocks (vs 48 flat). Near-field retains full spatial resolution for
hand tracking. Far-field uses coarse blocks because long-range depth estimation
has inherently lower SNR regardless of block resolution.

The zone boundaries are determined by the DA2 inverse-depth histogram: the median
inverse depth divides near from mid, and the 10th percentile divides mid from far.
This adapts automatically — if the user is close to a wall, all three zones
compress into a small metric range.

**S3.3 — Far-Plane Log-Linear Recalibration**

When ARCore detects a plane (floor, wall, ceiling) at > 3 m via the plane fitting
already running in `PlaneFitter`, use that plane's metric distance as the far
anchor in the dual-anchor calibration (S1.2). This extends accurate DA2
calibration into the 3–15 m range automatically, without requiring the user to
point at a specific reference object.

**S3.4 — Outdoor Scene Context Scaling**

When ALS > 5 000 lux (outdoor), multiply the DA2 inverse-depth output by a
global scene-context scale factor of 0.35 before calibration. This is a
prior: outdoor scenes have a depth distribution centered ~3× further than
indoor scenes. The scale factor shifts the DA2 working range from [0.3–3 m] to
[1–10 m] before the log-linear fine calibration takes over. One constant,
applied pre-calibration, with no model retraining required.

---

## 4. Fast-Moving Scene Reconstruction

### Problem context

The phone is essentially stationary — held or mounted. The moving subjects are
the user's hands and body. "Fast motion" therefore means fast-moving foreground
subjects, not camera shake. The consequences are:

- Temporal EMA depth blending introduces ghosting at moving hand edges
- MediaPipe hand tracking already handles fast hand motion — it is depth that lags
- SfM feature matching breaks when subject motion causes large foreground
  displacement between frames
- SL/photometric depth has half-frame rolling shutter latency at moving edges

### Solutions

**S4.1 — Motion-Adaptive EMA α**

Current EMA uses a fixed α for all pixels. Instead, compute per-pixel optical
flow magnitude from SlamLite's feature displacement field. Scale α per-pixel:

```
α(x,y) = α_min + (α_max - α_min) × clamp(flow_mag(x,y) / FLOW_THRESH, 0, 1)
α_min = 0.20   (heavy smoothing when stationary)
α_max = 0.80   (near-instant update when fast motion)
FLOW_THRESH = 15 px/frame
```

Where SlamLite provides only sparse flow (feature points), bilinearly interpolate
to a dense flow field before applying the per-pixel α map. Fast-moving hand
regions snap to the current frame; static background retains aggressive smoothing.

**S4.2 — Optical Flow-Warped EMA**

The standard EMA blends `current` with `previous` at the same pixel location.
This produces smearing at moving object boundaries because the "memory" of the
hand's previous position is mixed with the current background.

Fix: warp the previous EMA frame using the optical flow vectors before blending:

```
ema_warped(x,y) = ema_prev(x - flow_x(x,y), y - flow_y(x,y))
ema_new(x,y)   = α × current(x,y) + (1-α) × ema_warped(x,y)
```

The hand's historical depth now moves with the hand. Ghosting is eliminated at
the cost of one bilinear warp per frame (~0.8 ms at 128×96 on ARMv8).

**S4.3 — Pyramid Lucas-Kanade Feature Tracking**

Standard single-scale LK fails when inter-frame feature displacement exceeds
~5 px. Hand speeds of 2 m/s at 0.5 m range produce ~60 px/frame at 30 fps —
well beyond the capture radius.

Pyramid LK builds a Gaussian image pyramid (typically 4 levels) and tracks
features coarse-to-fine. At the coarsest level, 60 px displacement becomes 8 px
— within the standard LK window. Tracked at coarse level, the estimate is
refined at each finer level. Pyramid LK handles displacements up to ~80 px/frame
reliably.

SlamLite's feature tracker replaces its current single-scale implementation with
pyramid LK. The pyramid can be reused across `slam.process()` and the optical
flow computation for S4.1/S4.2 — computed once, used three times.

**S4.4 — Per-Landmark Depth Freshness Tagging**

Each landmark's depth estimate is tagged with the frame timestamp at which it
was last reliably computed. On each new frame, before serving the depth value:

```
if (current_frame - depth_frame[landmark] > STALE_FRAMES) {
    use DA2 bilinear estimate at landmark (nx, ny) instead
}
STALE_FRAMES = 3  (100 ms at 30 fps)
```

This ensures that a fast-moving hand whose depth was measured 4 frames ago at
a now-vacated position does not mislead downstream consumers. DA2's monocular
estimate is lower accuracy but always current — a better fallback than a stale
metric value.

The staleness check is per-landmark, not per-frame. Index-tip moves fast; wrist
moves slowly. Each of the 21 landmarks has its own freshness counter.

---

## Priority Matrix

| Solution | Effort | Impact | Scenario | Phase |
|---|---|---|---|---|
| S2.2 — DA2 luma scalar | XS | High | Dark | A |
| S2.1 — Luma-gated hierarchy | S | High | Dark | A |
| S1.1 — ALS-gated SL/PSP | S | High | Outdoor | A |
| S1.4 — Differential rPPG | S | Medium | Outdoor | A |
| S4.1 — Motion-adaptive EMA α | S | High | Fast motion | A |
| S2.3 — Intensity-normalised features | S | Medium | Dark | A |
| S1.3 — SLAM scale auto-cal | M | High | Outdoor | B |
| S3.4 — Outdoor scene context scaling | XS | Medium | Long-range | B |
| S4.2 — Flow-warped EMA | M | High | Fast motion | B |
| S4.3 — Pyramid LK | M | High | Fast motion | B |
| S4.4 — Per-landmark depth freshness | M | Medium | Fast motion | B |
| S1.2 — Dual-anchor log calibration | M | High | Outdoor/Long | B |
| S3.3 — Far-plane recalibration | M | Medium | Long-range | B |
| S3.2 — Hierarchical depth grid | L | High | Long-range | C |
| S2.4 — Multi-frame noise averaging | M | Medium | Dark | C |
| S2.5 — Graceful degradation HUD | XS | UX | Dark | C |
| S3.1 — Dual-lens stereo | XL | High | Long-range | C |

**Effort:** XS < 1 day · S 1–2 days · M 3–5 days · L 1 week · XL 2+ weeks  
**Phase A:** Quick wins, no architectural change  
**Phase B:** Medium complexity, significant pipeline extensions  
**Phase C:** Structural or hardware-dependent changes  
