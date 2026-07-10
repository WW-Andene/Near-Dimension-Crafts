# Redesign Plan

Turns `ENGINE_ARCHITECTURE.md`'s findings (diagnosis + fix *options*) into actual decisions and
an execution order. No code has been written yet — this is still planning. Every phase below
references the finding it resolves by section number; read the finding there for full evidence
before implementing.

## How this is organized

Findings that share a root cause are grouped into one workstream and fixed together, not
one-at-a-time in isolation — several findings in the assessment are two symptoms of the same
underlying gap (e.g. §4.3 and §10.3 are both "posed vs. freeform scan capture was migrated
inconsistently"). Fixing the shared root cause once resolves both.

Four items are **blocked** — they need your product decision before any code gets written,
because the "fix" is really "is this feature still wanted," not a technical choice. Everything
else has a concrete recommended design below, not just options. (A fifth, §10.7, was added later
in Phase 9 — a different-shaped discovery: working code with no UI trigger at all, not a
dormant/disconnected feature, but still a "what's actually wanted here" question, not a
technical one.)

## Phase 0 — Safety net before touching anything — DONE

§13 already recommends this: add regression tests for the two confirmed, highest-visibility live
bugs *before* fixing them, so the fix is provably correct and can't silently regress later, given
§13's other finding that nothing in the current suite would catch it either way.

- Test that `TextureBaker`'s and `GLBExporter`'s UV projections agree for a mesh *not* centered
  at the origin (the exact condition §4.1 breaks under).
- Test that completing a scan, then cancelling/failing a second one, does not re-trigger the
  result modal (§4.2).

## Phase 1 — The two confirmed live bugs users would actually notice — DONE

### 1.1 Unify the UV projection (§4.1)

**Decision**: extract one shared projection function (AABB-centered `u = atan2(vx-cx, vz-cz)`,
non-inverted `v = (vy-minY)/yRange` — `GLBExporter`'s version, since that's what the exported
mesh's UVs actually use) into a shared location both `GLBExporter` and `TextureBaker` call.
Delete `TextureBaker`'s independent formula entirely rather than just editing it to match — a
second independent implementation is exactly how this drifted apart once already.

### 1.2 Replace the `hasStoredModel` ambient check with an explicit per-scan signal (§4.2)

**Decision**: add a monotonically incrementing `completedScanId` (or a nullable
`lastCompletedScanId`) set only at the moment a scan *successfully* completes. The
`LaunchedEffect` in `MainActivity.kt:159-163` keys off a change in that id, not off the
ambient `hasStoredModel` boolean, so cancelling or failing a later scan can't re-trigger it.

## Phase 2 — Consolidate scan-capture ownership (resolves §4.3 and §10.3 together) — DONE

**Decision**: `SpatialFrameRouter` becomes the single owner of frame capture for both posed and
freeform scans.

Verification (step 2 below) found the dormant router branch was **not** actually equivalent to
`AppViewModel`'s inline capture in two ways — both fixed before wiring anything live:

- **Posed-scan gate/quality mismatch**: the router's branch gated only on `isScanActive` (no
  `Scanner.ScanState` check) and used `frame.frameConfidence` for quality, while
  `AppViewModel`'s inline capture gated on `scanner.status.value.state == CAPTURING` and used
  `scanner.status.value.quality` — a different signal. Flipping the flag as originally planned
  would have started capturing PREFLIGHT/COUNTDOWN frames with the wrong quality value, silently
  degrading scan quality. Fixed by adding the same `CAPTURING` gate and quality source to the
  router (`scanner` was already a constructor dependency, just unused for this).
- **Freeform bitmap texture-bake regression**: the router's freeform branch always passed
  `bitmap = null` to `freeformScanner.update()` with a comment "sampled separately via
  latestBitmap" — but nothing else in the router ever did that sampling. Deleting
  `AppViewModel`'s inline call (which passed the real bitmap) as originally planned would have
  silently broken texture baking for every freeform scan. Fixed by adding a
  `latestBitmapProvider: () -> Bitmap?` constructor parameter to `SpatialFrameRouter`, wired from
  `AppViewModel` as `{ producer.latestBitmap }` (already `@Volatile` on `SpatialFrameProducer`).

Implemented:
1. `router.isScanActive = true` in `startScan()`, `= false` in `cancelScan()` and both the
   success and catch paths of `processScan()` (mirroring freeform's existing lifecycle).
2. Router's posed-scan and freeform branches fixed as above, verified equivalent (not just
   diffed) before touching the inline path.
3. Deleted `AppViewModel`'s inline `capturedFrames`/`biometricFrames` writes (posed) and inline
   `freeformScanner.update()` call (freeform) — the exact cause of §4.3's double-invocation.
   `AppViewModel` still owns fused-depth/TSDF integration for both scan types; that was never
   part of this migration.

This is the same "make the router canonical, delete the legacy inline path" move already proven
safe once this session (the hand-retarget consolidation) — but this time the pre-deletion review
(the standing rule from earlier this session) caught two real, silent regressions the plan itself
didn't anticipate, confirming why that rule exists.

## Phase 3 — Delete the remaining duplicate-writer patterns — DONE

### 3.1 `renderer.handsData` / `renderer.mirrorX` (§4.4)

**Decision**: delete `AppViewModel`'s direct writes (`AppViewModel.kt:402-403`); router's
post-assembly values are canonical, consistent with every other consolidation this session.

### 3.2 `depthConfidence` (§4.5)

**Decision**: retire `AppViewModel.depthConfidence` entirely rather than consolidate to one
writer. Nothing renders it (confirmed: `MainActivity.kt:139` collects it into a dead local), and
the value actually shown to users (`HudOverlay.kt:76`) already comes from the safe, single-writer
`SpatialLayer.state.depthConfidence`. Remove the `MutableStateFlow`, its four writers
(`AppViewModel.kt:373,508,562,777`), and the dead collection in `MainActivity`. This is the one
place in the whole plan where "delete" is the right call for a whole field, not just a duplicate
call site — because the thing it would be consolidated *into* already exists, is already
correct, and is already what users see.

## Phase 4 — Cross-thread visibility fixes (mechanical, low-risk, bundle as one pass) — DONE

### 4.1 `restJointPositions` (§4.6)

**Decision**: mark `@Volatile`. The two writers are legitimately mutually exclusive by workflow;
visibility, not ordering, is the actual gap.

### 4.2 `AppViewModel.loadedAsset` at export time (§5.6)

**Decision**: snapshot `loadedAsset` into a local `val` on the Main thread before launching the
IO-dispatcher export coroutine, and pass the snapshot as a parameter. Cleaner than `@Volatile`
here since it removes the cross-thread read entirely rather than just guaranteeing visibility.

## Phase 5 — GL resource lifecycle (§7.1, §7.2) — DONE

**Decision**:
1. Add a `release()` method (following the existing pattern already used by
   `DepthMeshRenderer`/`SkinnedMeshRenderer`/etc.) to the four renderers missing one
   (`HandRenderer`, `BodySkeletonRenderer`, `FaceSkeletonRenderer`, `DepthCloudRenderer`
   component mode).
2. Add the same re-entrancy guard `DepthCloudRenderer` already has (`if (program != 0) return`)
   to the other 8 `init()` methods.
3. Wire `ARRenderer.onSurfaceCreated` (and `ModelViewerRenderer.onSurfaceCreated`, which also
   calls a sub-renderer's `init()` directly) to call `release()` immediately before `init()` on
   every sub-renderer.
4. Add a `DisposableEffect` to `ModelViewerScreen` that calls its renderer's `release()` on
   dispose — this is the one concretely reachable leak path identified (repeated navigation to
   that screen within one process lifetime, no guaranteed EGL context loss between visits).

**Correction made during implementation**: item 2 as originally scoped (bare re-entrancy guards,
with item 3 phrased as "at the point its own GL context is torn down") would have been a real
regression, not just insurance. `onSurfaceCreated` fires again after ordinary Android lifecycle
events (backgrounding without `setPreserveEGLContextOnPause`, or rotation — since `ARRenderer`
is owned by `AppViewModel`, which survives configuration changes, the *same* renderer instances
get a brand-new EGL context on rotation). A bare `if (program != 0) return` guard would see the
stale non-zero handle from the just-destroyed context and skip re-initialization entirely,
leaving the renderer trying to draw with invalid handles in the new context — silently broken
rendering after every rotation or background/resume, which is worse than the leak it was meant
to prevent (and that leak was already unconfirmed — see §7.2's own "not confirmed to happen
without a device" caveat). Confirmed reachable via a second call site found during
implementation: `ModelViewerRenderer.onSurfaceCreated` calls `skinnedRenderer.init()` directly
with no `release()` first, so the same class of renderer would have been affected there too.
Fixed by calling `release()` immediately before `init()` at both call sites — safe in every case
(release() on a virgin or already-released renderer is a guarded no-op), and it's what makes the
re-entrancy guards actually safe to keep instead of a hazard.

## Phase 6 — The other Core-layer correctness fix — DONE

### 6.1 `SlamLite` → DA2 flow correlation (§5.2)

**Decision**: pass the flow values as parameters directly into DA2's inference call at the
moment SLAM computes them for that exact bitmap (bundle bitmap + flow into one small data class
enqueued together), rather than DA2 reading whichever flow value happens to be freshest whenever
it gets around to running. This is the one timing/correlation finding worth fixing proactively
rather than just instrumenting — it's Core-internal, self-contained, and directly affects
hand-landmark Z-correction accuracy, unlike §5.1/§5.4/§5.5 which are lower-confidence and
touch ARCore/SfM calibration paths that are harder to verify without a device.

**Implemented** as `DepthAnythingSource.FlowSnapshot(mag, nx, ny)`. `SpatialLayer.processBitmap`
now returns the snapshot computed from that call's `slam.process(bitmap)` instead of writing it
into `da2.externalFlowMag`/`NX`/`NY` shared fields (removed entirely). `SpatialFrameProducer`
captures the return value and threads it through
`FusedDepthSource.processAuxSources(bitmap, slDepth, scope, flow)` into
`da2.processAsync(bitmap, flow, scope)`, which closes over it for the launched coroutine — so a
frame whose DA2 inference is still in flight keeps the exact flow reading it was enqueued with,
immune to being overwritten by a newer frame's SlamLite output.

## Phase 7 — Documentation/contract corrections (no behavior change) — DONE

### 7.1 `PointCloudStore` doc comment (§6.1)

**Decision**: correct the comment to state the real calling contract (`AppViewModel` coroutine,
not GL thread), then separately audit whether the internal synchronization primitive is actually
adequate for that real contract — treat that audit as its own follow-up, since it might surface
a second finding depending on what it finds.

**Implemented**: corrected the doc comment — `push` is called from whichever background
coroutine/thread a producer runs on (`FusedDepthSource`, `StructuredLightDepthSource`); `snapshot`
is called from *both* the GL thread (`DepthCloudRenderer.onDrawFrame`) *and* `AppViewModel`'s
hand-pipeline coroutine (fused-depth TSDF snapshot at `AppViewModel.kt`). Audit result: all three
public methods (`push`/`snapshot`/`clear`) are already `@Synchronized`, which serialises every
caller regardless of which thread it runs on — this already covers the real (wider) contract
correctly. No second finding; the gap was the comment, not the synchronization.

## Blocked — need your decision before these enter any phase

These four are real, working-or-nearly-working features, not bugs, so there's no "correct"
technical fix without knowing what's actually wanted:

- **§10.1 `ensureFullBodyCollector`** — full-body-context gesture classification. Wire it up
  (redirecting to `SpatialFrameProducer`'s own body-retarget output, not a fresh `.retarget()`
  call) or retire it deliberately?
- **§10.2 `renderer.scanCloudPoints`** — live point-cloud preview toggle. Feed it from
  `store.snapshot()` or retire the toggle?
- **§10.4 `FreeformPanel`** — references a `WorkflowMode` value that no longer exists. Rebuild
  under the current mode design, or retire along with its stale doc comment?
- **§10.5 `rPPGSource.snsProxy`** — a designed SNS/stress-arousal metric with no consumer. Wire
  it into OSC/HUD output somewhere, or retire it and the doc section describing it?
- **§10.7 Five working `AppViewModel` methods have no UI trigger** (`toggleCloud`, `toggleDepth`,
  `toggleLiveMesh`, and — after this pass — `toggleRoomMap`/`clearRoomMap`/`exportRoomMap`;
  `switchCamera`/`toggleTorch` are gesture-reachable but have no visible button either). Add UI
  entry points (settings row, debug menu, more gestures), or are some of these intentionally
  code/gesture-only? Not a bug — the backend logic all works — but picking where they surface
  is a design decision, not something to guess at blind.

Also effectively blocked, lower priority, deferred rather than urgent:
- §8.1 (BVH occlusion hard-snap → hold-at-last-known instead), §8.3 (OSC quaternion
  normalization), §9 (Compose recomposition memoization) — real, but lower severity than
  everything above; worth doing, not worth blocking the higher-impact phases on.
- §6.2 (`WhiteScreenOverlay`) — same shape as the blocked items above (needs a "was a one-shot
  flash actually wanted" answer), just lower stakes.

**Correction**: this list previously also included §5.1/§5.4/§5.5 as "deferred, speculative
without on-device data." That was superseded by Phase 8 item 1 below, which re-read each
finding's own recommended fix directly from `ENGINE_ARCHITECTURE.md` rather than from memory and
found the doc already specified a small, low-risk fix (timestamp-tag + expose age / time-bound
the calibration comparison) rather than something requiring on-device confirmation first — so
these were implemented in this pass, not deferred. See Phase 8 item 1's own text for what was
actually done and why it wasn't speculative after all.

## Phase 8 — Deeper architectural change (bigger than bug-fixing, addresses root shape not symptoms)

These don't fix a specific finding from `ENGINE_ARCHITECTURE.md` — they change the shape that
produced most of those findings, so the same class of bug is structurally harder to reintroduce
later. Bigger, more invasive, sequenced after Phases 0-7 land and are verified green.

1. **Timestamped/correlated message passing instead of shared mutable fields for cross-cadence
   data** — DONE, scope corrected during implementation against each finding's own recommended
   fix (re-read directly from `ENGINE_ARCHITECTURE.md` rather than from memory):
   - §5.2 (SlamLite→DA2) explicitly recommended *parameter passing* over a general
     timestamp/`Flow.combine` correlator, and that's what Phase 6 already did — no change needed.
   - §5.3 (camera-frame dual consumers) explicitly recommended *collapsing to one collector*,
     which item 3 above already did (the `onCameraFrame` callback) — no separate work needed.
   - §5.1/§5.4/§5.5 explicitly recommended fix (a) in each case — timestamp-tag the value,
     check/expose age, **not** a `Flow.combine` correlator — with (a) each described as "low
     risk," "small, contained" and the escalation to a bigger mechanism explicitly gated on
     on-device data showing the gap matters, which isn't available in this environment. A
     general `Flow.combine`-based correlator would also be *actively wrong* for §5.1's fields:
     arbiter weights/metricMode/rppg are written at raw-camera-frame rate and read at the
     *slower*, throttled hand-inference rate, so they're already fresher than the reader
     needs — correlating them would mean *slowing hand-tracking down* to match the depth
     pipeline, a real perf regression for a value that was never actually stale.

   Implemented exactly what each finding recommended: `FusedDepthSource.arbiterWeightsTimestampMs`
   / `.lastArcoreCallbackMs` and `rPPGSource.lastFrameMs` are now exposed, and
   `SpatialFrame` carries `fusionWeightsAgeMs`/`metricModeAgeMs`/`rppgAgeMs` computed at assembly
   time (§5.1 — observability only, no discarding). `FusedDepthSource.updateScaleCalibration()`
   and `.feedFarPlaneAnchor()` both skip their calibration update when the ARCore callback
   they're comparing against is more than `MAX_ARCORE_CALLBACK_GAP_MS` (500ms) old, instead of
   comparing against a wall-clock-unbounded "previous" reading (§5.4/§5.5 — real behavior change,
   but small and contained exactly as those findings described).
2. **A sealed-class state machine for the scan lifecycle** — DONE. Replaced
   `scanActive`/`freeformActive`/`isScanActive`/`isFreeformActive` (the exact shape that caused
   §10.3's posed/freeform asymmetry) with `ScanLifecycle` (`Idle`/`Posed`/`Freeform`,
   `feature/scan/ScanState.kt`) and one `AppViewModel.setScanLifecycle()` transition function that
   sets all four from one value. `depthMode` stayed a separate flag — it's a genuinely orthogonal,
   independently user-toggleable feature (`toggleDepth()` works outside an active scan too), not
   a sub-state of scan mode; folding it into the sealed class would have been a false economy.
   Building this surfaced a second real desync bug beyond §10.3: the freeform `FAILED` watcher
   only reset 2 of the 4 flags, leaving the router still thinking a freeform scan was active
   after it had already failed — now fixed for free by routing through the same transition
   function.
3. **Narrow `AppViewModel`'s visibility to raw tracking streams — partially done, rest
   descoped with a documented reason.** Investigating this before touching anything found the
   plan's original framing didn't match reality in two ways:
   - `AppViewModel`'s `handPipeline.processed` collector isn't a duplicate to eliminate — it's
     the deliberate single entry point that calls `producer.assembleFrame()`; the producer
     never subscribes to `handPipeline` itself.
   - `CameraFrameProvider.frames` is a `SharedFlow`, designed for multiple independent
     collectors. `AppViewModel`'s second collector (feeding the GL background texture) was
     legitimate multicast use, not the §5.3 hazard it looked like — §4.4 (the actual
     double-writer bug this item cites) was already fixed in Phase 3.

   **Done**: `frameProvider` is now `private` in `SpatialFrameProducer`. `AppViewModel` no
   longer holds a reference to it at all — camera frames reach `renderer.submitCameraFrame`
   via a new `producer.onCameraFrame` callback, and `CameraController` (the one legitimate
   external need for the raw provider, to feed it frames from hardware) is now constructed by
   `producer.createCameraController(context)` instead of `AppViewModel` reaching into
   `producer.frameProvider` to build it externally. This is real, compiler-enforced narrowing:
   nothing outside the producer can add a second collector to that flow anymore.

   **Descoped**: full `handPipeline`/`bodyPipeline` narrowing. Both are constructed by
   `AppViewModel` itself (not owned by the producer) and read directly in at least 3-4 distinct,
   legitimate places beyond the producer — scan quality gating, raw skeleton-overlay rendering,
   and the dormant `ensureFullBodyCollector`, at minimum. Real enforcement would mean moving
   their *construction* into the producer and exposing only derived outputs everywhere — which
   runs into live, frequently-exercised scan/tracking code, not just dormant paths. In practice
   this depends on item 5's decomposition existing first: `AppViewModel` reads these streams in
   so many places precisely because it's still one large coordinator; there isn't yet a smaller
   destination class to hand narrowed access to. Attempting it now would be a large, separately
   risky change for a bug class (§4.4) already fixed elsewhere — not something to bundle into
   this pass.
4. **Make retargeters pure** — DONE, scope corrected during implementation. `BoneRetargeter`
   turned out to already be pure (no internal mutable fields at all — verified by reading it;
   its `bindPose` is a read-only constructor `val`), so only `BodyRetargeter` needed the change.
   `BodyRetargeter` is now an `object` (no reason to instantiate a genuinely stateless type);
   `retarget()` takes a `BodyRetargeterState` (grace-period + EMA history) explicitly and returns
   `(newState, result)` instead of mutating three internal `HashMap` fields. This was not a
   hypothetical risk: `AppViewModel` has *two* call sites retargeting from `bodyPipeline`'s output
   through the same `BodyRetargeter` — the live `SpatialFrameProducer` pipeline and the dormant
   `ensureFullBodyCollector` (one of the blocked §10.1 items). Before this, activating that
   blocked feature would have silently corrupted the live pipeline's grace-period/EMA state the
   moment both ran. Each caller now holds its own `BodyRetargeterState` field, so that's no
   longer possible regardless of what's decided about §10.1.
5. **Decompose `AppViewModel` and `FusedDepthSource`** into smaller, single-responsibility
   coordinators once 1-4 reduce how much cross-cutting state they need to hold directly —
   **DONE, scoped to one bounded extraction.** A full decomposition of either class is a
   much larger undertaking than 1-4 combined, and this environment has no way to verify runtime
   behavior beyond CI compilation (no device). Extracted `ScanCoordinator`
   (`feature/scan/ScanCoordinator.kt`) — posed/freeform scan lifecycle orchestration
   (`setScanLifecycle`, `startScan`/`cancelScan`/`startFreeformScan`/`finishFreeformScan`/
   `cancelFreeformScan`, `processScan`/`processFreeformScan`, the scanner/freeform-scanner status
   watchers, incremental neural-recon training, `restJointPositions`) out of `AppViewModel`,
   which previously held all of it directly. Collaborators (`uiState`, `scanState`, `scanner`,
   `router`, `spatialLayer`, etc.) are passed by reference, not re-owned — the same `MutableStateFlow`
   instances `AppViewModel` and the UI layer already share, so this changes *where the logic
   lives*, not the external API: `AppViewModel` keeps a one-line forwarding method per public
   function (`startScan()`, `cancelScan()`, etc.), so `MainActivity`'s existing call sites are
   unchanged. `toggleLiveMesh()`/`stopRecordingAndExport()` now read
   `scanCoordinator.restJointPositions` instead of a field `AppViewModel` held directly.
   Explicitly **not done**: `toggleDepth()`/`recalibrateOef()` stay in `AppViewModel` (genuinely
   separate features that happen to touch the same `scanState`/`router`, not part of the
   lifecycle this extraction owns), the per-hand-frame fused-depth/TSDF integration block stays
   inline (tightly coupled to that collector's locals, not a standalone callable unit), and
   `FusedDepthSource` itself is not decomposed — a second, separately-scoped extraction, not
   attempted in this pass. CI-verified green (commit `9fe6be3`, workflow run `29113742152`)
   after fixing two compile errors surfaced by the initial extraction commit: a missing
   `kotlinx.coroutines.flow.update` import in `ScanCoordinator.kt`, and an initialization-order
   bug in `AppViewModel.kt` (the old scan-watcher `init {}` block called `scanCoordinator.start()`
   textually before the `scanCoordinator` property's own declaration further down the file —
   Kotlin runs property initializers/init blocks in strict textual order, so this must be a
   separate `init {}` block placed after the declaration, not the original call site).

Sequenced last because 1-4 are genuine redesigns of working code, higher risk, and only worth
doing once the concrete bugs in Phases 0-7 are fixed and confirmed — redesigning underneath
unfixed bugs makes them harder to isolate, not easier.

## Phase 9 — Reported on-device symptoms, plus a repo-wide cleanup pass — mostly DONE, one open item (6)

Triggered by direct user reports after Phase 8 shipped, not by a pre-existing
`ENGINE_ARCHITECTURE.md` finding — each fix added its own §4.7-4.9 entry there afterward, same
"diagnose in the architecture doc, execute here" split as every earlier phase.

1. **Unthrottled Core-layer depth channels (§4.7)** — SLAM/DA2 ran unconditionally on every
   camera frame, several lines before `FrameThrottler`'s existing gate for MediaPipe tracking.
   Added `util/DepthChannelBudget.kt` (same shed/recover EMA shape as `ModelBudgetManager`,
   generalised to named channels) and gated both calls in `SpatialFrameProducer.processBitmap`.
2. **Camera stream capped at 30fps (§4.8)** — `ImageAnalysis` had no capture-request tuning at
   all. Added `CameraController.applyHighestFpsRange()`, querying the camera's own advertised
   AE target FPS ranges via `Camera2Interop` rather than hardcoding a rate a device might reject.
3. **Rear-camera switch froze the screen (§4.9)** — ARCore's `Session` holds its own independent
   Camera2 handle to the rear camera, always-on; `CameraController.switchCamera()` binding onto
   the same physical camera hung waiting for ARCore to release it. Added
   `ArCoreDepthSource.pauseCameraHold()`/`resumeCameraHold()`, called around the CameraX rebind
   in `AppViewModel.switchCamera()`.
4. **Repo-wide dead-code/consistency audit, requested directly** — verified independently (not
   just trusted agent output — see §3.2's standing rule) rather than acted on blind:
   - Deleted `camera/TorchController.kt` and `depth/ArDepthSession.kt` — both genuinely dead
     (empty deprecated stub; fully-superseded old class per its own successor's docstring),
     confirmed via git history (both present since the initial zip extraction, untouched since)
     and zero real callers anywhere in the repo.
   - `depth/VoxelGrid.kt` and `export/PointCloudExporter.kt` looked dead by the same test but
     turned out to be a real, different, unwired feature — see ENGINE_ARCHITECTURE.md §10.6 for
     why, and §10.7 for a related discovery (several other working `AppViewModel` methods have
     no UI trigger either) that's flagged as a product decision, not silently fixed.
   - `ENGINE_ARCHITECTURE.md` §4.1-4.6, §5.1-5.6, §6.1, §7.1-7.2 were fixed in earlier phases of
     this same plan but never marked resolved in the original findings document — it was actively
     misdescribing fixed code as still-broken. Backfilled "— DONE (Phase X)" on every header that
     phase mapping in this document confirms was actually completed; left §6.2/§8.x/§9/§10.x
     untouched since those remain genuinely not done or blocked on a product decision.
   - This document's own Phase 0/1/3/4 headers were missing their "— DONE" markers despite being
     completed (Phase 2/5/6/7 had them) — same class of gap, fixed here. Also removed a direct
     self-contradiction: the "Blocked" section still listed §5.1/§5.4/§5.5 as deferred/speculative
     after Phase 8 item 1 (above) had already implemented them.
   - Minor style fixes: `import com.arhand.tracking.landmarkToWorld` placed out of alphabetical
     order (after `com.arhand.util.Vec3`) in 6 files — a fossil from an earlier cross-module
     refactor, corrected. `AppViewModel.kt` had no class-level KDoc for the app's central
     ViewModel — added one.
5. **`landmarkToWorld`'s `camAspect` silently wrong at 8 of 9 call sites (§4.10)** — triggered by
   a direct user report of "atrociously inaccurate" scans/tracking. `Scanner.kt`'s posed-scan
   point capture always took the buggy fallback path (its interpolated points have no MediaPipe
   world coordinates to prefer), distorting every posed scan's captured geometry, not a rare
   edge case. Threaded a real `camAspect` through `Scanner`/`HandSegmentationMask`/`DepthCarver`/
   `HandBiometrics`/`ScanPipeline`'s rest-joint computation — see §4.10 for the full breakdown of
   which call sites were actively wrong versus latent-but-currently-silent.
6. **Root cause CONFIRMED, correct fix scoped but NOT attempted: hand-landmark world coordinates
   vs. ARCore/SfM depth-cloud world coordinates are different coordinate frames (§4.11)** — found
   while fixing item 5, investigated further on direct request. Confirmed via `HandTracker.kt`'s
   own comment ("origin at hand geometric center") that MediaPipe world landmarks discard the
   hand's actual position relative to camera/room — they encode shape only. A pose-composition
   fix (compose ARCore's camera pose onto the hand landmarks) **cannot work**: there is no
   translation information left in hand-centred landmarks to compose a room position from — it
   was already thrown away. Confirmed by contrast with `BoneRetargeter`'s correct usage of the
   same landmarks (relative direction vectors only, origin-invariant) — `HandSegmentationMask` is
   the only place that (incorrectly) treats these landmarks' absolute position as meaningful.
   A correct fix requires going back to real per-landmark camera-space depth (SL/DA2) and
   unprojecting via camera intrinsics before composing with ARCore's pose — a materially larger
   piece of work with its own conditional dependencies (SL calibration, DA2 XR warm-up), scoped
   as its own follow-up rather than attempted blind here. Severity could be more than "imprecise"
   (possibly near-total masking failure for `depthMode` scans specifically) but isn't
   determinable without a device — see §4.11 for the full derivation.
7. **`BitmapGrayscaleShim`'s single-listener slot silently disconnected SfM after the first scan
   of every session (§4.12)** — triggered by being asked to dig deeper into whether the
   architecture itself, not individual bugs, was the limiting factor. Found a real structural
   flaw: the shim was built for what looked like one consumer but actually serves two (SfM
   always-on, Photometric scan-scoped) with a single `setListener` slot, so starting Photometric
   for the first scan silently clobbered SfM's registration, and ending that scan cleared the
   slot entirely with nothing ever re-registering SfM afterward. Converted the shim to support
   multiple listeners (`addListener`/`removeListener`); both sources now store the exact listener
   instance they registered so `stop()` only removes their own. This is the shape of issue the
   architecture question was actually asking about — not a missing throttle or a wrong default,
   but a shared-resource design that silently failed under its own real (two-consumer) usage
   pattern.
8. **BVH hard identity-pose snap on occlusion grace expiry (§8.1)** — held at last-known
   rotation indefinitely instead of dropping the joint from the result once grace expires, so
   BVH export no longer double-snaps (freeze → identity teleport → real-rotation teleport).
9. **OSC-receive quaternion validation (§8.3)** — added `OscReceiver.sanitizeQuaternion()`,
   rejecting non-finite components and normalizing otherwise, at both quaternion parse sites.
   The separate VMC address-naming question in the same finding needs external protocol
   verification and stays open, per that finding's own caution.
10. **HUD-derivation recomposition (§9, partial)** — wrapped `HandyApp`'s three `HudOverlay`
    derivations in `derivedStateOf`. Honest caveat: these are O(≤2) computations Compose was
    likely already skipping recomposition for via stable-parameter equality — applied per the
    finding's own recommendation, but its practical impact here is probably small. The larger
    part of §9 (the ~1000-line composable body re-executing in full, `ModelViewerScreen`'s
    viewer-scoped recomposition) is a bigger restructuring not attempted — too much regression
    risk with no way to visually verify the result from this environment.

## Recommended order

Phase 0 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8, then the blocked items once you've answered the four
(now five, with §10.7) product questions, then the deferred/lower-priority list opportunistically.
Phases 0-4 are all independent of each other technically and could be reordered or parallelized;
the sequence above is by impact (fix what's visibly broken first), not by dependency. Phase 9 was
reactive (direct user reports plus a requested cleanup pass) rather than part of the original
sequence, and is now also done.
