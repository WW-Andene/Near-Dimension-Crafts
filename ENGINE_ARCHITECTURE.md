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

### 4.1 Exported hand model's baked texture uses a UV projection that doesn't match the mesh

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

### 4.2 `hasStoredModel` is never reset on scan cancel/failure — stale "scan complete" modal can reappear

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

### 4.3 `FreeformScanner.update()` called twice per frame during every active freeform scan

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

### 4.4 `renderer.handsData` / `renderer.mirrorX` written from two independent collectors every frame

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

### 4.5 `depthConfidence` written from three independent, unsynchronized coroutines — but never actually rendered

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

### 4.6 `restJointPositions` written from two independent coroutines, not even `@Volatile`

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

## 5. Timing & correlation gaps (values from different cadences combined as if simultaneous)

### 5.1 Cross-cadence staleness: Core writes some fields at raw-frame rate, Translation reads them at hand-inference rate

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

### 5.2 `SlamLite`'s optical flow can be stale relative to the DA2 frame it calibrates

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

### 5.3 Camera frame stream has two uncorrelated consumers that can each drop different frames

`CameraFrameProvider.frames` (`tryEmit`, non-suspending, drops on backpressure) has two
collectors: `SpatialFrameProducer.init()` (full pipeline) and a second one in
`AppViewModel.kt:355-358` feeding only the GL passthrough background. Under load, each can miss
different frames — the background image and the computed tracking data aren't guaranteed to be
the same physical frame.

**Fix options**: (a) Collapse to one collector — have `SpatialFrameProducer` forward each
processed bitmap to `ARRenderer.submitCameraFrame` itself, guaranteeing consistency. (b) Keep two
collectors but have the second read from "last delivered to producer" instead of subscribing
independently — weaker guarantee, still allows drift if producer itself drops a frame. Recommended: (a).

### 5.4 `sfmScale` calibration has no explicit staleness bound between ARCore callbacks

`FusedDepthSource.updateScaleCalibration()` gates on displacement *magnitude* but not *time*
between callbacks — irregular ARCore callback timing under load could compare a large-but-old
displacement against fresh SfM data.

**Fix options**: (a) Add a wall-clock delta check alongside the existing magnitude checks; skip
the update if too much time elapsed between callbacks. Small, contained. (b) Leave as-is if
ARCore callback cadence is empirically regular enough on real target devices — unverifiable
without a device.

### 5.5 `feedFarPlaneAnchor()` compares a newly detected plane against a possibly-old ARCore depth reading

`lastArcoreMeanDepth` (written only in `ArcoreCallback.onPoints`) is used as a "near anchor" for
a far-plane calibration that can fire at an unrelated moment. Same category as §5.4.

**Fix options**: (a) Add a timestamp check comparing plane-detection time against
`lastArcoreMeanDepth`'s last-write time; skip/warn if too stale. Same shape as §5.4's fix.

### 5.6 `AppViewModel.loadedAsset` is a plain, non-volatile `var` read across threads at export time

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

### 6.1 `PointCloudStore`'s own doc comment describes a threading model its actual callers don't follow

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

### 6.2 `WhiteScreenOverlay`'s doc comment promises an API that doesn't exist, and nothing calls it

`WhiteScreenOverlay.kt:16-29`: doc comment says "call `trigger()` to fire a single 200ms flash,"
but there is no `trigger()` — the real signature takes `visible: Boolean` and runs an *infinite*
`RepeatMode.Restart` transition, not a one-shot flash. Zero callers found anywhere.

**Fix options**: (a) If a one-shot flash cue (e.g. photo-capture feedback) was actually wanted,
implement `trigger()` for real and wire it to whichever event should cause it. (b) If abandoned,
retire the composable and its stale doc comment together, deliberately (§3.2) — this is a
product decision (was a flash effect wanted anywhere?), not a unilateral cleanup call.

## 7. Resource lifecycle (GL leaks and re-initialization — a different bug class from §4–§5's races)

### 7.1 Every renderer `release()` method has zero callers

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

### 7.2 Only 1 of 9 renderer `init()` methods guards against being called twice

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

### 8.1 BVH's fixed joint hierarchy forces a hard identity-pose snap when an occluded joint's grace period expires

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
duration is preserved. `GltfAnimationExporter.kt:161-163`, by contrast, writes each frame's real
timestamp as the glTF sampler's input time — the animated-GLB export doesn't have this
limitation, only BVH does, and it's a genuine BVH format constraint, not a code bug.

**Fix options**: (a) None fully solve this within BVH's format — it's an inherent limitation, not
a mistake. If precise timing matters more than BVH compatibility, prefer the GLB/glTF export
path for that use case. (b) Resample/interpolate frames onto a uniform grid at export time
(reduces distortion but doesn't eliminate it, and adds synthetic frames). Recommended: document
this as a known BVH-format limitation rather than "fix" it — pushing users toward GLB export
when frame-timing precision matters is the more honest answer than a partial resampling fix.

### 8.3 OSC-receive quaternions aren't validated before reaching the renderer (contained, doesn't reach export)

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

## 9. UI layer: recomposition cost (structural, not a correctness bug)

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

## 10. Incomplete/disconnected features — do not delete without a product decision

**Not dead code.** Doc comments, guard-flag patterns, and purpose-built consumers show these are
mostly-built features missing one connection, not leftover cruft. §3.2 is the rule this produced.

### 10.1 `AppViewModel.ensureFullBodyCollector()` is built but never started — one call site short of working

`fullBodyCollectorStarted` (a guard flag) and the function's "ensure..." naming are the standard
shape for a lazily-started, idempotent collector meant to be triggered from multiple entry
points. `enableBodyTracking()`'s own doc comment explicitly promises *"Body landmarks are merged
into `[latestFullBodyFrame]` every hand-pipeline frame"* — but its body never calls
`ensureFullBodyCollector()`. The feature — `FullBodyFrame` merging, feeding
`CompositeGestureClassifier.classify(FullBodyFrame, slot: Int)`, an overload built specifically
for it and otherwise unused — is missing exactly the call that starts it. Concrete effect:
`bodyWristHint` on AppViewModel's OSC-velocity path always reads `null`, `perfMonitor`'s
body-confidence HUD metric is always `0f`, full-body-context gesture classification never runs.

**Open product question**: is full-body-context gesture classification still wanted? If yes:
add the missing call, and redirect it to read `SpatialFrameProducer`'s own `_latestBodyResult`
output instead of calling `.retarget()` again (since Producer already retargets body
independently — starting this collector verbatim would reintroduce a live double-invocation).
If no: retire it deliberately and document that decision, don't leave it half-built indefinitely.

### 10.2 `renderer.scanCloudPoints` — a working, wired UI toggle with no data feed

`ARRenderer.showCloud` is toggled live and gates a fully functional render path
(`depthCloudRenderer.updateAndDraw(...)`) — both work. Nothing populates `scanCloudPoints`
itself. Reads as a live point-cloud preview feature (likely meant to sample
`spatialLayer.fusedDepth.store.snapshot()`, the same source scan-capture code already reads)
whose render half was built and whose data-feed half was never connected.

**Fix options**: (a) Wire `scanCloudPoints` to periodically sample `store.snapshot()` while
`showCloud` is on, the same source AppViewModel's own scan-capture code already reads — likely
the smallest change that completes the feature. (b) If a live preview was never actually wanted
(only post-scan viewing), retire the toggle deliberately. Recommended: (a) unless the product
answer to §10.1's similar question is "we're trimming half-built preview features" broadly.

### 10.3 `SpatialFrameRouter`'s posed-scan capture branch — an asymmetric migration, not dead weight

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

### 10.4 `FreeformPanel` and its `WorkflowMode.FREEFORM` reference — orphaned from a prior design, not "one line away"

`FreeformPanel` (`MainActivity.kt:952-1068`) has zero call sites. Unlike §10.1/§10.2, this isn't
a near-miss — its doc comment references `WorkflowMode.FREEFORM`, a value that no longer exists
in `WorkflowMode.kt` (whose own comment says "SCAN and FREEFORM are no longer primary modes").
Reviving this would need redesigning which mode triggers it, not just adding a missing call.

**Open product question**: was the freeform-scan UI intentionally redesigned to drop this panel
in favor of something else (in which case: retire it and its stale doc comment deliberately), or
is a `FreeformPanel`-shaped UI still wanted under the current `WorkflowMode` design (in which
case: rebuild its trigger condition, not just re-add a call site)? Flagged per §3.2 — needs a
decision, not a unilateral fix in either direction.

### 10.5 `rPPGSource.snsProxy` — a deliberately designed metric with no consumer, corrected from an earlier "safe to delete" misclassification

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

**Open product question, not a technical one**: was an SNS/stress indicator meant to reach the
UI or OSC output (e.g. alongside `sendRppg`)? If yes: wire it to a consumer (e.g. add it to
`OscStreamer.sendRppg`'s payload or a HUD element) — the computation is already correct and
tested-by-construction (mirrors `bpm`/`amplitude`'s pattern exactly). If no: retire it
deliberately and remove the doc section describing it, as one decision, not a unilateral
deletion of just the code while the doc comment still describes intent.

**Why this is flagged so explicitly**: it's evidence the mistake §3.2 was written to prevent
recurred even after the rule existed and even within a pass that was specifically re-reviewing
for exactly this failure mode. The rule (read the candidate's own doc comments before concluding
"unused" means "delete") only works if it's actually applied every time, not just to the cases
that inspired it.

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
- **`GLBExporter`, `GltfAnimationExporter`, `TextureBaker`, `PointCloudExporter`** — stateless
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

## 16. Explicitly out of scope

- Device-specific tuning (NNAPI/GPU-delegate speculation, resolution/quality downgrades) — the
  NNAPI attempt this session regressed both performance and accuracy and was reverted.
- Any code changes. This document is research and assessment only — §4–§13 are a prioritized
  findings list with fix options for a future implementation pass, not a changelog.
