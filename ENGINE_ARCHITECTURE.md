# Engine architecture spec: Core / Translation / Application budget discipline

## 1. Problem statement

Depth-engine work (`SpatialFrameProducer.processBitmap()`) runs unconditionally at full
camera frame rate with zero rate control. MediaPipe tracking work (same function, same call
site, a few lines below) already has two layers of adaptive rate control. This is the single
largest architectural asymmetry in the app and the most likely explanation for CPU-bound
lag reported on-device. This spec defines the layer boundaries precisely, cites the exact
code responsible, and proposes one scoped fix: bring the depth engine's rate control up to
parity with tracking's, using tracking's own existing mechanism as the template.

## 2. Layer definitions (verified against source, not inferred)

Module dependency graph, from `settings.gradle` comment and each module's `build.gradle`
`implementation project(...)` declarations (all four checked directly):

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
| **Core** | `camera`, `depth` | Camera sensor → depth/spatial signal (point cloud, per-block confidence). No semantic labels (no "hand," no "joint"). |
| **Translation** | `tracking`, `mocap`, `scanner` | Camera frame → semantic keypoints (MediaPipe: 21 hand / 33 pose / 478 face landmarks) → structured motion (`BoneRetargeter`/`BodyRetargeter`: keypoints → joint quaternions) → structured geometry (`Scanner`/`FreeformScanner`/TSDF: keypoints + depth → 3D mesh) |
| **Application** | `render`, `export`, `app` | Structured motion/geometry → OpenGL draw calls, OSC packets, BVH files, UI state |

### 2.1 Verified exception: `depth` imports `tracking.HandLandmarks`

Grep-verified (not inferred): exactly two files in `depth` import tracking types —

- `depth/DepthCarver.kt:215` — `fun landmarksToWorld(lms: HandLandmarks, aspect: Float, mirrorX: Boolean): List<Vec3>`
- `depth/HandSegmentationMask.kt:33,49` — `fun buildHull(lms: HandLandmarks, ...): List<Pair<Float,Float>>`, `fun filterPointCloud(points: List<Vec3>, hull: ...): List<Vec3>`

All three are pure coordinate-math functions that take `HandLandmarks` as an input parameter
to convert/mask a point cloud — they do not invoke MediaPipe, do not run tracking, and hold
no tracking state. This is a **data-type dependency** (Core needs the landmark struct's shape
to do coordinate math), not a **behavioral dependency** (Core does not depend on Translation's
pipeline running). Recommendation: leave as-is. Moving these two files to `scanner` (which
already depends on both `depth` and `tracking`) would remove the asymmetry cosmetically but
touches working scan-carving code for no functional gain — not worth the churn or risk.

## 3. Root cause: unthrottled Core, throttled Translation

### 3.1 Translation's existing rate control (already shipping, unmodified by this spec)

`app/feature/spatial/SpatialFrameProducer.kt`, inside `processBitmap(bitmap: Bitmap)`:

```kotlin
if (!frameThrottler.shouldInfer()) return      // line 210

if (scanActive) clahe.process(bitmap)          // line 217 (gated this session)
trackerMgr?.detect(bitmap, ts)                 // line 220 — hand, always submitted when inferring
val dec = modelBudget.tick(bodyActive, faceActive)   // line 225
if (dec.submitBody && bodyActive) bodyPipeline.detect(...)   // line 227
if (dec.submitFace && faceActive) facePipeline.detect(...)   // line 229
```

- `util/FrameThrottler.shouldInfer()` — bool gate; adapts `inferEvery` (1–4, or ×2 when idle)
  based on measured dispatch time (`reportInferenceMs`) and hand motion magnitude.
- `util/ModelBudgetManager.tick(bodyActive, faceActive): SubmitDecision` — per-model EMA
  (`handEma`, `bodyEma`, `faceEma`) against `FRAME_BUDGET_MS = 25f`; sheds body to
  `bodyEvery = 2` first, then face, recovers one step per 30 frames once load drops below
  `RECOVERY_RATIO = 0.70`.

### 3.2 Core's absence of rate control

Same function, **6 lines above** the throttle gate:

```kotlin
private fun processBitmap(bitmap: Bitmap) {
    latestBitmap = bitmap
    depthShim.onBitmap(bitmap, System.currentTimeMillis())              // line 193
    val slResult = if (slEnabled && !isFrontCamera) { ... } else null    // line 196-199
    spatialLayer.processBitmap(bitmap)                                  // line 202 — SLAM + rPPG
    spatialLayer.fusedDepth.processAuxSources(bitmap, slResult?.depth, scope)  // line 205

    if (!frameThrottler.shouldInfer()) return    // ← throttle starts here, AFTER Core already ran
    ...
```

`processAuxSources` (`depth/fusion/FusedDepthSource.kt:456`) unconditionally runs DA2 (CNN
forward pass) every call, plus DRASL/JBU, plus — when `reconstructionActive` — RS-stereo, PSP,
stereo capture, flare/moire detectors. `spatialLayer.processBitmap` unconditionally runs
`SlamLite.process` (Harris-corner detection + pyramidal optical flow) every call. None of these
six call sites have any per-call rate limiting; they run at whatever rate `frameProvider.frames`
emits (up to full camera FPS).

## 4. Proposed fix: `DepthChannelBudget`

New file `util/src/main/java/com/arhand/util/DepthChannelBudget.kt`, structurally identical to
`ModelBudgetManager` (same EMA/shedding/recovery mechanism, reused verbatim — not reinvented):

```kotlin
class DepthChannelBudget(
    private val budgetMs: Float = 25f,
    private val recoveryRatio: Float = 0.70f,
    private val emaAlpha: Float = 0.15f
) {
    // One entry per channel: da2, slam, rsStereo, psp, stereo, clahe (only those active this
    // build — CLAHE's own scanActive gate from this session stays; this budget governs cadence
    // only among channels that are already permitted to run).
    private val ema        = HashMap<String, Float>()
    private val everyN      = HashMap<String, Int>()
    private val counter     = HashMap<String, Int>()
    private var recoveryWindow = 0

    /** Call once per camera frame, before deciding which channels to invoke. */
    fun tick(channelIds: List<String>): Map<String, Boolean> { /* same shed/recover logic as ModelBudgetManager.tick */ }

    /** Call once per channel after it runs, with measured wall time. */
    fun report(channelId: String, ms: Float) { /* EMA update, same formula as ModelBudgetManager.reportDispatch */ }
}
```

Call-site change in `SpatialFrameProducer.processBitmap`:

```kotlin
val decision = depthBudget.tick(listOf("slam", "da2", "rsStereo", "psp", "stereo"))
if (decision["slam"] == true) spatialLayer.processBitmap(bitmap)
if (decision["da2"]  == true) spatialLayer.fusedDepth.processAuxSources(bitmap, slResult?.depth, scope)
// (RS-stereo/PSP/stereo already live inside processAuxSources, gated by reconstructionActive;
//  their own decision keys thread through as extra params or a second tick() call scoped to
//  reconstruction-only channels — exact plumbing to be finalized during implementation, not
//  specified further here since it depends on how processAuxSources is split.)
```

Each channel holds its last output between throttled frames — every channel already
exposes its result via `@Volatile var last...: FloatArray?` (`RollingShutterStereo.lastDepthBlocks`,
`DepthAnythingSource`'s internal depth buffer, etc.), so "stale-but-recent" requires no new
staleness-handling code; it's the existing contract these fields were built for.

**Explicitly no channel is disabled.** `everyN` never exceeds a small cap (e.g. 4, matching
`FrameThrottler.maxEvery`); every channel still runs periodically regardless of load.

## 5. Out of scope for this spec

- Device-specific tuning (NNAPI/GPU-delegate, resolution/quality downgrades) — the NNAPI attempt
  this session regressed perf and accuracy and was reverted; nothing hardware-driver-dependent
  belongs in this plan until there's a way to test on a device.
- Module restructuring — layer boundaries already match modules (§2); this spec adds rate
  control inside Core, it does not move files (except the already-rejected option in §2.1).
- The known, not-yet-fixed Translation-layer duplicate: `AppViewModel.ensureFullBodyCollector()`
  runs its own `bodyRetargeter.retarget(poseLms)` in parallel with `SpatialFrameProducer`'s
  internal `bodyRetargeter` computing the same thing for `frame.body`. Same class of bug as the
  hand-retarget duplicate already fixed this session; same fix shape (`AppViewModel` reads
  `latestFullBodyRetargetResult.value?.body` instead of recomputing) — tracked as a separate,
  independent follow-up, not bundled into this spec.

## 6. Implementation steps

0. Commit this document itself to the repo as `ENGINE_ARCHITECTURE.md` at the project root
   (alongside the existing `README.md`), so the layer model and the rationale in §2–§3 are a
   durable, reviewable reference — not just an ephemeral planning artifact — before any code
   changes land.
1. Add `util/DepthChannelBudget.kt` per §4, unit-testable in isolation (pure Kotlin, no Android
   deps, same as `ModelBudgetManager` — can copy its shape almost directly).
2. Wire into `SpatialFrameProducer.processBitmap`, replacing the unconditional calls at lines
   202/205 with budget-gated calls.
3. Instrument each channel's actual wall-clock time (`System.currentTimeMillis()` around each
   call, same pattern already used for `inferMs` at line 223) and feed into `depthBudget.report(...)`.
4. Single isolated commit per code change (step 1+2 together, or split further) — no bundling
   unrelated changes, so a regression is bisectable in one step (this session's NNAPI revert was
   fast to diagnose specifically because it was isolated).

## 7. Verification

No physical device available in this environment; CI (`Build APK` workflow,
`claude/zip-extraction-nn9nko`) verifies compilation only, not runtime behavior.

1. CI green (`mcp__github__actions_list` → `get_job_logs` on failure → fix → repeat) is the
   only automated check available.
2. On-device verification (frame rate, perceived lag, tracking accuracy) requires the user to
   test and report back — cannot be shortcut or simulated from this environment.
3. Isolated commit per §6.4 so a regression can be bisected to this specific change if reported.
