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
else has a concrete recommended design below, not just options.

## Phase 0 — Safety net before touching anything

§13 already recommends this: add regression tests for the two confirmed, highest-visibility live
bugs *before* fixing them, so the fix is provably correct and can't silently regress later, given
§13's other finding that nothing in the current suite would catch it either way.

- Test that `TextureBaker`'s and `GLBExporter`'s UV projections agree for a mesh *not* centered
  at the origin (the exact condition §4.1 breaks under).
- Test that completing a scan, then cancelling/failing a second one, does not re-trigger the
  result modal (§4.2).

## Phase 1 — The two confirmed live bugs users would actually notice

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

## Phase 3 — Delete the remaining duplicate-writer patterns

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

## Phase 4 — Cross-thread visibility fixes (mechanical, low-risk, bundle as one pass)

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

Also effectively blocked, lower priority, deferred rather than urgent:
- §5.1, §5.4, §5.5 (cross-cadence staleness in Core calibration paths) — add timestamp checks
  only if on-device testing ever shows these cause a visible problem; speculative otherwise.
- §8.1 (BVH occlusion hard-snap → hold-at-last-known instead), §8.3 (OSC quaternion
  normalization), §9 (Compose recomposition memoization) — real, but lower severity than
  everything above; worth doing, not worth blocking the higher-impact phases on.
- §6.2 (`WhiteScreenOverlay`) — same shape as the blocked items above (needs a "was a one-shot
  flash actually wanted" answer), just lower stakes.

## Phase 8 — Deeper architectural change (bigger than bug-fixing, addresses root shape not symptoms)

These don't fix a specific finding from `ENGINE_ARCHITECTURE.md` — they change the shape that
produced most of those findings, so the same class of bug is structurally harder to reintroduce
later. Bigger, more invasive, sequenced after Phases 0-7 land and are verified green.

1. **Timestamped/correlated message passing instead of shared mutable fields for cross-cadence
   data.** Directly replaces §5.2's SlamLite→DA2 fix (which just makes that one pair correct) with
   a general mechanism: tag Core-layer values with the timestamp/frame-id they were computed for,
   and use `Flow.combine`/a custom correlator to only pair values whose timestamps actually match,
   instead of reading "whatever's freshest right now." Fixes §5.1/§5.4/§5.5's staleness gaps as a
   side effect of the same mechanism, rather than three separate timestamp-check patches.
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
   coordinators once 1-4 reduce how much cross-cutting state they need to hold directly.

Sequenced last because 1-4 are genuine redesigns of working code, higher risk, and only worth
doing once the concrete bugs in Phases 0-7 are fixed and confirmed — redesigning underneath
unfixed bugs makes them harder to isolate, not easier.

## Recommended order

Phase 0 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8, then the blocked items once you've answered the four
product questions, then the deferred/lower-priority list opportunistically. Phases 0-4 are all
independent of each other technically and could be reordered or parallelized; the sequence above
is by impact (fix what's visibly broken first), not by dependency.
