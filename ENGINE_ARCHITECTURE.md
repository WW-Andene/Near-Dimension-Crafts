# Engine reliability spec: cross-layer impact map

## 0. What this document is, and a correction to its own previous version

This document previously (commit `8214fd4`) claimed `AppViewModel.ensureFullBodyCollector()`
actively double-invokes `BodyRetargeter.retarget()` alongside `SpatialFrameProducer`. **That
claim was wrong** — verified this pass via a dedicated dependency-mapping agent plus
`git log --all -S"ensureFullBodyCollector()"` across the whole repo history: the function is
never called anywhere, in any commit. It's dead code, not a live bug. See §2.1 for what it
actually is (a real defect, just a different one than previously stated).

The lesson, not just the correction: a suspicious-looking function is not evidence of a live
bug until you've confirmed something actually calls it. Everything below was checked by (a)
reading the full method body, not the signature, and (b) tracing every call site, not assuming
one exists because the code "looks wired up." Three research passes (Core, Translation,
Application layers, run in parallel) did this systematically instead of opportunistically —
this version is the synthesis.

## 1. Layer model (unchanged, still verified against source)

```
util (no deps)
├── camera      → util
├── tracking    → util
├── depth       → util, camera, tracking
├── scanner     → util, tracking, depth
├── mocap       → util, tracking, depth, scanner
├── render      → util, tracking, mocap
├── export      → util, tracking, depth, scanner, mocap
└── app         → all of the above
```

| Layer | Modules | Input → Output |
|---|---|---|
| **Core** | `camera`, `depth` | Camera sensor → depth/spatial signal, no semantic labels |
| **Translation** | `tracking`, `mocap`, `scanner` | Camera frame → keypoints → joint rotations / 3D scan |
| **Application** | `render`, `export`, `app` | Structured motion/geometry → draw calls, OSC, BVH, UI |

`depth` importing `tracking.HandLandmarks` (2 files, coordinate-math helpers) is a data-type
dependency, not a behavioral one — not revisited here, still not worth restructuring.

## 2. Confirmed live bugs (currently executing, verified by reading the actual code path)

Ranked by how directly each explains a symptom already reported (lag, inaccuracy) or by
correctness risk if left alone.

### 2.1 `FreeformScanner.update()` called twice per frame during every active freeform scan

- `AppViewModel.kt:525-533` calls it directly, gated on `scanState.value.freeformActive`.
- `SpatialFrameRouter.kt:226-236` calls it again, gated on `router.isFreeformActive`.
- `startFreeformScan()` (`AppViewModel.kt:941-943`) sets **both** gates to `true` in the same
  call — there is no scenario where one fires without the other.

`FreeformScanner.update()` is heavily stateful: it mutates `prevLms` (motion-magnitude gating
against the previous call), `acceptedOrientations`/`coverageBuckets`/`coveredBuckets`
(viewpoint-novelty and auto-complete coverage tracking), and on acceptance appends to its own
`capturedFrames`/`biometricFrames`/`capturedBitmaps`. Calling it twice per actual new hand
frame means the motion gate compares frame N against itself instead of N-1 on the second call,
and coverage buckets/novelty state step twice per real frame. This can make freeform scans
complete early, under-cover the intended viewpoint spread, or reject frames a single-call
version would have accepted — directly affecting scan quality, not just performance. This is
the same architectural shape as the hand-retarget duplicate fixed earlier this session
(two owners of one stateful pipeline), just found in the scan-capture path instead of the
render/retarget path.

### 2.2 `renderer.handsData` / `renderer.mirrorX` written from two independent collectors every frame

- `SpatialFrameRouter.kt:117-121`, from `frame.primaryHand/secondaryHand` (post-`assembleFrame`).
- `AppViewModel.kt:402-403`, from the raw `hands` value in the same `handPipeline.processed`
  collector that calls `producer.assembleFrame(...)`.

Both run once per hand-pipeline frame. Since both ultimately derive from the same landmark
data, the visible skeleton is unlikely to look wrong — but it's two writers to one field with
no defined ordering, and the values aren't guaranteed identical (router's version is
post-assembly, filtered/shaped by `SpatialFrame`; AppViewModel's is the raw list). Redundant
work at minimum; a source of subtle one-frame flicker at worst if the two ever diverge.

### 2.3 `depthConfidence` written from three independent, unsynchronized coroutines

`AppViewModel.kt:373` (`producer.spatialLayer.state.collect`), `:508` and `:562` (inside the
posed-scan and freeform-scan depth-integration branches of the main hand-pipeline collector),
and `:777` (`switchCamera`). No ordering guarantee between them — whichever coroutine's write
lands last wins, regardless of which value is actually current. Low functional risk (it's a
displayed confidence number), but a genuine, unguarded race.

### 2.4 `restJointPositions` written from two independent coroutines, not even `@Volatile`

`AppViewModel.kt:1019` (`processFreeformScan`, its own `Dispatchers.Default` coroutine) and
`:1109` (`processScan`, a separate `Dispatchers.Default` coroutine) both write this plain
`var FloatArray?` — no `@Volatile`, no synchronization. The two are mutually exclusive by user
workflow (you can't run both scan types at once) but nothing in the code enforces that; a
future change that makes them concurrent would have an actual visibility bug (JMM does not
guarantee a plain `var` write on one thread is visible to a read on another), not just a race
in the "logically shouldn't happen" sense.

### 2.5 Cross-cadence staleness: Core writes some fields at raw-frame rate, Translation reads them at hand-inference rate

`SpatialFrameProducer.assembleFrame()` runs once per `handPipeline.processed` emission
(throttled, adaptive rate). But it reads `getMeanArbiterWeights()`, `metricMode`, and
`rppg.bpm`/`rppg.amplitude` — all of which are written by Core-layer code running at raw
camera-frame rate (`processAuxSources`, `ArcoreCallback.onPoints`, `SpatialLayer.processBitmap`)
on a different cadence and, in ARCore's case, its own callback thread. The values embedded in
a given `SpatialFrame` are "whatever Core last computed," not necessarily aligned to the exact
camera frame the bundled hand landmarks came from. Not a crash risk, but worth knowing before
trusting frame-to-frame correlation between depth-confidence/rPPG data and hand-landmark data
inside one `SpatialFrame`.

### 2.6 `SlamLite`'s optical flow can be stale relative to the DA2 frame it calibrates

`SpatialLayer.processBitmap()` calls `slam.process(bitmap)` **every raw camera frame,
unconditionally**, then immediately assigns `fusedDepth.da2.externalFlowMag/NX/NY` from it.
`DepthAnythingSource.processAsync` drops frames it can't keep up with (`AtomicBoolean`
busy-flag, by design — see the class's own comment). So by the time DA2 actually reads
`externalFlowMag/NX/NY` inside its flow-warp calibration step, those fields may already have
been overwritten by SLAM processing of a *later* raw frame that has nothing to do with the
bitmap pair DA2 is currently blending. There's no frame-id/timestamp correlation between the
two. This is an internal Core-layer numerical-correctness gap in the calibration math, not
just a performance concern — DA2's depth output (which also feeds hand-landmark Z-correction)
could be calibrated against the wrong motion estimate under load.

### 2.7 Camera frame stream has two uncorrelated consumers that can each drop different frames

`CameraFrameProvider.frames` (`MutableSharedFlow(extraBufferCapacity = 2)`, `tryEmit` —
non-suspending, silently drops on backpressure) has exactly two collectors: `SpatialFrameProducer.init()`
(the full tracking/depth pipeline) and a second one added directly in `AppViewModel.kt:355-358`
that feeds only the GL camera passthrough background (`ARRenderer.submitCameraFrame`). Under
load, each collector can miss different frames — meaning the background camera image the user
sees and the depth/tracking data computed for "the current frame" are not guaranteed to be the
same physical camera frame.

## 3. Confirmed dead/orphaned code (not bugs at runtime, but the same failure shape: intent left behind without cleanup)

### 3.1 `AppViewModel.ensureFullBodyCollector()` is never called

Corrects §0. Since nothing calls it: `latestFullBodyFrame` and `latestBodyRetargetResult` are
permanently `null`. Concrete effects of that:
- `bodyWristHint`/`bodyWristVis` in `AppViewModel`'s own OSC-velocity/live-mesh retarget path
  (`AppViewModel.kt:408-418`) always read `null` from `latestBodyRetargetResult.value` —
  silently never applies body-wrist alignment on that path, regardless of whether body tracking
  is actually running (live, correctly, through `SpatialFrameProducer`'s own internal collector).
- `perfMonitor.updateQuality(body = ...)` (`AppViewModel.kt:449`) always reports `0f` body
  confidence to the HUD, independent of real body-tracking state.
- `CompositeGestureClassifier.classify(frame: FullBodyFrame, slot: Int)` — the overload shaped
  to consume `latestFullBodyFrame` — is never called anywhere; the only live gesture-classify
  call site uses the other overload (`classify(gesture, faceExpressions)`,
  `AppViewModel.kt:1419`). If full-body-context gesture classification was intended to work,
  it currently does not.

### 3.2 `renderer.scanCloudPoints` has no writer anywhere in the repo

Read at `ARRenderer.kt:260-263` (`onDrawFrame`), never written — dead field, always empty.

### 3.3 `SpatialFrameRouter`'s posed-scan capture branch is currently unreachable

`route()`'s `capturedFrames`/`biometricFrames` append (`SpatialFrameRouter.kt:213-223`) is
gated on `isScanActive && !isFreeformActive` — but `router.isScanActive` is only ever set
`true` together with `router.isFreeformActive` (always both, never `isScanActive` alone for a
plain posed scan). So this branch never fires today; `AppViewModel.kt:475-480` is the sole
active writer of those same lists (they're literally the same `MutableList` objects, exposed
via delegate properties). Latent: if a future change sets `isScanActive` independently for
posed scans (a reasonable-looking fix on its own), both would start appending to the same list
— a new instance of §2.1's shape, introduced by someone fixing something else without knowing
this branch exists.

### 3.4 `renderer.latestRetargetResult` has two legitimate writers, mode-gated (not a bug, but worth naming explicitly so it isn't "fixed" into one)

`SpatialFrameRouter.kt:124` (SEND mode: live retarget) and `AppViewModel.kt:1484`
(`oscReceiver.frames.collect` inside `startOscReceiving` — RECEIVE mode: puppeteering from a
remote OSC source). These are intentionally mutually exclusive by feature, not concurrent.
Documented here specifically so a future pass applying §2's "one writer" rule mechanically
doesn't collapse this into a bug that isn't one.

## 4. The rule (kept from the previous version, still the organizing principle)

> Every piece of state read from more than one place has exactly one place that writes it.
> Every stateful per-frame update function is called from exactly one call site.

§2 and §3 are the current, checked list of where the codebase violates or has drifted from
this rule. This list is a snapshot from this research pass — treat any future mismatch between
this document and the code as a bug in whichever one is behind, not something to leave silently
drifting (that drift is what produced §3's dead code and the stale doc comments found earlier
this session).

## 5. Concurrency contract (kept, still accurate)

| Work category | Dispatcher | Rationale |
|---|---|---|
| Camera acquisition, YUV/RGBA conversion | CameraX's own executor | Owned by CameraX |
| MediaPipe hand/body/face inference | GPU delegate → CPU fallback (verified in all three of `HandTracker`/`BodyPipeline`/`FacePipeline`) | Correct, unmodified |
| Core depth-fusion channels (DA2, SLAM, stereo, RS-stereo, PSP) | `Dispatchers.Default` | Appropriate for CPU-bound batch work, but see §2.6-2.7 for cross-consumer correlation gaps this sharing introduces |
| Frame routing to renderer/OSC/motion-capture (`SpatialFrameRouter`) | Dedicated single thread (fixed this session) | Latency-sensitive; must not share a pool with unbounded CPU-bound work |

## 6. Degradation contract (kept, still verified)

Every depth/tracking source follows the same `isAvailable`-checked-by-every-consumer shape:
`StereoDepthSource`, `DepthAnythingSource`, ARCore (`FusedDepthSource.kt:376`, falls back to
"SfM+Photo only"), and MediaPipe's GPU→CPU delegate fallback. Consistent, load-bearing —
follow it for any new source.

## 7. Verification & enforcement

1. **Instance/call-site counts**, re-runnable any time:
   - `grep -rn "BoneRetargeter(\|BodyRetargeter("` — expect the counts in §2 of the prior
     research (2 legitimate `BoneRetargeter` owners + 1 test; exactly 1 `BodyRetargeter`
     construction, shared by a live writer and a dead one).
   - `grep -rn "freeformScanner.update\|scanner.update"` — currently 2 and 1 call sites
     respectively; the freeform count should become 1 once §2.1 is fixed.
   - `grep -rn "ensureFullBodyCollector"` — currently definition-only; either call it or
     delete it, don't leave it defined-but-unreferenced.
2. **CI is compile-only.** Every claim above was verified by reading method bodies and tracing
   call sites directly (three parallel research passes, cross-checked against each other and
   against this document's own prior, partially-incorrect claims) — not by assumption, and not
   by trusting existing comments (§0's correction exists precisely because a comment/claim
   without a traced call site was wrong once already).
3. Runtime behavior (does §2.1 actually degrade scan quality, does §2.7 actually cause a visible
   frame mismatch) can only be confirmed by testing on a real device — no physical device is
   available in this environment.

## 8. Explicitly out of scope

- Device-specific tuning (NNAPI/GPU-delegate speculation, resolution/quality downgrades) — the
  NNAPI attempt this session regressed both performance and accuracy and was reverted.
- Any code changes. This document is research and assessment only, per explicit instruction
  this pass — §2/§3 are a prioritized findings list for a future implementation pass, not a
  changelog of what was done.
