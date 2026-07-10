# Engine reliability spec: Core / Translation / Application

## 0. Why this document exists, and what makes it different from a design note

Every serious bug found this session shared one root shape: **a shared, stateful value with
more than one writer**, usually because a migration to a new owner was started but the old
writer was never removed, and a comment was left behind asserting the migration was complete
when it wasn't. Concretely:

- `AppViewModel`'s hand-retarget block had a comment reading *"All retargeting ... now happen
  inside producer/router. AppViewModel is a coordinator only"* directly above ~150 lines that
  still recomputed the entire retarget a second time (fixed this session).
- `BoneRetargeter`'s doc comment claims *"rebuilt whenever loadedAsset changes"* — true for
  `SpatialFrameProducer`'s copy, never true for `AppViewModel`'s (still open, see §4).
- The body-retarget case in §4.1 below — found while writing this document, not previously
  fixed — is the same shape again: two call sites invoking `.retarget()` on one shared,
  stateful `BodyRetargeter` instance, discovered by checking whether the object passed to two
  places was actually the same object, not by reading either call site in isolation.

A document that only describes "current architecture" doesn't stop this pattern; the stale
comments above were also "current architecture" descriptions at the time they were written.
What stops it is naming the rule precisely enough to check against, and keeping a live list of
where the codebase currently violates it. That's what §3–§5 are for. This document is wrong the
moment it stops matching the code — treat any mismatch found later as a bug in this file, to be
corrected in the same commit that touches the code, not left to drift.

## 1. Layer model (verified against source)

Module dependency graph — from `settings.gradle`'s module list and each module's
`build.gradle` `implementation project(...)` declarations, read directly, not inferred:

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
| **Core** | `camera`, `depth` | Camera sensor → depth/spatial signal (point cloud, per-block confidence). No semantic labels. |
| **Translation** | `tracking`, `mocap`, `scanner` | Camera frame → semantic keypoints (MediaPipe) → structured motion (`BoneRetargeter`/`BodyRetargeter`: keypoints → joint quaternions) → structured geometry (`Scanner`/TSDF: keypoints + depth → 3D mesh) |
| **Application** | `render`, `export`, `app` | Structured motion/geometry → OpenGL draw calls, OSC packets, BVH files, UI state |

Verified exception: `depth` imports `tracking.HandLandmarks` in exactly two files
(`DepthCarver.kt:215`, `HandSegmentationMask.kt:33,49`) — both are pure coordinate-math
functions taking a landmark struct as an input parameter (a **data-type** dependency), not
invoking or depending on the tracking pipeline running (a **behavioral** dependency). Not a
violation of the layering; not worth restructuring.

## 2. The rule

> **Every piece of state that is read from more than one place has exactly one place that
> writes it. Every stateful per-frame update function (anything holding an EMA, a counter, a
> "last good value," a grace period) is called from exactly one call site.**

Anything that violates this is either (a) a live bug — two writers disagreeing or racing — or
(b) heading toward one the next time someone touches either call site without knowing the
other exists. §4 is the current, checked inventory against this rule. §6 is how to check it.

## 3. Concurrency contract

| Work category | Dispatcher | Rationale |
|---|---|---|
| Camera frame acquisition, YUV/RGBA conversion | CameraX's own analyzer executor | Owned by CameraX, not app code |
| MediaPipe hand/body/face inference | GPU delegate (falls back to CPU) — verified in `HandTracker.kt`, `BodyPipeline.kt`, `FacePipeline.kt`, all try `Delegate.GPU` then `Delegate.CPU` with the same try/catch shape | Google-owned, already correct |
| Core depth-fusion channels (DA2, SLAM, stereo, RS-stereo, PSP) | `Dispatchers.Default` (shared pool) | Long, non-suspending CPU-bound work — appropriate for a batch pool, but see §4.3 for what this pool must never share with |
| Frame routing to renderer/OSC/motion-capture (`SpatialFrameRouter`) | Dedicated single-thread dispatcher (fixed this session — was `Dispatchers.Default`, contended with DA2/SLAM) | Latency-sensitive: its staleness is directly visible as puppet lag |

**Rule**: any coroutine whose staleness would be visible to the user (retarget result, render
state) must not share a dispatcher with unbounded CPU-bound batch work (CNN inference, optical
flow, block-matching search). This is the exact rule `SpatialFrameRouter`'s dedicated-thread
fix (this session) implements — apply it to any new latency-sensitive consumer, don't default
back to `Dispatchers.Default` for convenience.

## 4. Ownership registry — current state, checked against §2

| State / stateful function | Legitimate owner | Status |
|---|---|---|
| Hand retarget result (`latestRetargetResult`, `renderer.latestRetargetResult`) | `SpatialFrameProducer` → `SpatialFrameRouter` | **Fixed this session.** Was written by both `SpatialFrameRouter.route()` and a duplicate computation inline in `AppViewModel`; duplicate removed. |
| `reconstructionActive` flag | `FusedDepthSource` | **Correct.** Single owner, toggled from `AppViewModel` via `SpatialLayer.setReconstructionActive()`, read only inside `FusedDepthSource`. |
| `scanActive` flag (Core-layer CLAHE gating) | `AppUiState.scanActive` (single source), mirrored into `SpatialFrameProducer.scanActive` by one `viewModelScope.launch { uiState.collect { ... } } ` | **Correct pattern to copy.** One reactive mirror, not multiple manual sync points — this is the shape to use instead of threading a flag through every call site, the mistake the two items above had to be dug out of. |
| `BodyRetargeter` instance (`emaPrev`, `lastGoodRotation`, `graceRemaining`) | Ambiguous — see §4.1 | **Open. Found while writing this document, not yet fixed.** |
| `BoneRetargeter` bind-pose rebuild on asset load | `SpatialFrameProducer.onAssetLoaded()` | **Open, low severity.** `AppViewModel`'s own `boneRetargeter` field (kept this session for the OSC-velocity/live-mesh path) is never rebuilt on `loadAsset()` — stays on `DEFAULT_PUPPET.bindPose` even after a custom asset loads. Only affects that one minor path (OSC angular velocity, live-mesh preview), not the canonical retarget. |

### 4.1 Open violation: `BodyRetargeter` is shared, stateful, and called from two collectors

Verified, not inferred:

- `AppViewModel.kt:163-174` constructs `SpatialFrameProducer` passing `bodyRetargeter = bodyRetargeter`
  — i.e. `AppViewModel`'s own `val bodyRetargeter = BodyRetargeter()` (line 139) is the *same
  object* injected into the producer. Not two instances — one shared instance, two owners
  believing they're allowed to call it.
- `BodyRetargeter.kt:168,171,182` — `lastGoodRotation`, `graceRemaining`, `emaPrev` are mutable
  instance state, written inside `retarget()` (confirmed by reading the method body, not just
  its signature).
- Two independent `bodyPipeline.processed.collect { ... }` blocks both call
  `bodyRetargeter.retarget(lms)` on this same instance: `SpatialFrameProducer.kt:147-156`
  (`init()`) and `AppViewModel.ensureFullBodyCollector()`.

Effect: every time `bodyPipeline.processed` emits a new pose, **both** collectors fire and
**both** call `.retarget()` with the same landmarks, stepping the EMA and grace-period counters
twice per actual new frame instead of once. This isn't wasted CPU (the hand-retarget bug's
main cost) — it's active corruption of the smoothing/grace-period state, on every frame body
tracking is enabled. Likely contributor to body-skeleton jitter/inaccuracy independent of
anything already fixed this session.

**Not fixed as part of writing this document** — flagged per the user's explicit instruction
that this pass is planning only, no code changes. Fix shape (for a future session): same as
the hand-retarget fix — pick one owner (`SpatialFrameProducer`, since it already feeds
`frame.body` through the router to every consumer), delete `AppViewModel`'s
`ensureFullBodyCollector()`'s call to `.retarget()`, have it read
`latestFullBodyRetargetResult.value?.body` instead of recomputing.

## 5. Degradation contract (verified per source, not per source's own claims)

Every depth/tracking source in this codebase already follows the same shape: an `isAvailable`
(or equivalent) flag, set `false` in a `catch` block, checked by every consumer before use.
Verified present in: `StereoDepthSource.kt` (4 sites), `DepthAnythingSource.kt`,
`ArCoreDepthSource` (checked via `FusedDepthSource.kt:373`, falls back to
`"ARCore unavailable — SfM+Photo only"`), and MediaPipe's GPU→CPU delegate fallback (§3). This
is a consistent, load-bearing pattern — any new Core/Translation source should follow it
rather than assume hardware/model availability.

## 6. Verification & enforcement

No static analysis tool enforces §2's rule automatically in this environment. Cheap, concrete
checks anyone (human or agent) can run before trusting a change:

1. **Instance-count check**: `grep -rn "BoneRetargeter(\|BodyRetargeter(" --include=*.kt` and
   confirm the count of construction sites matches the count of intended owners (currently:
   `mocap/BoneRetargeter.kt` class def + 2 real constructions in `SpatialFrameProducer` +
   1 in `AppViewModel` [OSC-velocity path, intentional] + 1 in a test file = expected baseline;
   `BodyRetargeter` should be exactly 1 construction site once §4.1 is fixed, currently is).
2. **Shared-instance check**: when a constructor parameter and a field have the same name
   (`bodyRetargeter = bodyRetargeter`), confirm deliberately whether that's intentional sharing
   (fine) or an accidental alias masking that two supposedly-independent computations are
   actually mutating one object (the §4.1 bug) — the name match alone doesn't tell you which.
3. **Dispatcher audit**: any new `scope.launch(Dispatchers.Default) { ... }` that updates a
   renderer/UI-visible field should be checked against §3's rule before merging.
4. **CI is compile-only**: the `Build APK` GitHub Actions workflow on
   `claude/zip-extraction-nn9nko` proves Kotlin compiles, nothing about runtime correctness or
   performance. Every perf/correctness claim in this document was verified by reading the
   actual method bodies (not signatures or comments) and, where relevant, by CI-green code
   changes — never by assumption. Runtime behavior (does §4.1 actually cause visible jitter,
   does a throttle change actually reduce lag) can only be verified by the user testing on a
   real device and reporting back.

## 7. Explicitly out of scope

- Device-specific tuning (NNAPI/GPU-delegate speculation, resolution/quality downgrades). The
  NNAPI attempt this session regressed both performance and accuracy and was reverted —
  nothing hardware-driver-dependent belongs here until there's a way to test it on a device.
- Module restructuring. Layer boundaries already match modules (§1); this document disciplines
  ownership and scheduling, it does not propose moving files.
