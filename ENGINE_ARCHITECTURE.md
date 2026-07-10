# Engine Architecture & Reliability

## 1. Purpose

A cross-layer map of what depends on what in this codebase, an assessment of where it currently
violates its own implied design, and the rules that assessment was checked against. Not a
changelog — findings here are current state, verified by reading full method bodies and tracing
real call sites, not by trusting names, doc comments, or how something "looks wired up." That
distinction matters concretely: an earlier pass through this same material called two genuinely
unfinished features "dead code" before a second look (§7) showed they were one connection short
of working, not abandoned — see §3.2 for the rule that mistake produced.

Any future mismatch between this document and the code is a bug in whichever one is behind —
not something to leave silently drifting. Drift of exactly this kind (comments describing a
migration as complete when it wasn't) produced several of §4's findings.

## 2. Layer Model

Module dependency graph, verified against `settings.gradle` and each module's `build.gradle`:

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
dependency, not a behavioral one — Core doesn't depend on Translation's pipeline running, it
just reuses the landmark struct's shape for its own point-cloud math. Not worth restructuring.

## 3. Principles

Everything in §4–§9 was checked against these two rules. Apply them to any future change.

### 3.1 Single ownership

> Every piece of state read from more than one place has exactly one place that writes it.
> Every stateful per-frame update function is called from exactly one call site.

### 3.2 Nothing gets deleted without a second, independent review pass

Before deleting anything because "nothing calls this" or "this looks unused":
1. **Check what else the file/symbol carries.** A thing can look dead by one measure (an unused
   function) while the same file holds something load-bearing (a shared constant, a type used
   elsewhere) — see §11.2 for the confirmed case this already happened.
2. **Check whether "nothing calls this" means "abandoned" or "not wired up yet."** Read the
   candidate's own doc comments and naming (guard flags, "ensure"/"lazy" naming, TODO markers)
   for evidence it was designed to be called from somewhere that never got written — see §7 for
   two cases just caught this way.
3. Only after both checks pass does "delete" become the answer instead of "finish" or
   "deliberately retire, on purpose, out loud."

This rule exists because an earlier pass through this document nearly misclassified §7's two
features as safe to delete on first read. See §11 for the historical cases behind it.

## 4. Live bugs (currently executing, ranked by impact)

### 4.1 `FreeformScanner.update()` called twice per frame during every active freeform scan

- `AppViewModel.kt:525-533` calls it directly, gated on `scanState.value.freeformActive`.
- `SpatialFrameRouter.kt:226-236` calls it again, gated on `router.isFreeformActive`.
- `startFreeformScan()` (`AppViewModel.kt:941-943`) sets **both** gates to `true` in the same
  call — there is no scenario where one fires without the other.

`FreeformScanner.update()` is heavily stateful: it mutates `prevLms` (motion-magnitude gating
against the previous call), `acceptedOrientations`/`coverageBuckets`/`coveredBuckets`
(viewpoint-novelty and auto-complete coverage tracking), and on acceptance appends to its own
`capturedFrames`/`biometricFrames`/`capturedBitmaps`. Calling it twice per actual new hand frame
means the motion gate compares frame N against itself instead of N-1 on the second call, and
coverage buckets/novelty state step twice per real frame. This can make freeform scans complete
early, under-cover the intended viewpoint spread, or reject frames a single-call version would
have accepted — directly affecting scan quality, not just performance. Same architectural shape
as the hand-retarget duplicate fixed earlier this session (two owners of one stateful pipeline),
found here in the scan-capture path instead of the render/retarget path. Fix direction: §7.3.

### 4.2 `renderer.handsData` / `renderer.mirrorX` written from two independent collectors every frame

- `SpatialFrameRouter.kt:117-121`, from `frame.primaryHand/secondaryHand` (post-`assembleFrame`).
- `AppViewModel.kt:402-403`, from the raw `hands` value in the same `handPipeline.processed`
  collector that calls `producer.assembleFrame(...)`.

Both run once per hand-pipeline frame. Since both ultimately derive from the same landmark
data, the visible skeleton is unlikely to look wrong — but it's two writers to one field with no
defined ordering, and the values aren't guaranteed identical (router's version is post-assembly,
filtered/shaped by `SpatialFrame`; AppViewModel's is the raw list). Redundant work at minimum; a
source of subtle one-frame flicker at worst if the two ever diverge.

### 4.3 `depthConfidence` written from three independent, unsynchronized coroutines

`AppViewModel.kt:373` (`producer.spatialLayer.state.collect`), `:508` and `:562` (inside the
posed-scan and freeform-scan depth-integration branches of the main hand-pipeline collector),
and `:777` (`switchCamera`). No ordering guarantee between them — whichever coroutine's write
lands last wins, regardless of which value is actually current. Low functional risk (it's a
displayed confidence number), but a genuine, unguarded race.

### 4.4 `restJointPositions` written from two independent coroutines, not even `@Volatile`

`AppViewModel.kt:1019` (`processFreeformScan`, its own `Dispatchers.Default` coroutine) and
`:1109` (`processScan`, a separate `Dispatchers.Default` coroutine) both write this plain `var
FloatArray?` — no `@Volatile`, no synchronization. The two are mutually exclusive by user
workflow (you can't run both scan types at once) but nothing in the code enforces that; a future
change that makes them concurrent would have an actual visibility bug (JMM does not guarantee a
plain `var` write on one thread is visible to a read on another), not just a race in the
"logically shouldn't happen" sense.

## 5. Timing & correlation gaps (values from different cadences combined as if simultaneous)

### 5.1 Cross-cadence staleness: Core writes some fields at raw-frame rate, Translation reads them at hand-inference rate

`SpatialFrameProducer.assembleFrame()` runs once per `handPipeline.processed` emission
(throttled, adaptive rate). But it reads `getMeanArbiterWeights()`, `metricMode`, and
`rppg.bpm`/`rppg.amplitude` — all written by Core-layer code running at raw camera-frame rate
(`processAuxSources`, `ArcoreCallback.onPoints`, `SpatialLayer.processBitmap`) on a different
cadence and, in ARCore's case, its own callback thread. The values embedded in a given
`SpatialFrame` are "whatever Core last computed," not necessarily aligned to the exact camera
frame the bundled hand landmarks came from.

### 5.2 `SlamLite`'s optical flow can be stale relative to the DA2 frame it calibrates

`SpatialLayer.processBitmap()` calls `slam.process(bitmap)` **every raw camera frame,
unconditionally**, then immediately assigns `fusedDepth.da2.externalFlowMag/NX/NY` from it.
`DepthAnythingSource.processAsync` drops frames it can't keep up with (`AtomicBoolean` busy-flag,
by design). So by the time DA2 actually reads `externalFlowMag/NX/NY` inside its flow-warp
calibration step, those fields may already have been overwritten by SLAM processing of a *later*
raw frame that has nothing to do with the bitmap pair DA2 is currently blending — no
frame-id/timestamp correlation between the two. This is an internal Core-layer
numerical-correctness gap, not just performance: DA2's depth output (which also feeds
hand-landmark Z-correction) could be calibrated against the wrong motion estimate under load.

### 5.3 Camera frame stream has two uncorrelated consumers that can each drop different frames

`CameraFrameProvider.frames` (`MutableSharedFlow(extraBufferCapacity = 2)`, `tryEmit` —
non-suspending, silently drops on backpressure) has exactly two collectors:
`SpatialFrameProducer.init()` (the full tracking/depth pipeline) and a second one added directly
in `AppViewModel.kt:355-358` that feeds only the GL camera passthrough background
(`ARRenderer.submitCameraFrame`). Under load, each collector can miss different frames — the
background camera image the user sees and the depth/tracking data computed for "the current
frame" are not guaranteed to be the same physical camera frame.

### 5.4 `sfmScale` calibration has no explicit staleness bound between ARCore callbacks

`FusedDepthSource.updateScaleCalibration()` (called only from `ArcoreCallback.onPoints`)
computes a displacement ratio between the current and previous ARCore callback to refine
`sfmScale`. It gates on displacement *magnitude* (`arcoreDisp < 0.001f || sfmDisp < 0.5f` →
skip) but not on *time* between the two callbacks — if ARCore callbacks are irregular under
load, a large but old displacement could be compared against fresh SfM data, or vice versa. Not
confirmed to cause a visible problem, just the same "adjacent-in-code, not necessarily
adjacent-in-time" assumption gap as §5.1.

### 5.5 `feedFarPlaneAnchor()` compares a newly detected plane against a possibly-old ARCore depth reading

`FusedDepthSource.feedFarPlaneAnchor(planeDistM)` (`FusedDepthSource.kt:432`, called from
`SpatialFrameProducer.kt:282` when a detected plane is >3m away) uses `lastArcoreMeanDepth` as
its "near anchor" reference. `lastArcoreMeanDepth` is written only inside `ArcoreCallback.onPoints`
(`FusedDepthSource.kt:660`), on ARCore's own callback cadence — not necessarily the same moment
the far-plane detection that triggers this call happened. Same category as §5.4.

## 6. Documentation/reality mismatches and wasted work

### 6.1 `PointCloudStore`'s own doc comment describes a threading model its actual callers don't follow

`util/PointCloudStore.kt:14` states `snapshot()` "is called from the GL thread" — its actual
callers are `AppViewModel.kt:487,490,542,545`, all inside the `handPipeline.processed.collect`
coroutine (posed- and freeform-scan depth-integration branches), not the GL thread. Whether the
class's internal synchronization is adequate for the actual calling thread wasn't evaluated
here — the point is the doc comment asserts a contract that isn't what's happening, the same
failure shape as the stale "AppViewModel is a coordinator only" comment found and fixed earlier
this session. Worth a deliberate check of `PointCloudStore`'s internal locking against its *real*
callers, not its documented ones.

### 6.2 `rPPGSource.snsProxy` is computed every warm camera frame and read by nothing

`rPPGSource.kt:186` computes `snsProxy = sqrt(variance / ampHistory.size)` every frame once
`rPPGSource` is warm (~4s after start). Grepped the whole repo for `.snsProxy` — zero external
readers. Small, but genuinely wasted work on every frame for the lifetime of the app, not gated
behind any feature flag the way this session's CLAHE fix gated a similar always-on cost.

## 7. Incomplete/disconnected features — do not delete

**These are not dead code.** Re-reading each one's own doc comments and surrounding design
(guard-flag patterns, a UI toggle already wired to a working render path, a classifier overload
built specifically to consume one of these) showed they are fully- or mostly-built features
missing one connection, not leftover cruft. §3.2 is the rule this produced.

### 7.1 `AppViewModel.ensureFullBodyCollector()` is built but never started — one call site short of working

`fullBodyCollectorStarted` (a guard flag, `AppViewModel.kt:1326`) and the function's own name
("ensure...") are the standard shape for a lazily-started, idempotent collector meant to be
triggered from more than one entry point. `enableBodyTracking()`'s doc comment
(`AppViewModel.kt:1362-1366`) explicitly promises: *"Body landmarks are merged into
`[latestFullBodyFrame]` every hand-pipeline frame"* — but `enableBodyTracking()`'s body
(`AppViewModel.kt:1368-1372`) never calls `ensureFullBodyCollector()`. The feature this builds —
`FullBodyFrame` merging pose+face+both hands, feeding
`CompositeGestureClassifier.classify(FullBodyFrame, slot: Int)`, an overload built specifically
for it and otherwise unused — was designed and implemented and is missing exactly the call that
starts it. Concrete effect of the gap: `bodyWristHint` in `AppViewModel`'s own OSC-velocity/
live-mesh path always reads `null`, `perfMonitor`'s body-confidence HUD metric is always `0f`,
and full-body-context gesture classification never runs — not because these don't work, but
because they're never started.

**Open product question, not a technical one**: is full-body-context gesture classification
still wanted? If yes, the fix is adding the missing call — and resolving the shared-`BodyRetargeter`-instance
question: `SpatialFrameProducer` already retargets body independently, so starting this collector
too would reintroduce a live double-invocation, meaning the actual fix is likely "point this
collector at the producer's own `_latestBodyResult` output instead of calling `.retarget()`
again," not just adding the missing call verbatim. If no, that's a deliberate call to make and
document, not something to silently delete as unused.

### 7.2 `renderer.scanCloudPoints` — a working, wired UI toggle with no data feed

`ARRenderer.showCloud` is toggled live from `AppViewModel.kt:763` and gates a fully functional
render path (`ARRenderer.kt:259-263`: `depthCloudRenderer.updateAndDraw(...)`) — the toggle and
the renderer both work. Nothing anywhere populates `scanCloudPoints` itself, so the toggle
currently does nothing visible. Reads as a live point-cloud preview feature (likely meant to
sample `spatialLayer.fusedDepth.store.snapshot()`, the same source `AppViewModel`'s scan-capture
code already reads) whose render half was built and whose data-feed half was never connected —
same shape as §7.1: finish it or make a deliberate call to retire the toggle, don't delete the
renderer path as "unused."

### 7.3 `SpatialFrameRouter`'s posed-scan capture branch — an asymmetric migration, not dead weight

Different shape again. `route()`'s `capturedFrames`/`biometricFrames` append
(`SpatialFrameRouter.kt:213-223`) is gated on `isScanActive && !isFreeformActive`, but
`startScan()` (posed scans, `AppViewModel.kt:820-887`) never touches `router.isScanActive` at
all — it manages capture entirely inline, the way the codebase apparently worked before
`SpatialFrameRouter` existed. `startFreeformScan()` sets `router.isScanActive`/`isFreeformActive`
*and* still runs its own inline capture — freeform scanning was migrated to the router but its
old inline path was never removed (§4.1), while posed scanning was never migrated at all. The
consistent fix for both is the same move: make `SpatialFrameRouter` the single owner of capture
for *both* scan types — wire `isScanActive` in `startScan()` too, then delete `AppViewModel`'s
inline capture for both posed and freeform. Not "delete the router's dead branch" — the router's
branch is the correct future state; the inline paths are what should go, once verified equivalent.

## 8. Intentional multi-writer patterns — not bugs, don't "fix" into a violation

### 8.1 `renderer.latestRetargetResult` has two legitimate writers, mode-gated

`SpatialFrameRouter.kt:124` (SEND mode: live retarget) and `AppViewModel.kt:1484`
(`oscReceiver.frames.collect` inside `startOscReceiving` — RECEIVE mode: puppeteering from a
remote OSC source). Intentionally mutually exclusive by feature, not concurrent. Documented here
so a future pass mechanically applying §3.1 doesn't collapse this into a bug that isn't one.

## 9. Verified clean — checked against §3.1 and found fine

Recorded so this document reflects the whole structure that was actually examined, not only the
parts that turned out to be wrong:

- **`ARRenderer.loadedAsset` / `SpatialFrameRouter.loadedAsset`** — two fields, but one writer
  (`AppViewModel.kt:345-346`) sets both together from the same source value; they intentionally
  hold different representations (renderer's is `null` for the default puppet, router's is
  always the concrete asset) rather than racing.
- **`ARRenderer.liveMeshPositions`, `torchOn`, `depthMeshPositions`** — each has two write sites,
  but each pair is sequential/mutually exclusive by user workflow (e.g. posed-scan completion
  vs. freeform-scan completion for `depthMeshPositions`), not concurrent writers racing.
- **`AppViewModel.uiState`, `scanState`** — ~20 and ~21 write sites respectively, but all via
  `.copy()` on disjoint sub-fields from the standard multi-owner UI-state pattern; no field
  collision found.
- **`AppViewModel.incrementalCarver`/`incrementalTrainJob`** — reassigned across scan
  start/cancel/complete functions, but each reassignment cancels the previous job first —
  standard lifecycle management, not a race.
- **`motionRecorder.pushFrame`, `oscStreamer.sendFrame`, `renderer.updateBodyPose`,
  `renderer.applyMorphWeights`** — each confirmed exactly one production call site (all via
  `SpatialFrameRouter`), matching this session's earlier hand-retarget-duplicate fix; no
  leftover second call site was found for any of them.
- **`CLAHEAnalyzer`** — confirmed exactly the two known consumers (`Scanner`/`FreeformScanner`
  quality gating via `AppViewModel`), no missed third consumer anywhere in the repo.

## 10. Architecture contracts

### 10.1 Concurrency

| Work category | Dispatcher | Rationale |
|---|---|---|
| Camera acquisition, YUV/RGBA conversion | CameraX's own executor | Owned by CameraX |
| MediaPipe hand/body/face inference | GPU delegate → CPU fallback (verified in all three of `HandTracker`/`BodyPipeline`/`FacePipeline`) | Correct, unmodified |
| Core depth-fusion channels (DA2, SLAM, stereo, RS-stereo, PSP) | `Dispatchers.Default` | Appropriate for CPU-bound batch work, but see §5.2-5.3 for cross-consumer correlation gaps this sharing introduces |
| Frame routing to renderer/OSC/motion-capture (`SpatialFrameRouter`) | Dedicated single thread (fixed this session) | Latency-sensitive; must not share a pool with unbounded CPU-bound work |

### 10.2 Degradation

Every depth/tracking source follows the same `isAvailable`-checked-by-every-consumer shape:
`StereoDepthSource`, `DepthAnythingSource`, ARCore (`FusedDepthSource.kt:376`, falls back to
"SfM+Photo only"), and MediaPipe's GPU→CPU delegate fallback. Consistent, load-bearing — follow
it for any new source.

## 11. Process: verification, enforcement, and the case studies behind it

### 11.1 Checklist, re-runnable any time

- `grep -rn "BoneRetargeter(\|BodyRetargeter("` — expect 2 legitimate `BoneRetargeter` owners +
  1 test; exactly 1 `BodyRetargeter` construction, shared by `SpatialFrameProducer`'s live
  collector and `ensureFullBodyCollector`'s currently-unstarted one (§7.1 — a disconnected
  feature, not a live double-call).
- `grep -rn "freeformScanner.update\|scanner.update"` — currently 2 and 1 call sites
  respectively; the freeform count should become 1 once §4.1 is fixed.
- `grep -rn "ensureFullBodyCollector"` — currently definition-only; per §7.1/§3.2, resolve by
  either wiring it up (if the feature is wanted) or retiring it deliberately — not by deleting
  it as unused without that decision being made first.
- CI (`Build APK` workflow) is compile-only. Every claim in this document was verified by
  reading method bodies and tracing call sites directly, not by assumption or by trusting
  existing comments — §1's opening correction exists precisely because a comment/claim without
  a traced call site was wrong once already. Runtime behavior (does §4.1 actually degrade scan
  quality, does §5.3 actually cause a visible frame mismatch) can only be confirmed by testing
  on a real device — none is available in this environment.

### 11.2 Case study: `ControlPanel.kt` — the regression §3.2 is meant to prevent

Earlier this session, `ControlPanel.kt` was deleted as dead code because its composable
functions (`ControlPanel`, `OscPanel`, `CtrlButton`) had no callers. The same file also defined
shared color constants (`UIBg`, `Plasma`, `Warn`) imported by roughly 10 other files. Deleting
the file broke the build; CI caught it, and the fix was creating a new `Colors.kt` to hold the
constants. Exactly §3.2 rule 1, after the fact — the composables were genuinely unused, but the
file wasn't only the composables. Confirmed historical event, cited as the standing case study
for why that rule exists.

### 11.3 Case study: the ~21k-line "duplicate app-module code" deletion — re-audited, confirmed safe

Commit `52b9f54` removed 87 files under `app/src/main/java/com/arhand/{camera,depth,export,
mocap,render,scanner,tracking,util}/` — full pre-modularization copies of code that also existed
in the corresponding library modules `app` already depended on via `project(':x')`. Re-audited
independently, not just re-read: every one of the 87 deleted files was diffed programmatically
against its module counterpart *as both existed at the moment of deletion* (`52b9f54^`, not
today's versions, to avoid comparing against unrelated later work).

Result: **69 files were byte-for-byte identical** to their module counterpart. The remaining
**18 differed**, and every difference was the module version being ahead of the app-local copy
— never the reverse:
- Package-path corrections for functions already relocated to their real home (`com.arhand.util.
  landmarkToWorld`/`lmDist` → `com.arhand.tracking.landmarkToWorld`/`lmDist`, appearing in
  `DepthCarver.kt`, `HandSegmentationMask.kt`, `BoneRetargeter.kt`, `HandMeshBuilder.kt`,
  `HandBiometrics.kt`, `Scanner.kt`, `QualityEngine.kt`, `util/MathUtils.kt`) — this is the exact
  mechanism behind the `ScanPipeline` build failure fixed immediately after (`6304cd3`): the
  stale app-local copy of `landmarkToWorld` was masking an already-broken reference, not holding
  something the module version lacked.
- Features present in the module version and absent from the stale app-local copy: GAP-2 morph
  target delta parsing (`AssetLoader.kt`, `SkinnedMeshRenderer.kt`), v27 OSC addresses and send
  methods (`OscSchemaAddresses.kt`, `OscStreamer.kt` — `sendCameraPose`/`sendRppg`/
  `sendDepthMetric`/`sendHandOcclusion`/`sendPlanes` all present and independently confirmed
  live in `SpatialFrameRouter.kt` today), IMU world-frame pre-rotation (`BodyPipeline.kt`),
  dual-threshold occlusion hysteresis (`OcclusionEngine.kt`).

No file showed the reverse — nothing found only in an app-local copy and missing from its module
counterpart. This deletion removed stale, superseded shadow copies in favor of the
actively-maintained module versions, with no functionality lost. Confirmed safe, not re-asserted.

## 12. Explicitly out of scope

- Device-specific tuning (NNAPI/GPU-delegate speculation, resolution/quality downgrades) — the
  NNAPI attempt this session regressed both performance and accuracy and was reverted.
- Any code changes. This document is research and assessment only — §4–§9 are a prioritized
  findings list for a future implementation pass, not a changelog of what was done.
