# Engine Architecture & Reliability

## 1. Purpose

A cross-layer map of what depends on what in this codebase, an assessment of where it currently
violates its own implied design, and the rules that assessment was checked against. Not a
changelog — findings here are current state, verified by reading full method bodies and tracing
real call sites, not by trusting names, doc comments, or how something "looks wired up." That
distinction matters concretely: an earlier pass through this same material called two genuinely
unfinished features "dead code" before a second look (§10) showed they were one connection short
of working, not abandoned — see §3.2 for the rule that mistake produced.

Scope: Core (`camera`/`depth`), Translation (`tracking`/`mocap`/`scanner`), Application
(`render`/`export`/`app`), the Compose UI layer, and test coverage. Every finding below includes
concrete fix options with tradeoffs, not just diagnosis — a prior version of this document
stopped at description for most items; that gap is closed here.

Any future mismatch between this document and the code is a bug in whichever one is behind —
not something to leave silently drifting.

## 2. Layer Model

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
| **Application** | `render`, `export`, `app` (incl. Compose UI) | Structured motion/geometry → draw calls, OSC, BVH/GLB files, UI |

`depth` importing `tracking.HandLandmarks` (2 files, coordinate-math helpers) is a data-type
dependency, not a behavioral one. Not worth restructuring.

## 3. Principles

Everything below was checked against these two rules.

### 3.1 Single ownership

> Every piece of state read from more than one place has exactly one place that writes it.
> Every stateful per-frame update function is called from exactly one call site.

### 3.2 Nothing gets deleted without a second, independent review pass

1. **Check what else the file/symbol carries** — see §14.2 for the confirmed case this already
   went wrong.
2. **Check whether "nothing calls this" means "abandoned" or "not wired up yet"** — read the
   candidate's own doc comments and naming for evidence it was designed to be called from
   somewhere that never got written — see §10 for cases caught this way.
3. Only after both checks pass does "delete" become the answer instead of "finish" or
   "deliberately retire, on purpose, out loud."

## 4. Live bugs (currently executing, ranked by impact)

### 4.1 Exported hand model's baked texture uses a UV projection that doesn't match the mesh — DONE (REDESIGN_PLAN.md Phase 1.1)

`TextureBaker.bakeFromTriangles` (`export/.../TextureBaker.kt:67-77`) computes
`u = atan2(x, z)` / `v = 1f - (y-minY)/height` directly from world coordinates. Its own doc
comment claims this "matches GLBExporter's C2 projection" — it doesn't:
`GLBExporter.kt:130-142` computes `u = atan2(vx-cx, vz-cz)` (centered on the mesh's own AABB
midpoint) and `v = (vy-minY)/yRange` (not inverted). `ScanPipeline.kt:185-213` feeds the same
mesh into both. Since scanned meshes are almost never centered at world origin, `cx`/`cz` are
typically non-zero, so the baked atlas is shifted/rotated around the vertical axis and flipped
top-to-bottom relative to the UVs the exported GLB actually samples it with. **Concrete
outcome**: exported models show visibly wrong texture placement (wrong side textured, or
upside-down) for any scan not centered at the origin — essentially always.

**Fix options**: (a) Make `TextureBaker` compute its projection from the *same* AABB-centered
`cx`/`cz` and non-inverted `v` that `GLBExporter` uses — smallest change, just align the two
formulas (the doc comment already claims this is the intent). (b) Have `GLBExporter` call a
shared projection function that `TextureBaker` also calls, eliminating the possibility of the
two drifting apart again. Recommended: (b) — this is the second UV-mismatch-shaped bug in this
codebase (see §11 for the same root cause pattern); a shared function prevents a third.

### 4.2 `hasStoredModel` is never reset on scan cancel/failure — stale "scan complete" modal can reappear — DONE (Phase 1.2)

`MainActivity.kt:159-163`: `LaunchedEffect(freeformActive, hasStoredModel) { if (!freeformActive
&& hasStoredModel) showResult = true }`. `AppViewModel.scanState.hasStoredModel` is set `true`
on a scan's successful completion (`AppViewModel.kt:1024,1114`) and otherwise only ever read —
`cancelFreeformScan()` (`:953-972`), `processFreeformScan()`'s exception branch (`:1059-1071`),
and the `FreeformScanner.State.FAILED` handler (`:589-592`) all flip `freeformActive` back to
`false` but never touch `hasStoredModel`. **Concrete scenario**: complete one scan successfully,
then start and cancel (or fail) a second one — `freeformActive` flips `true→false`, the
`LaunchedEffect` re-evaluates, and the "SCAN COMPLETE" modal pops showing the *first* scan's
stale mesh/biometrics, misleadingly presented as if it were the just-cancelled attempt's result.

**Fix options**: (a) Reset `hasStoredModel = false` is wrong (it would hide a real prior
success) — instead, gate the `LaunchedEffect` on an explicit "this specific scan just
completed" signal (e.g. a monotonically incrementing `completedScanId`) rather than the
ambient `hasStoredModel` boolean. (b) Have `cancelFreeformScan()`/the FAILED handler explicitly
set a `justCancelled`/`justFailed` flag that the `LaunchedEffect` checks first and short-circuits
on. Recommended: (a) — it fixes the actual conflation (any-prior-success vs. this-attempt-succeeded)
rather than adding another flag to keep in sync.

### 4.3 `FreeformScanner.update()` called twice per frame during every active freeform scan — DONE (Phase 2)

- `AppViewModel.kt:525-533` calls it directly, gated on `scanState.value.freeformActive`.
- `SpatialFrameRouter.kt:226-236` calls it again, gated on `router.isFreeformActive`.
- `startFreeformScan()` (`AppViewModel.kt:941-943`) sets **both** gates `true` in the same call.

`FreeformScanner.update()` is heavily stateful (`prevLms` motion gating, `acceptedOrientations`/
`coverageBuckets`/`coveredBuckets` novelty/coverage tracking, its own capture lists). Calling it
twice per real frame double-steps all of this — can make scans complete early, under-cover the
intended viewpoint spread, or reject frames a single-call version would accept. Same shape as
the hand-retarget duplicate already fixed this session, found here in scan-capture instead of
render/retarget.

**Fix options**: (a) Make `SpatialFrameRouter` the sole owner — delete `AppViewModel`'s direct
call — consistent with §10.3's posed-scan asymmetric-migration fix (same move, same direction).
(b) Make `AppViewModel` the sole owner instead, if scan capture is considered out of router's
routing responsibility. Recommended: (a) — matches the direction every other duplicate-ownership
fix this session went (consolidate onto the producer/router pipeline, not the legacy inline path).

### 4.4 `renderer.handsData` / `renderer.mirrorX` written from two independent collectors every frame — DONE (Phase 3.1)

`SpatialFrameRouter.kt:117-121` (from `frame.primaryHand/secondaryHand`, post-`assembleFrame`)
and `AppViewModel.kt:402-403` (from the raw `hands` value in the same collector that calls
`producer.assembleFrame(...)`) both write these every hand-pipeline frame. Values aren't
guaranteed identical (router's is post-assembly/filtered; AppViewModel's is raw). Redundant work
at minimum, a source of subtle one-frame flicker at worst if the two ever diverge.

**Fix options**: (a) Delete `AppViewModel`'s direct writes, rely solely on router's post-assembly
values — simplest, consistent with treating the router as canonical. (b) Keep AppViewModel's
writes instead if raw-landmark latency matters more than post-assembly consistency (unverified
whether assembly adds meaningful delay). Recommended: (a) unless a measured latency problem
specifically justifies (b).

### 4.5 `depthConfidence` written from three independent, unsynchronized coroutines — but never actually rendered — DONE (Phase 3.2)

`AppViewModel.kt:373,508,562,777` all write `depthConfidence` with no ordering guarantee.
**Update from the UI-layer research pass**: `MainActivity.kt:139` collects this value into a
local but never reads it again — the HUD's "DEPTH"/"MTR %" display actually reads
`spatialState.depthConfidence` (`HudOverlay.kt:76`, sourced from `SpatialLayer.state`, written
in exactly one place, `SpatialLayer.kt:136`). So this race currently has **no visible symptom** —
worth fixing for correctness hygiene, not because anything looks wrong today.

**Fix options**: (a) Since nothing renders it, the simplest correct fix is retiring the field
per §3.2 (confirm deliberately that it's unneeded, then remove the dead collection and the three
writers) rather than "fixing" a race nobody observes. (b) If it's wanted for a future HUD
element, consolidate to the one write site that's already safe (`producer.spatialLayer.state.collect`)
and delete the other three. Recommended: (a) — check first whether this was meant to replace
`SpatialLayer.state.depthConfidence` and the migration was simply never finished (same shape as
§10's findings) before assuming it's just dead.

### 4.6 `restJointPositions` written from two independent coroutines, not even `@Volatile` — DONE (Phase 4.1)

`AppViewModel.kt:1019` (`processFreeformScan`) and `:1109` (`processScan`), each its own
`Dispatchers.Default` coroutine, both write this plain `var FloatArray?` with no synchronization.
Mutually exclusive by user workflow today, but nothing enforces that — a future change making
them concurrent would hit a real JMM visibility bug, not just a logical race.

**Fix options**: (a) Mark `@Volatile` — minimal fix; the two call sites are legitimately
mutually exclusive by design, so visibility (not ordering) is the actual gap. (b) Unify both
scan-completion paths into one shared handler if their post-processing logic is similar enough.
Recommended: (a) as the minimal safe fix; consider (b) only if a broader posed/freeform
consolidation (§10.3) makes it natural.

### 4.7 Core-layer depth channels (SLAM, DA2) run unthrottled at full camera rate — DONE

`SpatialFrameProducer.processBitmap` calls `spatialLayer.processBitmap(bitmap)` (SlamLite
Harris-corner detection + pyramidal optical flow, plus rPPG) and
`spatialLayer.fusedDepth.processAuxSources(...)` (DA2 CNN dispatch, plus DRASL/JBU, plus
RS-stereo/PSP/stereo/FLARE/MOIRE when `reconstructionActive`) unconditionally, several lines
*before* `frameThrottler.shouldInfer()`'s gate. MediaPipe tracking (hand/body/face, a few lines
below that gate) already has two layers of adaptive rate control (`FrameThrottler`,
`ModelBudgetManager`) — Core had none. Since `frameProvider.frames.collect { processBitmap(...) }`
runs on one sequential per-frame coroutine, an over-budget Core pass on one frame delays every
later stage of that same frame, including hand-tracking submission — a plausible direct cause of
lag/delay between real movement and rendered result reported on-device.

**Fix**: added `util/DepthChannelBudget.kt` — same shed/recover EMA mechanism as
`ModelBudgetManager`, generalised to a named-channel list (`"da2"`, `"slam"`, priority-ordered;
`da2` first since its output also feeds hand-landmark Z correction, not just reconstruction).
Wired into `SpatialFrameProducer.processBitmap`: both calls are now gated on
`depthBudget.tick(...)`'s per-frame decision and their wall-clock time reported back via
`depthBudget.report(...)`. No channel is ever fully disabled (`everyN` capped at `maxEvery = 4`
by default); each channel already exposes its last output via its own `@Volatile`/cached field
(SlamLite's internal state, DA2's `depthBlocks`, and a new `lastFlowSnapshot` field on
`SpatialFrameProducer` so a "da2 ran, slam didn't" frame still has a flow reading to pass
through), so skipped frames read as stale-but-recent rather than absent. Unit-tested in
isolation (`util/src/test/.../DepthChannelBudgetTest.kt`) — no device available in this
environment to confirm the on-device lag actually improves; CI verifies compilation only.

### 4.8 Camera stream itself capped at 30fps regardless of Core/Translation throttling — DONE

Separately from §4.7's per-frame processing cost, `CameraController.bindCamera()`
(`camera/.../CameraController.kt`) built its `ImageAnalysis` with no capture-request tuning at
all, so the actual camera hardware stream rate was whatever CameraX/the camera HAL's AE routine
defaults to for the chosen resolution — 30fps on most devices/cameras, even when the camera
supports a wider AE target FPS range. §4.7 fixes how much work runs per delivered frame; this is
the separate question of how many frames the hardware delivers per second in the first place —
both needed for "not a perfect 1:1 60fps" to actually mean 60fps end-to-end.

**Fix**: added `CameraController.applyHighestFpsRange()`, called from `bindCamera()` for both
the initial `start()` and every `switchCamera()` rebind. Queries the currently-selected
camera's own `CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` via
`Camera2Interop.Extender` and requests the widest range the camera itself advertises — never a
hardcoded guess like `(60, 60)`, which would be silently ignored or rejected outright on a
camera that doesn't support it. Wrapped in try/catch with a silent fallback to CameraX's default
behaviour on any failure, matching the existing device-variance-guard convention already used
by `ArCoreDepthSource`/`GrayscaleCamera`'s own Camera2 calls. No device available in this
environment to confirm actual achieved frame rate or rule out device-specific AE-range quirks;
CI verifies compilation only.

### 4.9 Switching to the rear camera froze the screen — ARCore and CameraX both held the same physical camera — DONE

`ArCoreDepthSource` creates its own ARCore `Session` (`ArCoreDepthSource.kt:181`) with no
camera-facing configuration and no ARCore Shared-Camera integration, so it opens its own
independent Camera2 handle to the device's rear-facing camera as soon as `SpatialLayer.start()`
resumes it — always-on, per §2's "metric grounding is always-on from camera start" design.
Separately, `CameraController` (CameraX/`ProcessCameraProvider`) defaults to the *front* camera
at startup (`CameraController.kt:40`), so the two never contended for hardware — until
`AppViewModel.switchCamera()` called `cameraController.switchCamera()` to bind CameraX onto the
*rear* camera too. At that point both clients wanted exclusive access to the same physical
camera device; CameraX's `bindToLifecycle()` had to wait for a device ARCore's `Session` was
still holding open, which is what surfaced as the screen freezing and never completing the
switch. This had no prior symptom simply because nothing had exercised switching to the rear
camera before.

**Fix**: added `ArCoreDepthSource.pauseCameraHold()`/`resumeCameraHold()` (a lighter-weight
pair than the existing `start()`/`stop()`, which also touch `started`/`callback` bookkeeping),
threaded through `FusedDepthSource`/`SpatialLayer` as `pauseArcoreCameraHold()`/
`resumeArcoreCameraHold()`. `AppViewModel.switchCamera()` now releases ARCore's camera hold
*before* calling `cameraController.switchCamera()`, and reacquires it only once CameraX has
switched back to front (freeing the rear camera again) — while CameraX itself is on rear,
ARCore genuinely cannot also hold it, so depth/world-tracking degrades to unavailable during
that window, same as the already-documented front-camera degradation path (§4's comment this
replaces). A full fix — ARCore's Shared-Camera API, letting both clients use the same physical
camera concurrently — is a substantially larger, device-camera-HAL-sensitive integration not
attempted here; this is the minimal change that resolves the freeze. No device available in
this environment to confirm on-device; CI verifies compilation only.

### 4.10 `landmarkToWorld`'s `camAspect` parameter silently defaulted to the wrong value at 8 of 9 call sites — DONE

`landmarkToWorld(lm, aspect, mirrorX, camAspect: Float = aspect)` — `LandmarkUtils.kt:40` — only
uses `camAspect` in its screen-space fallback branch (taken when a `Landmark`'s `worldX`/`worldY`
are both exactly `0f`, i.e. no MediaPipe world coordinates available). `camAspect` is meant to be
the *camera's actual capture aspect ratio* (`bitmap.width/height`, ≈4:3 for this app's 640×480
capture) — distinct from `aspect`, the on-screen/UI aspect (a modern phone screen, ~9:19.5) —
so the fallback's crop-compensation math can correct for the camera preview being cropped to fit
a differently-shaped screen. Only `BoneRetargeter.kt:166` (live hand-puppet retargeting) passed
it explicitly. Every other call site omitted it, silently taking the wrong default (`camAspect =
aspect`, i.e. "assume the camera and screen have the same aspect ratio," which they never do on
a real phone):

- `Scanner.kt`'s `capturePosePoints()` — **always triggered, not an edge case**: it builds
  denser interpolated points along MediaPipe's 21-landmark skeleton by constructing fresh
  `Landmark(x, y, z)` values with no `worldX`/`worldY` set at all (they default to `0f`), so this
  call *always* took the buggy fallback branch, on every posed scan, unconditionally. These
  points flow directly into `Scanner.cloudPoints` → `ScanCoordinator`/`ScanInput.cloudPoints` →
  the actual mesh-reconstruction pipeline (`ScanPipeline.kt`) — i.e. this distorted the posed
  scan's captured geometry on every single scan, not a rare corner case.
- `HandSegmentationMask.buildHull()`, `DepthCarver.landmarksToWorld()`, `HandBiometrics.compute()`,
  `ScanPipeline.kt`'s rest-joint computation — all process real MediaPipe `HandLandmarks`, which
  populate `worldX`/`worldY` in essentially all live-tracking conditions with the Tasks SDK (per
  `HandBiometrics.kt`'s own doc comment, confirmed by re-reading it), so these took the *correct*
  (non-`camAspect`-dependent) branch in practice — latent/defensive bugs, not active ones, but the
  same shape and worth closing given how easily this drifts (proven by the fact it *did* drift).

**Fix**: threaded a real `camAspect` (computed as `latestBitmap?.let { it.width.toFloat() /
it.height.toFloat() } ?: aspect`, the same expression `BoneRetargeter`'s call site already used)
through every one of these call chains: `Scanner.update()` → `updateCapture()` →
`capturePosePoints()`; `HandSegmentationMask.buildHull()`; `DepthCarver.landmarksToWorld()` (and
its two callers in `SpatialFrameRouter.route()`); `HandBiometrics.compute()`; and the rest-joint
computation in `ScanPipeline.kt` (computed from `input.capturedBitmaps.firstOrNull()` there, since
that function doesn't have a live bitmap reference). No device available to confirm the
resulting improvement in exported scan geometry; CI verifies compilation only.

### 4.11 Hand-landmark "world" coordinates and ARCore/SfM depth-cloud "world" coordinates are not the same coordinate frame — DONE

While fixing §4.10, `HandSegmentationMask.buildHull()`/`filterPointCloud()` raised a bigger
question, investigated further per direct request. Root cause is now confirmed, not just
hypothesized — `HandTracker.kt:120`'s own comment states it plainly: *"World landmarks: metric
3D coordinates (meters), origin at hand geometric center."* This means MediaPipe's world
landmarks encode **only the hand's shape/pose** — they are deliberately re-centered to the
hand's own centroid every frame, which discards all information about *where the hand actually
is* relative to the camera or the room. `filterPointCloud()` then tests hull coordinates derived
from these hand-centred points directly against `store.snapshot()`'s points — the fused
ARCore/SfM depth cloud, anchored to ARCore's persistent room-scale tracking origin
(`ArCoreDepthSource.onDrawFrame()` transforms every point through `cam.pose`). These are
confirmed-different coordinate systems.

**Why a simple pose-composition fix cannot rescue this** (the fix this document originally
proposed investigating): composing the camera's ARCore pose onto the hand-centred landmarks
would correctly re-orient them, but has no way to recover the *translation* — how far the hand
actually is from the camera, and in what direction — because MediaPipe discarded that
information when it re-centred the landmarks to the hand's own centroid. There is nothing left
in the world-landmark data to compose the missing translation from. This is confirmed by
contrast with the codebase's own correct usage of these same landmarks: `BoneRetargeter.kt:164-177`
converts hand landmarks via the identical `landmarkToWorld()` call, but only ever computes
*relative* direction vectors between joints (`world[tipIdx] - world[baseIdx]`) for
`shortestArcQuaternion` — a calculation that's invariant to the landmarks' arbitrary origin,
because only the angle between two vectors expressed in the same frame matters, not their
absolute position. `HandSegmentationMask` is the only place in the codebase that (incorrectly)
treats these landmarks' *absolute* position as meaningful for cross-referencing against a
different frame.

**Severity, best-effort estimate**: potentially worse than "imprecise" — if the hand hull sits
near coordinate (0,0,0)-ish (wherever MediaPipe re-centres it) while `cloud`'s real points sit
wherever ARCore's session happened to anchor its origin (arbitrary — wherever tracking first
stabilized, unrelated to where the user later holds their hand), the hull could fail to overlap
`cloud` at all for most of a scan, meaning `filterPointCloud` silently rejects nearly everything
passed to it. Whether this is "somewhat imprecise" or "near-total masking failure in practice"
cannot be determined without a device — this depends on distances involved that aren't known
from static analysis.

**Fix applied**: implemented the correct approach identified above — going back to *before*
MediaPipe's world-landmark re-centring, using real per-landmark camera-space depth unprojected
via camera intrinsics, then transformed by the camera's live ARCore pose.

- `ArCoreDepthSource` now exposes the full `Pose` object plus raw (native-resolution) intrinsics
  (`lastPose`, `lastFx/Fy/Cx/Cy`, `lastImgW/ImgH`) captured every `onDrawFrame()` call, alongside
  the existing `lastCamX/Y/Z`.
- `DepthAnythingSource.isMetricCalibrated` (new) reports whether the DA2 dense depth map has
  actually completed XR (or dual-anchor log-linear) calibration — before that, `denseDepth`'s
  values are an arbitrary uncalibrated scale, not metres, and must not be trusted as such.
- `SpatialLayer.unprojectLandmarkToWorld(normX, normY): Vec3?` (new) — samples DA2's calibrated
  dense depth at the landmark's own pixel, unprojects through the camera's real intrinsics into
  camera space, then calls `pose.transformPoint(...)` — the exact same unprojection
  `ArCoreDepthSource.onDrawFrame()` already performs per-pixel for its own depth cloud, evaluated
  at one landmark instead of a dense grid. Returns null (not a wrong-frame guess) when metric
  depth isn't currently available — not tracking, or DA2 hasn't calibrated yet.
- `HandSegmentationMask.buildHullMetric(lms, unproject)` (replaces `buildHull`) builds the hull
  from this real per-landmark world position instead of `landmarkToWorld`'s hand-centred output,
  so it's finally in the *same* coordinate frame as `PointCloudStore`'s ARCore/SfM cloud it's
  compared against. Both `AppViewModel` call sites now fall back to **unfiltered** (pass the
  cloud through) when metric depth isn't available yet, rather than filtering against a hull in
  the wrong frame — strictly better than the previous always-wrong behavior.

**Also removed**: `SpatialLayer.toWorldSpace()` (the "GAP-1" function) and its supporting
`ArCoreDepthSource.lastCamQX/Y/Z/W` fields. `toWorldSpace()` had zero callers anywhere and was
itself an instance of the exact broken pose-composition approach this finding's analysis ruled
out (it rotated MediaPipe's hand-*centred* world landmark by the camera's rotation and added the
camera's world position, treating a hand-relative offset as a camera-relative one) — confirmed
dead and confirmed wrong before deletion, not assumed.

**Scope note**: this fix only applies where real metric depth is actually available (rear camera,
ARCore tracking, DA2 XR-calibrated) — the same conditions `depthMode`/TSDF fusion already require
elsewhere. The default landmark-only `DepthCarver.carveAndExtract` path never touched `store`/
ARCore's cloud and was never affected by this bug in the first place.

### 4.12 `BitmapGrayscaleShim`'s single-listener slot meant SfM went permanently silent after the first scan of every session — DONE

Found while digging deeper into "the architecture doesn't feel strong enough" after §4.7-4.11's
fixes didn't fully resolve reported lag/inaccuracy — this is a structural design flaw, not a
one-line bug. `BitmapGrayscaleShim` (`camera/.../BitmapGrayscaleShim.kt`) exists so `SfMDepthSource`
and `PhotometricDepthSource` can both consume `CameraFrameProvider`'s existing bitmap stream
without each opening a second camera session. Its actual usage has two *concurrent* consumers:
SfM registers once at app start and is meant to stay registered for the whole session (it's the
metric-grounding fallback when ARCore is unavailable — see §2's degradation ladder); Photometric
registers additionally only while a scan's reconstruction sources are active
(`FusedDepthSource.setReconstructionActive(true)`) and unregisters when the scan ends.

The shim held only a single `listener` slot (`setListener`), not a set. Tracing the actual call
sequence: app start → `SfMDepthSource.start()` → `shim.setListener(sfmHandler)` (SfM now
receiving frames). First scan begins → `setReconstructionActive(true)` → `PhotometricDepthSource
.start()` → `shim.setListener(photometricHandler)` — this **silently replaces** SfM's
registration; SfM stops receiving frames for the scan's duration with no error, no log, no
symptom beyond degraded output. Scan ends → `setReconstructionActive(false)` →
`PhotometricDepthSource.stop()` → `shim.setListener(null)` — now *neither* source is registered,
and nothing ever re-registers SfM afterward (`SfMDepthSource.start()` is only ever called once,
at `FusedDepthSource.start()`). **Net effect: SfM-based grounding goes silent after the first
scan of every app session and never recovers**, on any device where it matters (i.e. whenever
ARCore isn't the metric source) — directly explaining sustained accuracy/grounding degradation
that a single throttling or coordinate-math fix wouldn't touch.

**Fix**: converted `BitmapGrayscaleShim` from a single `listener` field to a small listener list
(`addListener`/`removeListener`), so both sources can be registered simultaneously without
clobbering each other. `SfMDepthSource`/`PhotometricDepthSource` each now store the exact
`GrayscaleCamera.FrameListener` instance they registered so `stop()` removes only their own
entry. The per-frame grayscale conversion still runs exactly once regardless of listener count —
this fixes a correctness bug, not a performance one, and adds no per-frame cost.

This is the kind of finding the "is the architecture good enough" question was really asking
about: not a missing throttle or a wrong default parameter, but a shared resource (the shim)
designed for what looked like one consumer, actually serving two with incompatible lifecycles,
with nothing catching the silent handoff failure. No device available to confirm the resulting
grounding-continuity improvement; CI verifies compilation only.

## 5. Timing & correlation gaps (values from different cadences combined as if simultaneous)

### 5.1 Cross-cadence staleness: Core writes some fields at raw-frame rate, Translation reads them at hand-inference rate — DONE (Phase 8 item 1)

`SpatialFrameProducer.assembleFrame()` runs once per throttled `handPipeline.processed`
emission, but reads `getMeanArbiterWeights()`, `metricMode`, `rppg.bpm`/`amplitude` — all written
by Core-layer code at raw camera-frame rate or ARCore's own callback thread. Values embedded in
a given `SpatialFrame` are "whatever Core last computed," not necessarily aligned to the exact
camera frame the bundled hand landmarks came from.

**Fix options**: (a) Timestamp each Core-layer value when written; `assembleFrame()` checks the
timestamp against the current frame's time and flags/discards data older than a threshold —
adds observability without full synchronization, low risk. (b) Have Core publish one immutable
snapshot object atomically per raw frame instead of individually-racing fields — bigger refactor,
stronger guarantee. Recommended: (a) first, as a low-risk diagnostic step; escalate to (b) only
if (a) shows the staleness is large enough to matter in practice.

### 5.2 `SlamLite`'s optical flow can be stale relative to the DA2 frame it calibrates — DONE (Phase 6)

`SpatialLayer.processBitmap()` runs `slam.process(bitmap)` every raw frame unconditionally, then
assigns `fusedDepth.da2.externalFlowMag/NX/NY`. `DepthAnythingSource.processAsync` drops frames
it can't keep up with, so by the time DA2 reads those fields, they may reflect a *later* raw
frame than the bitmap DA2 is actually blending — no frame-id/timestamp correlation between the
two. Internal Core-layer numerical-correctness gap, not just performance: DA2's output (which
also feeds hand-landmark Z-correction) could be calibrated against the wrong motion estimate.

**Fix options**: (a) Pass the flow values as parameters directly into DA2's inference call at
the moment SLAM computes them for that exact bitmap (wrap bitmap+flow in one data class enqueued
together), eliminating the correlation gap entirely. (b) Timestamp-tag the flow fields; DA2
checks tag age before using them, falling back to a neutral value if stale — cheaper, only
mitigates. Recommended: (a) — this was the original fix direction identified before the broader
assessment widened scope, still the right answer.

### 5.3 Camera frame stream has two uncorrelated consumers that can each drop different frames — DONE (Phase 8 item 3)

`CameraFrameProvider.frames` (`tryEmit`, non-suspending, drops on backpressure) has two
collectors: `SpatialFrameProducer.init()` (full pipeline) and a second one in
`AppViewModel.kt:355-358` feeding only the GL passthrough background. Under load, each can miss
different frames — the background image and the computed tracking data aren't guaranteed to be
the same physical frame.

**Fix options**: (a) Collapse to one collector — have `SpatialFrameProducer` forward each
processed bitmap to `ARRenderer.submitCameraFrame` itself, guaranteeing consistency. (b) Keep two
collectors but have the second read from "last delivered to producer" instead of subscribing
independently — weaker guarantee, still allows drift if producer itself drops a frame. Recommended: (a).

### 5.4 `sfmScale` calibration has no explicit staleness bound between ARCore callbacks — DONE (Phase 8 item 1)

`FusedDepthSource.updateScaleCalibration()` gates on displacement *magnitude* but not *time*
between callbacks — irregular ARCore callback timing under load could compare a large-but-old
displacement against fresh SfM data.

**Fix options**: (a) Add a wall-clock delta check alongside the existing magnitude checks; skip
the update if too much time elapsed between callbacks. Small, contained. (b) Leave as-is if
ARCore callback cadence is empirically regular enough on real target devices — unverifiable
without a device.

### 5.5 `feedFarPlaneAnchor()` compares a newly detected plane against a possibly-old ARCore depth reading — DONE (Phase 8 item 1)

`lastArcoreMeanDepth` (written only in `ArcoreCallback.onPoints`) is used as a "near anchor" for
a far-plane calibration that can fire at an unrelated moment. Same category as §5.4.

**Fix options**: (a) Add a timestamp check comparing plane-detection time against
`lastArcoreMeanDepth`'s last-write time; skip/warn if too stale. Same shape as §5.4's fix.

### 5.6 `AppViewModel.loadedAsset` is a plain, non-volatile `var` read across threads at export time — DONE (Phase 4.2)

`AppViewModel.kt:250` (`internal var loadedAsset`) is written on the Main dispatcher
(`:343`, inside an `assetManager.loadedAsset.collect` coroutine) and read on an IO-dispatcher
coroutine at export time (`:1312`, `recordingManager.exportAndAppend(..., loadedAsset)`) — no
`@Volatile`, no synchronization. Same JMM-visibility class as §4.6. **Concrete scenario**: reload
a custom asset immediately before "stop & export" — the export coroutine isn't guaranteed to
observe the new asset, and could bake in the *previous* asset's bind pose/mesh/textures instead
of the one currently displayed.

**Fix options**: (a) Mark `@Volatile` — minimal, matches §4.6's fix. (b) Snapshot `loadedAsset`
into a local `val` on the Main thread before launching the IO-dispatcher export coroutine,
passing the snapshot as a parameter — removes the cross-thread read entirely rather than just
guaranteeing visibility. Recommended: (b) — cleaner, and avoids the same field needing a second
look if a similar hazard is found nearby later.

## 6. Documentation/reality mismatches and wasted work

### 6.1 `PointCloudStore`'s own doc comment describes a threading model its actual callers don't follow — DONE (Phase 7)

`util/PointCloudStore.kt:14` states `snapshot()` "is called from the GL thread" — actual callers
are `AppViewModel.kt:487,490,542,545`, inside `handPipeline.processed.collect`, not the GL
thread. Whether internal synchronization is adequate for the real calling thread wasn't
evaluated — the doc comment itself asserts a contract that isn't what's happening, same failure
shape as the stale "AppViewModel is a coordinator only" comment found and fixed earlier.

**Fix options**: (a) Correct the doc comment to state the real calling contract, and audit the
internal synchronization primitive against that real contract (not the documented one) to
confirm it's actually adequate. (b) If GL-thread access is genuinely needed elsewhere and was
the original intent, add it there instead and keep the doc — but no such caller was found in
this pass. Recommended: (a).

### 6.2 `WhiteScreenOverlay`'s doc comment promises an API that doesn't exist, and nothing calls it — DONE

`WhiteScreenOverlay.kt:16-29`: doc comment says "call `trigger()` to fire a single 200ms flash,"
but there is no `trigger()` — the real signature takes `visible: Boolean` and runs an *infinite*
`RepeatMode.Restart` transition, not a one-shot flash. Zero callers found anywhere.

**Fix applied**: option (a) — the flash cue was worth having (visible feedback for a completed
pose capture during a scan). Rewrote `WhiteScreenOverlay` to take a `trigger: Int` token instead
of `visible: Boolean`, driven by `Animatable(0f)` + `LaunchedEffect(trigger) { snapTo(1f);
animateTo(0f, tween(200)) }` — a genuine one-shot 200ms flash, matching the doc comment for real
this time. Wired to `Scanner.poseCaptureDone` (`ScanCoordinator.start()` now collects it
unconditionally and increments `AppUiState.captureFlashToken`), called from `MainActivity` as
`WhiteScreenOverlay(trigger = uiState.captureFlashToken)`.

## 7. Resource lifecycle (GL leaks and re-initialization — a different bug class from §4–§5's races)

### 7.1 Every renderer `release()` method has zero callers — DONE (Phase 5)

`DepthMeshRenderer`, `SkinnedMeshRenderer`, `LiveMeshRenderer`, `CameraPassthroughRenderer`,
`PointCloudRenderer` (the last never even constructed anywhere — fully dead), and
`ModelViewerRenderer` all define a `release()`/cleanup method that deletes GL objects
(`glDeleteProgram`/`glDeleteBuffers`/`glDeleteVertexArrays`). Grepped the whole repo for
`.release()` — zero call sites. `HandRenderer`, `BodySkeletonRenderer`, `FaceSkeletonRenderer`,
and `DepthCloudRenderer` (component mode) don't even have a `release()` method at all.

**Fix options**: (a) Add the missing call — `ARRenderer` (or whatever owns the GL lifecycle)
should call `release()` on every sub-renderer at the point its own GL resources are torn down.
(b) For the renderers with no `release()` at all, add one following the existing pattern in the
renderers that already have it, then wire it in per (a). Recommended: both, as one change — this
is the render-module analogue of §10's disconnected-feature pattern (cleanup code built, never
invoked), same fix shape (finish the wiring, don't leave it half-built).

### 7.2 Only 1 of 9 renderer `init()` methods guards against being called twice — DONE (Phase 5)

`DepthCloudRenderer.kt:69` is the sole re-entrancy guard (`if (program != 0) return`). Every
other `init()` unconditionally re-runs `glGenBuffers`/`glGenVertexArrays`/`compileProgram` into
the same fields, overwriting the previous handle values. `ARRenderer.onSurfaceCreated`
(`:200-207`) calls `.init()` on all 9 sub-renderers every time it fires, with no `release()`
beforehand (consistent with §7.1).

**Severity nuance, stated precisely**: `setPreserveEGLContextOnPause` is never set anywhere
(grep-verified), so on Android's documented default the EGL context is destroyed on
pause/resume — meaning `onSurfaceCreated` re-firing after backgrounding is *not* a real leak in
that specific case, since context destruction already frees the old GL objects. The genuine risk
is `onSurfaceCreated` firing on a *still-alive* context, which would leak — not confirmed to
happen in this app without a device to test on device/manufacturer-specific `GLSurfaceView`
quirks. **The one concretely identified repeat-init scenario**: `ModelViewerScreen.kt:61-118`
builds its `GLSurfaceView` in an `AndroidView` factory with no `DisposableEffect`/teardown at
all — repeated navigation to that screen within one process lifetime (no guaranteed EGL context
loss between visits) is a plausible, ordinary-usage path to a real leak.

**Fix options**: (a) Add the same re-entrancy guard pattern (`if (program != 0) return`) to the
other 8 `init()` methods — small, mechanical, safe. (b) Add a `DisposableEffect` to
`ModelViewerScreen` that calls the renderer's `release()` (once §7.1 wires it) on screen
dispose — directly closes the one concretely-identified leak path. Recommended: both — (a) is
cheap insurance, (b) fixes the actual reachable scenario.

## 8. Export correctness

### 8.1 BVH's fixed joint hierarchy forces a hard identity-pose snap when an occluded joint's grace period expires — DONE

`BodyRetargeter.kt:165-234` holds an occluded joint at `lastGoodRotation` during its grace
period, then drops it from the result entirely once grace expires. `MotionRecorder.writeBvhMotion`
(`:506,516,542`) fills any missing joint with `Quaternion.IDENTITY` on export, since BVH's fixed
per-frame channel layout has no "joint absent this frame" concept. Real exported effect: tracked
motion → frozen at last-known (during grace) → hard discontinuous jump to identity (grace
expiry) → another discontinuous jump back to real rotation on reacquisition. A genuine
double-snap artifact baked into the file, affecting both hand and body joints identically.

**Fix options**: (a) On grace expiry, hold at `lastGoodRotation` indefinitely instead of
snapping to identity, accepting a frozen (not moving, not visually jarring) joint until
reacquisition — trades "joint looks stuck" for "joint doesn't teleport." (b) Interpolate from
`lastGoodRotation` toward identity over N frames instead of an instant snap, if a frozen joint
is considered worse than a slow drift. Recommended: (a) — a frozen joint reads as "occluded,
tracking paused," which is closer to the truth than either a hard snap or a fabricated drift
toward a pose that was never observed.

### 8.2 BVH's single global `Frame Time` can't represent uneven capture spacing from dropped frames

`MotionRecorder.kt:444-450` correctly averages real timestamps into one `Frame Time` rather than
assuming constant 30fps — right approach for total clip duration, but BVH format only supports
one uniform value for the whole file, so uneven real spacing (e.g. a burst of `tryEmit` drops in
one segment, none in another) gets homogenized, distorting internal timing even though total
duration is preserved. `GLBAnimationExporter.kt:161-163`, by contrast, writes each frame's real
timestamp as the glTF sampler's input time — the animated-GLB export doesn't have this
limitation, only BVH does, and it's a genuine BVH format constraint, not a code bug.

**Fix options**: (a) None fully solve this within BVH's format — it's an inherent limitation, not
a mistake. If precise timing matters more than BVH compatibility, prefer the GLB/glTF export
path for that use case. (b) Resample/interpolate frames onto a uniform grid at export time
(reduces distortion but doesn't eliminate it, and adds synthetic frames). Recommended: document
this as a known BVH-format limitation rather than "fix" it — pushing users toward GLB export
when frame-timing precision matters is the more honest answer than a partial resampling fix.

### 8.3 OSC-receive quaternions aren't validated before reaching the renderer (contained, doesn't reach export) — DONE (normalization half only)

`OscReceiver`'s bounds-checking on packet parsing is solid (verified), but parsed floats build
`Quaternion(qx,qy,qz,qw)` directly with no normalization or NaN/Inf guard, unlike
`BoneRetargeter`'s quaternions (always unit-length via `shortestArcQuaternion`). A malformed or
adversarial remote sender could push a non-unit or NaN quaternion straight to
`renderer.latestRetargetResult`. Confirmed this is confined to rendering — the RECEIVE-mode
collector never calls `motionRecorder.pushFrame`, so this cannot corrupt an exported file.
Separately, a naming inconsistency was found in the VMC address wiring (`OscReceiver.kt:57-58,294,303`
— a `VSF_BONE_POS` constant matched only in the rotation branch, while the position branch
matches a different hardcoded literal missing one letter) — flagged as a code-inconsistency
observation only; not confirmed against real VMC/VSeeFace sender behavior without external
interop testing.

**Fix options**: (a) Normalize (or reject) incoming quaternions before they reach the renderer —
small, contained, closes the validation gap regardless of the VMC question. (b) Investigate the
VMC address-constant inconsistency against the actual VMC protocol spec or a real VSeeFace
sender before deciding whether it's a real interop bug or intentional — needs external
verification this environment can't provide.

**Implemented (a)**: added `OscReceiver.sanitizeQuaternion()` — rejects (returns null, joint
untouched) any parsed quaternion with a non-finite (NaN/Inf) component, otherwise normalizes it,
applied at both quaternion-parsing call sites. **(b) still open** — the VMC naming question
needs external protocol/sender verification this environment can't provide, left as-is per the
doc's own caution rather than guessed at.

## 9. UI layer: recomposition cost (structural, not a correctness bug) — DONE (HUD-derivation half only)

`MainActivity`'s top-level composable collects `handPipeline.processed` directly (up to
hand-inference rate) to derive HUD values, re-running the ~1000-line composable body's
unmemoized derivations every inference frame (child composables still skip via stable-param
memoization). `ModelViewerScreen` writes `retargetResult`/`loadedAsset` directly in the
composable body by design, so the whole screen recomposes at retarget-frame rate whenever open
— scoped to viewer-open time only, so bounded.

**Fix options**: (a) Wrap the HUD-value derivations in `remember(handPipeline.processed value) { ... }`
or move them into a `derivedStateOf` so they only recompute when their actual inputs change, not
every collected emission. (b) Leave as-is if profiling shows the derivation cost is negligible
next to child-composable skip already happening — unverified without a device. Recommended: (a)
is cheap to try; worth doing given how many other findings in this document turned out to be
"assumed fine, wasn't" — this one hasn't been assumed, it's just unmeasured.

**Implemented (a)** for `HandyApp`'s three `HudOverlay` derivations (`landmarkCount`,
`activeSlots`, `handSide`) via `derivedStateOf`. Honest caveat found while implementing: these
three derivations are each O(≤2) (at most 2 tracked hands) — `sumOf`/`map.sorted().joinToString`/
`firstOrNull` are not themselves expensive, and `HudOverlay`'s parameters are already stable
primitives (Int/String), so Compose's existing positional-parameter equality check was already
skipping `HudOverlay`'s own recomposition when these resolved to the same value frame-to-frame.
`derivedStateOf` makes that guarantee explicit rather than incidental, but its practical impact
here is likely small — this specific instance was never the "unmemoized derivation is
expensive" case the finding worried about, just an unmeasured one that turned out cheap. The
larger, unaddressed part of this finding — `HandyApp`'s ~1000-line composable body re-executing
in full on every `processedHands` emission, and `ModelViewerScreen`'s viewer-open-scoped
recomposition — is a much bigger restructuring this pass didn't attempt (splitting a large,
visually load-bearing composable apart carries real regression risk with no way to visually
verify the result in this environment).

## 10. Incomplete/disconnected features — do not delete without a product decision

**Not dead code.** Doc comments, guard-flag patterns, and purpose-built consumers show these are
mostly-built features missing one connection, not leftover cruft. §3.2 is the rule this produced.

### 10.1 `AppViewModel.ensureFullBodyCollector()` is built but never started — one call site short of working — DONE (partial)

`fullBodyCollectorStarted` (a guard flag) and the function's "ensure..." naming are the standard
shape for a lazily-started, idempotent collector meant to be triggered from multiple entry
points. `enableBodyTracking()`'s own doc comment explicitly promises *"Body landmarks are merged
into `[latestFullBodyFrame]` every hand-pipeline frame"* — but its body never calls
`ensureFullBodyCollector()`. The feature — `FullBodyFrame` merging, feeding
`CompositeGestureClassifier.classify(FullBodyFrame, slot: Int)`, an overload built specifically
for it and otherwise unused — is missing exactly the call that starts it. Concrete effect:
`bodyWristHint` on AppViewModel's OSC-velocity path always reads `null`, `perfMonitor`'s
body-confidence HUD metric is always `0f`, full-body-context gesture classification never runs.

**Fix applied**: started the collector from `initCamera()`, and rewrote its body to read
`SpatialFrameProducer.latestBodyResult`/`latestBodyLandmarks` (both newly exposed as public
`StateFlow`s) instead of calling `bodyRetargeter.retarget()` a second time — avoids the live
double-invocation this finding warned about. This incidentally fixes `latestBodyRetargetResult`
having had zero writers, so the HUD's body-confidence indicator now reflects real data instead of
always reading `0f`. **Still open, deliberately not done**: wiring
`CompositeGestureClassifier.classify(FullBodyFrame, slot)` itself — `latestFullBodyFrame` still
has zero consumers beyond this collector populating it. Whether full-body-context gesture
classification should actually run is a product decision this document doesn't make unilaterally
(§3.2); the duplicate-computation bug this finding centered on is fixed regardless of that
decision.

### 10.2 `renderer.scanCloudPoints` — a working, wired UI toggle with no data feed — DONE

`ARRenderer.showCloud` is toggled live and gates a fully functional render path
(`depthCloudRenderer.updateAndDraw(...)`) — both work. Nothing populates `scanCloudPoints`
itself. Reads as a live point-cloud preview feature (likely meant to sample
`spatialLayer.fusedDepth.store.snapshot()`, the same source scan-capture code already reads)
whose render half was built and whose data-feed half was never connected.

**Fix applied**: option (a). The per-hand-frame collector in `AppViewModel` now samples
`spatialLayer.fusedDepth.store.snapshot()` into `renderer.scanCloudPoints` whenever
`uiState.value.showCloud` is true — same source scan-capture already reads, sized correctly via
the store's own two-call `snapshot()` contract (call once for required size, once with a
properly-sized buffer).

### 10.3 `SpatialFrameRouter`'s posed-scan capture branch — an asymmetric migration, not dead weight — DONE (Phase 2, same motion as §4.3)

`route()`'s capture-append branch is gated on `isScanActive && !isFreeformActive`, but
`startScan()` (posed scans) never touches `router.isScanActive` — it manages capture entirely
inline, the way the codebase worked before `SpatialFrameRouter` existed. `startFreeformScan()`
sets both router flags *and* still runs its own inline capture (§4.3's bug). Freeform scanning
was migrated to the router but its old inline path was never removed; posed scanning was never
migrated at all.

**Fix options**: (a) Make `SpatialFrameRouter` the single owner of capture for both scan types —
wire `isScanActive` in `startScan()` too, delete `AppViewModel`'s inline capture for both posed
and freeform. This is the consistent fix and resolves §4.3 in the same motion. (b) Leave posed
scanning inline permanently and only fix freeform's duplicate — smaller change, but leaves the
codebase with two different capture architectures for what's conceptually one feature.
Recommended: (a).

### 10.4 `FreeformPanel` and its `WorkflowMode.FREEFORM` reference — orphaned from a prior design, not "one line away" — DONE (retired)

`FreeformPanel` (`MainActivity.kt:952-1068`) has zero call sites. Unlike §10.1/§10.2, this isn't
a near-miss — its doc comment references `WorkflowMode.FREEFORM`, a value that no longer exists
in `WorkflowMode.kt` (whose own comment says "SCAN and FREEFORM are no longer primary modes").
Reviving this would need redesigning which mode triggers it, not just adding a missing call.

**Resolved**: confirmed `WorkflowMode` has no `FREEFORM` member (verified against the enum
directly, not inferred) and that the freeform-scan UX is already fully served by a different,
live mechanism — `onFreeform = { vm.startFreeformScan() }` via the calibrate-sheet flow, plus the
already-working `FreeformScanOverlay`. This is genuinely superseded code, not a missing wire —
deleted `FreeformPanel` and its section header comment entirely (one of the two "retire"
outcomes this document's own §3.2 rule allows for, reached only after confirming zero call sites
and a working replacement, not a unilateral guess).

### 10.5 `rPPGSource.snsProxy` — a deliberately designed metric with no consumer, corrected from an earlier "safe to delete" misclassification — DONE

**This document previously (in this same research pass) recommended deleting this as wasted
computation. That was wrong, caught on review before anything was acted on** — exactly the
mistake §3.2 exists to prevent, made again despite the rule already being written down. Re-reading
`rPPGSource.kt`'s own class doc comment (lines 26-30) shows a dedicated `## SNS proxy` section:
*"High-frequency variability in [amplitude] correlates with sympathetic nervous system (SNS)
arousal. A simple measure is the coefficient of variation of [amplitude] over the last few
seconds."* `snsProxy` is a named, `@Volatile`, properly-`reset()`-integrated public property
sitting alongside `bpm`/`amplitude` — same construction, same care — not an incidental
leftover. It is computed correctly every warm frame (§6's original observation that nothing
reads it externally still holds, verified by repo-wide grep) but has no wired consumer, the
same shape as §10.1–§10.2: a real, deliberately-designed feature (a stress/arousal indicator,
presumably meant for a biometric or wellness-adjacent UI/OSC output) missing its connection.

**Fix applied**: wired it to OSC. Threaded `snsProxy` through `SpatialFrame.rppgSnsProxy` →
`SpatialFrameProducer` → new `OscStreamer.sendRppgSns()` → `SpatialFrameRouter`'s existing
`if (frame.rppgBPM > 0)` warm-up-gated block, right after `sendRppg(...)`. Deliberately used a
**separate new OSC address** (`/rppg/sns`) rather than extending `/rppg`'s existing argument
list, specifically so no existing OSC consumer expecting exactly `(amplitude, bpm)` on `/rppg`
breaks.

**Why this is flagged so explicitly**: it's evidence the mistake §3.2 was written to prevent
recurred even after the rule existed and even within a pass that was specifically re-reviewing
for exactly this failure mode. The rule (read the candidate's own doc comments before concluding
"unused" means "delete") only works if it's actually applied every time, not just to the cases
that inspired it.

### 10.6 `VoxelGrid`/`PointCloudExporter` — a real, different feature that looked like dead code — DONE

A repo-wide dead-code audit initially flagged `depth/VoxelGrid.kt` and `export/PointCloudExporter.kt`
as unreferenced (zero callers anywhere). Re-review before deleting (§3.2) found `VoxelGrid` is
not a duplicate of `PointCloudStore` — it's a different capability: `PointCloudStore` is a
capacity-bounded, age-ordered live buffer for the active scan; `VoxelGrid` spatially deduplicates
into one best-confidence point per 5mm world-space cell with no age-based eviction, and its own
doc comment says it's designed to pair with `SlamLite` (already live) for persistent room-scale
mapping as the camera moves — a distinct feature nothing in the app exposed.

**Fix**: wired both in rather than deleting either. `SpatialLayer.voxelGrid` now accumulates real
points from the existing depth-source callback, gated behind `setRoomMapActive(Boolean)`
(off by default, same shape as `setReconstructionActive` — no added per-frame cost unless a
caller opts in). `AppViewModel.toggleRoomMap()`/`clearRoomMap()`/`exportRoomMap()` expose it,
with `exportRoomMap()` reusing `PointCloudExporter.exportPly` as the actual output path instead
of that file staying an orphaned debug utility. **Not done**: no UI button/gesture calls these
yet — see §10.7, the same gap affects several other already-working methods, and picking where
this belongs in the UI is a product decision, not a technical one.

### 10.7 Several working `AppViewModel` methods have no UI trigger at all — DONE (partial)

Repo-wide grep for call sites of `toggleCloud()`, `toggleDepth()`, `toggleLiveMesh()` found none,
anywhere — not from `MainActivity`'s Compose tree, not from a gesture, not from anywhere except
their own declarations. `switchCamera()`/`toggleTorch()` are reachable, but only via the built-in
gesture shortcuts (`PEACE`/`ROCK` in `dispatchGestureShortcut`), not any visible button. Each of
these has fully working, previously-verified backend logic (§10.6's new `toggleRoomMap` etc. now
joins them in the same state) — this isn't dead code the way §10.1-10.5's features are dormant
for a missing wire; the logic runs fine the moment something calls it. The gap is purely
UI surface: no button, menu entry, or settings row currently reaches any of them.

**Fix applied**: added a new "SPATIAL" section to `SettingsScreen` with toggles/buttons for
`toggleCloud` (point cloud overlay), `toggleDepth` (TSDF depth reconstruction, with the same
front-camera guard `AppViewModel.toggleDepth()` already enforces surfaced as visible copy rather
than a silent no-op), and `toggleRoomMap`/`exportRoomMap`/`clearRoomMap` (room mapping, showing
the last export path when present). `MainActivity`'s `SettingsScreen(...)` call site now supplies
these from `uiState`/`scanState`. **Deliberately left unwired**: `toggleLiveMesh()` — it requires
a loaded scan asset (`assetManager.state.value.name != null`) and reads as contextually tied to a
model-viewing screen, not general app settings; wiring it into Settings would mean adding
asset-loaded-state plumbing to a screen that otherwise has none. `switchCamera()`/`toggleTorch()`
were left as-is (gesture-only), consistent with this finding's own observation that they already
have a deliberate gesture-only UX distinct from the other toggles.

## 11. Intentional multi-writer patterns — not bugs, don't "fix" into a violation

### 11.1 `renderer.latestRetargetResult` has two legitimate writers, mode-gated

`SpatialFrameRouter.kt:124` (SEND mode) and `AppViewModel.kt:1484` (RECEIVE mode, remote
puppeteering). Intentionally mutually exclusive by feature, not concurrent. Documented so a
future pass mechanically applying §3.1 doesn't collapse this into a bug that isn't one.

## 12. Verified clean — checked against §3.1 and found fine

- **`ARRenderer.loadedAsset` / `SpatialFrameRouter.loadedAsset`** — one writer sets both together;
  intentionally different representations, not a race.
- **`ARRenderer.liveMeshPositions`, `torchOn`, `depthMeshPositions`** — sequential/mutually
  exclusive by workflow, not concurrent.
- **`AppViewModel.uiState`, `scanState`** — standard multi-owner UI-state pattern, disjoint
  sub-fields via `.copy()`, no collision.
- **`AppViewModel.incrementalCarver`/`incrementalTrainJob`** — standard cancel-then-reassign
  lifecycle, not a race.
- **`motionRecorder.pushFrame`, `oscStreamer.sendFrame`, `renderer.updateBodyPose`,
  `renderer.applyMorphWeights`** — each exactly one production call site (all via
  `SpatialFrameRouter`).
- **`CLAHEAnalyzer`** — exactly the two known consumers, no missed third.
- **Camera switch (`CameraController.switchCamera()`)** — no GL-side reset needed and none
  happens; aspect-dependent state is handled per-frame (`camAspect` updated live,
  `CameraPassthroughRenderer` reallocates texture storage on dimension change), not via a stale
  cached buffer.
- **`DepthMeshRenderer`'s scan-restart buffer handling** — VBO only grows, never shrinks, but
  `draw()` always renders `meshPositions.size` from the current call, so a smaller subsequent
  scan renders correctly rather than blending with leftover geometry.
- **`MotionRecorder`'s threading contract** — matches its documented contract exactly
  (`start`/`stop`/`reset`/`pushFrame`/`frameSnapshot` all synchronized on `frames`).
- **`GLBExporter`, `GLBAnimationExporter`, `TextureBaker`, `PointCloudExporter`** — stateless
  objects, no cached fields, no stale-asset references of their own; all data passed in fresh at
  call time.
- **`OscReceiver`'s own mutable state** (`_frames`, `socket`, `scope`) — exactly one writer path
  each, consistent with its documented same-thread contract.
- **Compose `remember`/state hoisting** — every `remember { mutableStateOf(...) }` checked in
  the UI directory is local, ephemeral toggle state correctly scoped; none hoisted in a way that
  resets unexpectedly.
- **Compose side effects** — only three `LaunchedEffect`s exist in the whole UI layer, zero
  `DisposableEffect`s; none acquire a resource needing cleanup (though one of the three has the
  logic bug in §4.2, independent of lifecycle/cleanup concerns).

## 13. Test coverage

Exactly one test file exists in the entire repo: `app/src/test/java/com/arhand/PureComputationTest.kt`
(507 lines, confirmed via exhaustive search across every module — no `androidTest/` directory
anywhere). It covers: `MarchingCubes` SDF→mesh extraction, `HandBiometrics.compute()`, quaternion
math edge cases in `BoneRetargeter`, `QualityEngine` frame scoring, and `MotionRecorder`'s ring
buffer/Euler conversion — all pure, deterministic, single-threaded, driven by direct sequential
calls on the test thread.

**Zero tests exercise any concurrency/ownership finding in this document.** No test constructs
`FreeformScanner`, `SpatialFrameRouter`, or `AppViewModel`. No test touches `depthConfidence` or
`restJointPositions`. No test file imports `kotlinx.coroutines.test` or uses
`runTest`/`TestCoroutineScheduler`/`Dispatchers.Default`/`launch`/`delay` — nothing in the suite
exercises timing-dependent behavior in any form. **Practical consequence**: every finding in §4–§9
of this document could regress or get "fixed" incorrectly without CI ever noticing — a fix's
correctness can currently only be confirmed by manual on-device testing, the same limitation
noted throughout this document for runtime behavior generally.

**Fix options** (general direction, not a specific implementation): (a) Add ViewModel-level tests
using `kotlinx-coroutines-test`'s `runTest`/`TestDispatcher` to drive `AppViewModel`/`SpatialFrameProducer`/
`SpatialFrameRouter` through the specific scenarios in §4 (e.g. assert `FreeformScanner.update()`
is called exactly once per emitted frame) — directly closes the coverage gap for the highest-impact
findings. (b) At minimum, add regression tests for the two confirmed live bugs (§4.1's UV
projection, §4.2's stale-modal logic) before fixing them, so the fix is provably correct and the
bug can't silently return. Recommended: (b) as a minimum bar for any future fix in this codebase,
(a) as the larger investment if this project continues to grow.

## 14. Process: verification, enforcement, and the case studies behind it

### 14.1 Checklist, re-runnable any time

- `grep -rn "BoneRetargeter(\|BodyRetargeter("` — expect 2 legitimate `BoneRetargeter` owners + 1
  test; exactly 1 `BodyRetargeter` construction, shared by `SpatialFrameProducer`'s live
  collector and `ensureFullBodyCollector`'s currently-unstarted one (§10.1).
- `grep -rn "freeformScanner.update\|scanner.update"` — currently 2 and 1 call sites
  respectively; freeform should become 1 once §4.3 is fixed.
- `grep -rn "\.release()"` — currently 0 call sites for any renderer; should be non-zero once
  §7.1 is fixed.
- `grep -rn "ensureFullBodyCollector"` — currently definition-only; resolve per §10.1/§3.2, not
  by silent deletion.
- CI (`Build APK` workflow) is compile-only. Every claim in this document was verified by reading
  method bodies and tracing call sites directly, not by assumption or by trusting existing
  comments. Runtime behavior can only be confirmed by testing on a real device — none is
  available in this environment (see §13 for why CI passing doesn't currently verify any of this
  either).

### 14.2 Case study: `ControlPanel.kt` — the regression §3.2 is meant to prevent

`ControlPanel.kt` was deleted as dead code because its composables had no callers. The same file
also defined shared color constants (`UIBg`, `Plasma`, `Warn`) imported by ~10 other files.
Deleting it broke the build; CI caught it, fixed by creating `Colors.kt`. Confirmed historical
event, the standing case study for why §3.2 rule 1 exists.

### 14.3 Case study: the ~21k-line "duplicate app-module code" deletion — re-audited, confirmed safe

Commit `52b9f54` removed 87 files under `app/src/main/java/com/arhand/{camera,depth,export,
mocap,render,scanner,tracking,util}/` — pre-modularization copies of code also living in the
corresponding library modules. Independently re-audited: every one of the 87 deleted files was
diffed against its module counterpart *as both existed at the moment of deletion*. **69 were
byte-for-byte identical.** The remaining **18 differed**, and every difference was the module
version being ahead — package-path corrections for already-relocated functions (the exact
mechanism behind the `ScanPipeline` build failure fixed immediately after, `6304cd3`), or
features present in the module version and absent from the stale copy (GAP-2 morph deltas, v27
OSC methods independently confirmed live today, IMU pre-rotation, occlusion hysteresis). No file
showed the reverse. Confirmed safe, not re-asserted.

## 15. Architecture contracts

### 15.1 Concurrency

| Work category | Dispatcher | Rationale |
|---|---|---|
| Camera acquisition, YUV/RGBA conversion | CameraX's own executor | Owned by CameraX |
| MediaPipe hand/body/face inference | GPU delegate → CPU fallback (verified in all three of `HandTracker`/`BodyPipeline`/`FacePipeline`) | Correct, unmodified |
| Core depth-fusion channels (DA2, SLAM, stereo, RS-stereo, PSP) | `Dispatchers.Default` | Appropriate for CPU-bound batch work, but see §5.2-5.3 for cross-consumer correlation gaps this sharing introduces |
| Frame routing to renderer/OSC/motion-capture (`SpatialFrameRouter`) | Dedicated single thread (fixed this session) | Latency-sensitive; must not share a pool with unbounded CPU-bound work |

### 15.2 Degradation

Every depth/tracking source follows the same `isAvailable`-checked-by-every-consumer shape:
`StereoDepthSource`, `DepthAnythingSource`, ARCore (falls back to "SfM+Photo only"), and
MediaPipe's GPU→CPU delegate fallback. Consistent, load-bearing — follow it for any new source.

## 17. Perceived quality: latency, occlusion realism, low-light detection

Triggered by direct user reports after using the app: skeleton motion lags visibly behind real
movement, occluded/hidden parts aren't extrapolated the way a person would expect, and detection
degrades badly in near-dark conditions. Investigated by tracing the actual per-frame pipeline and
comparing it against a low-light prototype (`DarkVision.jsx`, a browser demo of manual-exposure +
multi-frame-stacking + multi-scale-CLAHE dark-scene enhancement) the user pointed to as an example
of depth/detail this app's own low-light path is missing.

### 17.1 Hand tracking runs at half camera rate by default, before any GPU-budget pressure exists — DONE, plus a bigger related find

`FrameThrottler` (`util/FrameThrottler.kt:25`) initializes `inferEvery = 2` — MediaPipe hand
inference only runs on every *other* camera frame from the start, not because the GPU is
over budget (that's `reportInferenceMs`'s job, which can lower it back toward `minEvery = 1`),
but as the unconditional starting point. On a 30fps camera stream this puts a 2× cut (~15fps hand
sampling) under the pipeline's own retarget/smoothing/render stages before any adaptive logic has
even measured real inference cost. `reportInferenceMs` only converges `inferEvery` toward 1 once
several frames measure comfortably under `targetMs * 0.5` (10ms) — real GPU-delegate MediaPipe
hand inference on mid-range hardware is commonly in the 12–25ms range, close enough to the 20ms
target that the throttler can plausibly hover at `inferEvery = 2` indefinitely rather than settle
at 1, meaning this isn't just a brief startup cost.

This compounds with deliberate smoothing latency already in the pipeline by design — the One Euro
Filter (`HandPipeline.kt:69-71`, `OEF_MIN_CUTOFF_XY = 0.5f`) trades responsiveness for jitter
rejection, and Temporal Depth Fusion runs before it. None of these are bugs individually (OEF's
whole point is a cutoff/latency tradeoff; the throttler's whole point is adaptive GPU shedding) —
the issue is the *stacked default*: half-rate sampling as the unconditional starting point, feeding
filters that are themselves tuned for smoothness over responsiveness, with no on-device
measurement in this environment to say where the resulting total lag actually lands.

**Fix applied**: option (a) — `FrameThrottler.inferEvery`/`countdown` now initialize to `minEvery`
instead of a hardcoded 2 (`reset()` too), letting `reportInferenceMs` shed *down* from full rate
under real measured load instead of starting pre-shed. Option (b) (retuning OEF cutoff/beta) is
still deliberately not done — a genuine UX tradeoff that needs on-device feel-testing, not a
blind constant change.

**A bigger related find, discovered while implementing this fix**: `HandPipeline.predictSkipFrame()`
already existed — fully implemented, carefully commented (its own "FIX-3" doc comment), designed
to project landmarks forward by their last known velocity on frames `FrameThrottler` decides to
skip — but had **zero callers anywhere in the repo**. Without it, every throttled-skip frame left
`handPipeline.processed` completely unchanged: the rendered hand held a perfectly static position
for the entire skipped interval (up to `maxEvery` frames, more when idle-doubled), then snapped to
the next real detection. That's a visible stutter on *every* ordinary throttle cycle, independent
of occlusion — plausibly the single largest contributor to reported skeleton lag/roughness, bigger
than the half-rate-baseline issue above.

**Fix applied**: wired `handPipeline.predictSkipFrame(nowMs)` into
`SpatialFrameProducer.processBitmap()`'s throttle-skip branch. While wiring it in, also found and
fixed a real bug in `predictSkipFrame()` itself: it computed `dt` as the delta since the *previous
call* to itself (`nowMs - lastPredictMs[slot]`), re-based from the same frozen `activeLms[slot]`
every time — on consecutive skip frames this under-extrapolated, advancing one small step on the
first skip frame and then effectively re-freezing for the rest of the skip run instead of
continuing to move. Fixed by using `OcclusionEngine.velState`'s own timestamp (only advanced by
real detections) as the "time since last real detection" reference instead — cumulative and
correctly fading over `VEL_EXTRAP_MAX_SEC`, and removes the now-redundant `lastPredictMs` field
entirely. The same corrected extrapolation (factored into a shared `extrapolateVelocity` helper)
is also used by §17.2's whole-hand-dropout fix below — one model, two call sites.

### 17.2 Occlusion inference is shallower than it looks, and one of its four signals is inert — DONE (a, b); (c, body) not done

`OcclusionEngine.detectOcclusion()` combines four checks, but one is dead: `HandTracker.kt:138`
sets `visibility = lm.visibility().orElse(1.0f)`. MediaPipe's **Hand** Landmarker (unlike Pose
Landmarker, which genuinely computes per-landmark visibility) does not populate a real visibility
score for hand landmarks — the field exists in the shared landmark schema but Hand Landmarker
never writes it, so `.orElse(1.0f)` resolves to 1.0 on effectively every landmark, every frame.
`OcclusionEngine.applyInference()`'s check `if (lms[i].visibility < VISIBILITY_THRESHOLD)` (line
217) is consequently a no-op in practice — occlusion detection actually rests entirely on the
other three checks (segment-length ratio, Z-chain reversal, palm-plane dot product), all coarse
3D self-consistency heuristics with no direct visual signal of what the camera can actually see.

Separately, per-joint inference (`inferLandmark()`) only runs while MediaPipe is still reporting
*some* landmarks for that hand at all (`raw != null` in `HandPipeline.update()`). If the whole hand
drops out of MediaPipe's detection (heavier occlusion — hidden behind an object, crossed fully
behind the other hand, moved out of frame briefly), the pipeline takes a different, cruder path:
it holds the *last known static pose* for `GRACE_FRAMES = 8` frames (~500ms at the effective
~15fps rate from §17.1), with **no velocity extrapolation at all**, then drops the hand entirely.
Contrast with `inferLandmark()`'s per-joint blend (FK continuation 50% + velocity 35% + short
history average 15%) for landmarks MediaPipe is still (unreliably) reporting within an otherwise-
detected hand — a real, if shallow, extrapolation model exists at the joint level but not at the
whole-hand level.

Body tracking has no equivalent inference layer at all: `BodyRetargeter`'s visibility gating
(`BODY-4`, real visibility scores this time — Pose Landmarker does compute them) holds an occluded
joint at `lastGoodRotation` indefinitely once grace expires (§8.1's fix), but never extrapolates
motion the way `OcclusionEngine.inferLandmark()` does for hands — an occluded body joint just
freezes, full stop.

None of this reaches "interpret what the camera can't see" in the sense of true kinematic/prior-
based inference (e.g., inferring a curled fist's actual finger positions from hand structure, or a
body part's plausible pose from the visible rest of the skeleton) — the existing "inference" is
short-horizon linear/FK continuation, which degrades to a frozen or drifting guess for anything
beyond brief (~200ms, `VEL_EXTRAP_MAX_SEC`) occlusion, by design.

**Fix applied**: (a) removed the dead visibility check from `OcclusionEngine.applyInference()`
entirely (and its now-unused `VISIBILITY_THRESHOLD` constant) rather than leave a check that
implies a fourth real signal exists when it doesn't — occlusion detection is honestly
3-heuristic-only now, matching what actually runs. (b) `HandPipeline.update()`'s whole-hand-dropout
branch (`raw == null`, within the `GRACE_FRAMES` window) now extrapolates by each landmark's last
known velocity via the same `extrapolateVelocity` helper §17.1's `predictSkipFrame` fix uses,
fading to a full freeze after `VEL_EXTRAP_MAX_SEC` instead of holding a hard static freeze for the
whole grace window — same shape as §8.1's BVH hold-at-last-known, applied one layer up, at the
tracking layer instead of the export layer.

**Not done**: (c) body tracking still has no velocity-extrapolation layer — `BodyRetargeter`'s
grace-period hold stays a hard freeze. Body's visibility signal is real (Pose Landmarker
genuinely computes it, unlike Hand Landmarker), so unlike (a) there's no dead-check cleanup
needed there; adding motion prediction would mean giving `BodyRetargeter` its own velocity-EMA
state per joint, a large enough change to `mocap`'s pure-retargeter design (§8.4) to warrant its
own pass rather than bundling it in here. None of (a)-(c) are "make the app see through
occlusion" in the sense of true kinematic/prior-based inference (e.g., inferring a curled fist's
actual finger positions from hand structure) — that would need a genuinely different approach (a
learned prior over hand/body pose space); these are honest fixes to what's already attempted, not
a promise of occlusion-invariant tracking.

### 17.3 CLAHE contrast enhancement is computed every scan frame but never applied to the image anyone (or MediaPipe) sees — the single biggest lever for low-light detection — DONE (a-c for detection; d and the depth-channel extension deliberately not done)

`CLAHEAnalyzer.process()`'s own doc comment states it plainly: *"Returns a contrast score [0,1];
the enhanced bitmap is never materialised."* Confirmed by every call site (repo-wide grep): its
one caller, `SpatialFrameProducer.processBitmap()` line 287, only runs it `if (scanActive)`, and
its one consumer, `AppViewModel`'s `claheContrast`, feeds `Scanner`/`FreeformScanner`'s pose
*quality gating* — a scan-progress metric, not a rendered or tracked image. The actual
tile-equalized `outLum` buffer this class computes every call is discarded after the contrast
score is read off it. MediaPipe hand/body/face detection, the ARCore/SfM depth pipeline, and the
on-screen camera passthrough all receive the raw, unenhanced camera bitmap in every lighting
condition — nothing in this codebase does any image-domain low-light enhancement before
detection, ever, scan or no scan.

This is the direct answer to "why is detection so much worse near-dark": there is no image
preprocessing step improving what the neural network (or a human) can see in a dark frame. The
`DarkVision.jsx` prototype the user pointed to demonstrates three techniques with zero equivalent
anywhere in this app:

1. **Manual exposure/ISO** (`applyHardwareExposure`) — pins `exposureMode`/`focusMode` to manual
   and drives `exposureTime`/`iso` to the camera's own advertised maximum. This app's camera setup
   (`GrayscaleCamera.kt:94`, `CameraController.kt`) only ever sets `CONTROL_MODE_AUTO` — full
   auto-exposure, which is tuned by the platform for balanced daylight/indoor behavior (avoiding
   motion blur, holding frame rate), not for wringing out a sensor's true dark-scene floor.
   `Camera2Interop` is already a live dependency here (used for FPS-range selection, ArCore §4.8's
   fix) — the same mechanism DarkVision's `applyHardwareExposure` uses is directly reachable, not
   a new dependency.
2. **Multi-frame stacking with motion alignment** — `DepthAnythingSource` has a *conceptually*
   similar mechanism already (`S2.4`'s `accumBuf`/`stationaryFrameCount` — stationary multi-frame
   noise averaging), but it's scoped entirely to DA2's *depth* estimate, not the RGB frame feeding
   MediaPipe. No equivalent averaging/alignment exists for the tracking-facing image.
3. **Multi-scale CLAHE + gamma lift + unsharp mask, actually applied to a bitmap** — this app's
   `CLAHEAnalyzer` computes the equivalent of DarkVision's single-scale CLAHE pass internally
   every call, then throws the result away (see above). Gamma lift and unsharp mask have no
   equivalent at all in this codebase.

**Fix applied**: new `camera.LowLightEnhancer` (hot-pixel suppression + multi-scale CLAHE +
gamma lift + unsharp mask, luma-only with chroma preserved so output stays a colour image, not
`DarkVision.jsx`'s grayscale-only result) and `CameraController.setLowLightExposure()` (manual
exposure/ISO via `Camera2CameraControl`'s dynamic capture-request options, toggled live without
rebinding the camera — unlike the prototype's bind-time-only `applyHardwareExposure`). Both are
dark-gated with hysteresis (`SpatialFrameProducer` samples mean luminance every frame — cheap,
always runs — and only engages enhancement/manual-exposure while actually dark), so normal
lighting sees zero behavior change. `enhance()` itself only runs on frames that reach past
`FrameThrottler`'s gate (MediaPipe's own inference rate), not on every raw camera frame — doing
the full-cost multi-pass pipeline unconditionally at full camera rate would have reintroduced
exactly the unthrottled-Core-layer mistake §4.7 already fixed once for SLAM/DA2. Applied to the
bitmap fed to `trackerMgr`/`bodyPipeline`/`facePipeline` (the "detection" half of the complaint).

**Deliberately not done**: (1) the depth channel (`depthShim` → SfM/Photometric) does *not*
receive the enhanced bitmap — it needs a consistent every-frame cadence for its own feature-
tracking continuity assumptions, which tying it to the inference-rate-gated enhancement above
would break, and extending it safely would need its own budget-gated cadence (like
`DepthChannelBudget` already gives SLAM/DA2) rather than reusing the detection path's gate. SL
depth is also explicitly left on the raw bitmap — its phase decoding depends on precise raw
intensity ratios from its projected pattern, which a contrast/gamma transform could distort in a
way plain detection wouldn't notice. (2) Multi-frame stacking (the prototype's single biggest
low-light win) is not ported to the live path at all — it trades added integration-time latency
for signal, directly working against the §17.1 fixes that just removed this class of lag from the
same pipeline. If a genuinely darker capability is wanted, it belongs in a separate still-capture
mode that can afford the latency, not the live per-frame detection feed. Both left as documented
follow-ups, not silently dropped.

Not verified on-device (no device access in this environment) — the exposure-time/ISO bounds, the
dark-mode hysteresis thresholds, and the enhancement's actual effect on MediaPipe's confidence are
all reasoned from queried sensor capabilities and the prototype's own values, not measured.

### 17.3a Non-blinding "another way" to approximate multi-frame stacking's benefit — DONE

Directly requested after the user rejected auto-torch ("blinding light in the eyes of the user is
not an option") as the fix for §17.3's deferred multi-frame-stacking gap: added continuous
temporal noise averaging (`LowLightEnhancer.accumulateTemporal`) instead of the prototype's
stack-then-flush model. Rather than blocking every output on N frames of integration time (the
exact added-latency problem §17.1 fixed once already), this runs a per-pixel exponential moving
average over luma that's always immediately usable and gets cleaner the longer the scene holds
still. It reuses `HandPipeline.motionMag`/`MOTION_GATE_THRESHOLD` — the same stillness signal
`FrameThrottler` already computes for its own idle-shedding — so it needed no new sensor or
heuristic: while still, frames blend in slowly (real noise-reduction gain, and this is exactly
when `FrameThrottler` is already skipping most inference anyway, so the averaging effectively
spends otherwise-idle cycles); the instant motion resumes, the accumulator snaps straight to the
current frame with zero blending, so a moving hand never picks up motion blur from this. This is
a smaller win than true multi-frame stacking (no motion-compensated alignment, so it only helps
during genuine stillness, not a moving low-light scene) but needs no light source and adds no
latency — a real, if partial, answer to "another way."

### 17.4 `FrameThrottler`'s idle-doubling had a real feedback bug: once doubled, it could never recover to the true GPU-justified rate — DONE

Found from a direct on-device HUD screenshot showing `INF 9.6ms` (fast — well under the 20ms
target) alongside `SKIP 81%` — a large, otherwise-unexplained gap between "inference is cheap"
and "inference almost never runs." Root cause: `reportInferenceMs()` computed its GPU-budget
estimate (`gpuBased`) as `inferEvery - 1` / `inferEvery + 1` — directly off of `inferEvery`, which
is sometimes the *idle-doubled* value (up to `maxEvery * 2`) written by the previous call, not a
clean undoubled rate. Once idle-doubling pushed `inferEvery` up to `maxEvery * 2` (e.g. 8), each
subsequent call computed `gpuBased = max(minEvery, 8 - 1) = 7`, then immediately re-doubled it
back (`min(maxEvery*2, 7*2) = 8`) — the exact same value it started with. The rate plateaus at
`maxEvery * 2` **permanently**, regardless of how fast inference actually measures, the moment the
hand ever goes still even briefly. Worse, this directly contradicts the class's own doc comment
("reverts to the GPU-budget-based rate immediately, with no ramp-up delay") — motion resumption
only decremented the inflated value by 1 per call, a slow linear crawl back down, not the
documented instant recovery. At `maxEvery * 2 = 8`, `shouldInfer()` returns true on only 1 in 8
camera frames (a 87.5% skip rate) — closely matching the reported 81%.

**Fix applied**: added a second field, `gpuRate`, that tracks the true measured-cost-based rate on
its own, independent of `inferEvery`'s idle-doubled value. `reportInferenceMs()` now derives
`gpuBased` from `gpuRate`, and idle-doubling is applied as a pure overlay when writing the public
`inferEvery` — `gpuRate` itself is never doubled, so it can't be contaminated, and the moment
motion resumes `inferEvery = gpuRate` directly (true immediate recovery, matching the doc for
real). This was very likely a meaningful contributor to "still lag" reports independent of (and
probably larger than) §17.1's inference-rate-baseline fix, which only addressed the *starting*
value, not this runaway-plateau bug in the ongoing adaptive logic.

**Related observation, not fixed here**: the HUD's `FPS`/`SKIP` readouts come from
`PerfMonitor.onFrame()`, called once per GL `onDrawFrame` (display-vsync cadence), while
`onInference()` only increments when a real MediaPipe call actually ran (camera-arrival cadence
filtered by this throttler). Those are two different clocks — if the camera's own capture rate
ever falls below the display's refresh rate (e.g. a long manual exposure in near-dark, or simply
a camera AE-negotiated rate below vsync), `SKIP` will read high even with this bug fixed, because
some GL redraws necessarily happen between camera frame arrivals with no new frame to show. This
makes `SKIP` a mix of "throttled by this class" and "camera hasn't delivered a new frame yet," not
a clean single-cause number — worth knowing when reading the HUD, not a functional bug in itself.

## 18. Explicitly out of scope

- Device-specific tuning (NNAPI/GPU-delegate speculation, resolution/quality downgrades) — the
  NNAPI attempt this session regressed both performance and accuracy and was reverted.
- Any code changes. This document is research and assessment only — §4–§13 are a prioritized
  findings list with fix options for a future implementation pass, not a changelog.
