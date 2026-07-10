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

## 3. Incomplete/disconnected features — corrected from an earlier "dead code" misclassification

**These are not dead code and must not be deleted.** An earlier version of this document (and
my own first read of them) called §3.1/§3.2 "dead/orphaned code" — wrong. Re-reading each one's
own doc comments and the surrounding design (guard-flag patterns, a UI toggle already wired to
a working render path, a classifier overload built specifically to consume one of these) showed
they are fully- or mostly-built features missing one connection, not leftover cruft. See §5 for
the rule this changes going forward.

### 3.1 `AppViewModel.ensureFullBodyCollector()` is built but never started — one call site short of working

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
still wanted? If yes, the fix is adding the missing call (and resolving the shared-`BodyRetargeter`-instance
question from the prior draft — `SpatialFrameProducer` already retargets body independently, so
starting this collector too would reintroduce a live version of that double-invocation, meaning
the actual fix is likely "point this collector at the producer's own `_latestBodyResult` output
instead of calling `.retarget()` again," not just adding the missing call verbatim). If no, that's
a deliberate call to make and document, not something to silently delete as unused.

### 3.2 `renderer.scanCloudPoints` — a working, wired UI toggle with no data feed

`ARRenderer.showCloud` is toggled live from `AppViewModel.kt:763` and gates a fully functional
render path (`ARRenderer.kt:259-263`: `depthCloudRenderer.updateAndDraw(...)`) — the toggle and
the renderer both work. Nothing anywhere populates `scanCloudPoints` itself, so the toggle
currently does nothing visible. This reads as a live point-cloud preview feature (likely meant
to sample `spatialLayer.fusedDepth.store.snapshot()`, the same source `AppViewModel`'s scan-capture
code already reads) that had its render half built and its data-feed half never connected —
same shape as §3.1, same rule: finish it or make a deliberate call to retire the toggle, don't
delete the renderer path as "unused."

### 3.3 `SpatialFrameRouter`'s posed-scan capture branch — an asymmetric migration, not dead weight

Different shape again. `route()`'s `capturedFrames`/`biometricFrames` append
(`SpatialFrameRouter.kt:213-223`) is gated on `isScanActive && !isFreeformActive`, but
`startScan()` (posed scans, `AppViewModel.kt:820-887`) never touches `router.isScanActive` at
all — it manages capture entirely inline, the same way the codebase apparently worked before
`SpatialFrameRouter` existed. `startFreeformScan()` sets `router.isScanActive`/`isFreeformActive`
*and* still runs its own inline capture — meaning freeform scanning was migrated to the router
but its old inline path was never removed (this is §2.1's live bug), while posed scanning was
simply never migrated at all. Read together, the consistent fix for both is the same move: make
`SpatialFrameRouter` the single owner of capture for *both* scan types — wire `isScanActive` in
`startScan()` too, then delete `AppViewModel`'s inline capture for both posed and freeform. Not
"delete the router's dead branch" — the router's branch is the correct future state; the inline
paths are what should go, once actually verified equivalent.

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
drifting (that drift is what produced §3's disconnected features and the stale doc comments
found earlier this session).

## 5. Standing rule: nothing gets deleted without a second, independent review pass

Added after nearly misclassifying §3.1/§3.2 as safe-to-delete dead code on first read. Applies
to every future change in this codebase, no exceptions:

Before deleting anything because "nothing calls this" or "this looks unused":
1. **Check what else the file/symbol carries.** A thing can look dead by one measure (an
   unused function) while the same file holds something load-bearing (a shared constant, a
   type used elsewhere) — see §5.1 for the confirmed case this already happened.
2. **Check whether "nothing calls this" means "abandoned" or "not wired up yet."** Read the
   candidate's own doc comments and naming (guard flags, "ensure"/"lazy" naming, TODO markers)
   for evidence it was designed to be called from somewhere that never got written — see §3.1,
   §3.2 for two cases just caught this way.
3. Only after both checks pass does "delete" become the answer instead of "finish" or
   "deliberately retire, on purpose, out loud."

### 5.1 Historical case where this rule would have caught a real regression: `ControlPanel.kt`

Earlier this session, `ControlPanel.kt` was deleted as dead code because its composable
functions (`ControlPanel`, `OscPanel`, `CtrlButton`) had no callers. The same file also defined
shared color constants (`UIBg`, `Plasma`, `Warn`) imported by roughly 10 other files. Deleting
the file broke the build; CI caught it, and the fix was creating a new `Colors.kt` to hold the
constants. This is exactly rule 1 above, after the fact — the composables were genuinely
unused, but the file wasn't only the composables. No new evidence needed for this one; it's a
confirmed historical event on record. Cited here as the standing case study for why rule 1
exists, and as an admission that this document's own methodology wasn't always this careful
before this pass.

### 5.2 The ~21k-line "duplicate app-module code" deletion — independently re-audited, confirmed safe

Earlier in this session, commit `52b9f54` removed 87 files under `app/src/main/java/com/arhand/
{camera,depth,export,mocap,render,scanner,tracking,util}/` — full pre-modularization copies of
code that also existed in the corresponding library modules `app` already depended on via
`project(':x')`. This was re-audited independently this pass, not just re-read: every one of the
87 deleted files was diffed programmatically against its module counterpart *as both existed at
the moment of deletion* (`52b9f54^`, not today's versions, to avoid comparing against unrelated
later work).

Result: **69 files were byte-for-byte identical** to their module counterpart. The remaining
**18 differed**, and every single one of those differences was the module version being ahead
of the app-local copy — never the reverse:
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

No file showed the reverse — nothing found only in an app-local copy and missing from its
module counterpart. Conclusion: this deletion removed stale, superseded shadow copies in favor
of the actively-maintained module versions, with no functionality lost. Confirmed safe, not
just re-asserted.

## 6. Concurrency contract (kept, still accurate)

| Work category | Dispatcher | Rationale |
|---|---|---|
| Camera acquisition, YUV/RGBA conversion | CameraX's own executor | Owned by CameraX |
| MediaPipe hand/body/face inference | GPU delegate → CPU fallback (verified in all three of `HandTracker`/`BodyPipeline`/`FacePipeline`) | Correct, unmodified |
| Core depth-fusion channels (DA2, SLAM, stereo, RS-stereo, PSP) | `Dispatchers.Default` | Appropriate for CPU-bound batch work, but see §2.6-2.7 for cross-consumer correlation gaps this sharing introduces |
| Frame routing to renderer/OSC/motion-capture (`SpatialFrameRouter`) | Dedicated single thread (fixed this session) | Latency-sensitive; must not share a pool with unbounded CPU-bound work |

## 7. Degradation contract (kept, still verified)

Every depth/tracking source follows the same `isAvailable`-checked-by-every-consumer shape:
`StereoDepthSource`, `DepthAnythingSource`, ARCore (`FusedDepthSource.kt:376`, falls back to
"SfM+Photo only"), and MediaPipe's GPU→CPU delegate fallback. Consistent, load-bearing —
follow it for any new source.

## 8. Verification & enforcement

1. **Instance/call-site counts**, re-runnable any time:
   - `grep -rn "BoneRetargeter(\|BodyRetargeter("` — expect the counts in §3.1's research (2
     legitimate `BoneRetargeter` owners + 1 test; exactly 1 `BodyRetargeter` construction,
     shared by `SpatialFrameProducer`'s live collector and `ensureFullBodyCollector`'s
     currently-unstarted one — see §3.1, this is a disconnected feature, not a live double-call).
   - `grep -rn "freeformScanner.update\|scanner.update"` — currently 2 and 1 call sites
     respectively; the freeform count should become 1 once §2.1 is fixed.
   - `grep -rn "ensureFullBodyCollector"` — currently definition-only; per §3.1/§5, resolve by
     either wiring it up (if the feature is wanted) or retiring it deliberately — not by deleting
     it as unused without that decision being made first.
2. **CI is compile-only.** Every claim above was verified by reading method bodies and tracing
   call sites directly (three parallel research passes, cross-checked against each other and
   against this document's own prior, partially-incorrect claims) — not by assumption, and not
   by trusting existing comments (§0's correction exists precisely because a comment/claim
   without a traced call site was wrong once already).
3. Runtime behavior (does §2.1 actually degrade scan quality, does §2.7 actually cause a visible
   frame mismatch) can only be confirmed by testing on a real device — no physical device is
   available in this environment.

## 9. Explicitly out of scope

- Device-specific tuning (NNAPI/GPU-delegate speculation, resolution/quality downgrades) — the
  NNAPI attempt this session regressed both performance and accuracy and was reverted.
- Any code changes. This document is research and assessment only, per explicit instruction
  this pass — §2/§3 are a prioritized findings list for a future implementation pass, not a
  changelog of what was done.
