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

## Phase 2 — Consolidate scan-capture ownership (resolves §4.3 and §10.3 together)

**Decision**: `SpatialFrameRouter` becomes the single owner of frame capture for both posed and
freeform scans.

1. Wire `router.isScanActive = true` inside `startScan()` (posed scans currently never touch
   this flag at all).
2. Verify the router's posed-scan capture branch (`SpatialFrameRouter.kt:213-223`) — dormant
   since it's never run today — actually produces equivalent output to `AppViewModel`'s current
   inline capture, before removing the inline path. This is the one step in this whole plan that
   needs real verification, not just a diff: that branch has never executed in production.
3. Once verified, delete `AppViewModel`'s inline capture for *both* posed
   (`AppViewModel.kt:475-480`-equivalent) and freeform (`AppViewModel.kt:525-533`) scans, and
   delete the freeform inline call specifically, since it's the direct cause of §4.3's
   double-invocation.

This is the highest-value single workstream in the plan: it resolves a live scan-quality bug
(§4.3) and an architectural inconsistency (§10.3) with one change, and it's the same "make the
router canonical, delete the legacy inline path" move already proven safe once this session (the
hand-retarget consolidation).

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

## Phase 5 — GL resource lifecycle (§7.1, §7.2)

**Decision**: 
1. Add a `release()` method (following the existing pattern already used by
   `DepthMeshRenderer`/`SkinnedMeshRenderer`/etc.) to the four renderers missing one
   (`HandRenderer`, `BodySkeletonRenderer`, `FaceSkeletonRenderer`, `DepthCloudRenderer`
   component mode).
2. Wire `ARRenderer` to call `release()` on every sub-renderer at the point its own GL context
   is torn down.
3. Add the same re-entrancy guard `DepthCloudRenderer` already has (`if (program != 0) return`)
   to the other 8 `init()` methods.
4. Add a `DisposableEffect` to `ModelViewerScreen` that calls its renderer's `release()` on
   dispose — this is the one concretely reachable leak path identified (repeated navigation to
   that screen within one process lifetime, no guaranteed EGL context loss between visits).

## Phase 6 — The other Core-layer correctness fix

### 6.1 `SlamLite` → DA2 flow correlation (§5.2)

**Decision**: pass the flow values as parameters directly into DA2's inference call at the
moment SLAM computes them for that exact bitmap (bundle bitmap + flow into one small data class
enqueued together), rather than DA2 reading whichever flow value happens to be freshest whenever
it gets around to running. This is the one timing/correlation finding worth fixing proactively
rather than just instrumenting — it's Core-internal, self-contained, and directly affects
hand-landmark Z-correction accuracy, unlike §5.1/§5.4/§5.5 which are lower-confidence and
touch ARCore/SfM calibration paths that are harder to verify without a device.

## Phase 7 — Documentation/contract corrections (no behavior change)

### 7.1 `PointCloudStore` doc comment (§6.1)

**Decision**: correct the comment to state the real calling contract (`AppViewModel` coroutine,
not GL thread), then separately audit whether the internal synchronization primitive is actually
adequate for that real contract — treat that audit as its own follow-up, since it might surface
a second finding depending on what it finds.

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

## Recommended order

Phase 0 → 1 → 2 → 3 → 4 → 5 → 6 → 7, then the blocked items once you've answered the four
product questions, then the deferred/lower-priority list opportunistically. Phases 0-4 are all
independent of each other technically and could be reordered or parallelized; the sequence above
is by impact (fix what's visibly broken first), not by dependency.
