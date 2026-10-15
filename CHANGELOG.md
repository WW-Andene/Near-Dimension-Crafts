# Changelog

## Unreleased — project goal and rename (2026-10)

### Added
- `docs/VISION.md`: the project goal (a LiDAR substitute for Android phones),
  the Detection → Translation → Application layers, measurable success
  criteria, public-dataset benchmark plan and roadmap.

- Depth Anything V2 Small (int8, Apache-2.0) bundled; the neural depth channel
  now actually runs (it never loaded before — four separate bugs, see git log).
- `tools/benchmark/da2_tum.py` and first results in `docs/benchmarks/`.

- Rigged humanoid models now actually animate. The renderer ignored skin weights
  (every vertex bound to bone 0), drew indexed meshes as triangle soup, rotated
  bones about the model origin without hierarchy, and only drew a body model while
  a hand was visible. New: `SkinnedGlbBuilder` (all primitives, indices, normals,
  real JOINTS/WEIGHTS, scene-space Y-up rest pose, rig reduced to ≤ 56 bones),
  `HumanBoneMapper` (Unity, VRM 0/1, Mixamo, Biped, Unreal, Sketchfab suffixes) and
  `BodySkinPose` (retargets onto the rig's own rest pose — T or A — with hierarchy,
  facing detection and front-camera mirroring). JVM tests cover all three.
- Bundled body model: the CC BY 4.0 ball-joint doll by ChamberSu1996, rigged with
  `tools/rigging/rig_doll.py`; credited in the asset picker.

### Changed
- Skin palette 64 → 56 bones so the skinned shader fits OpenGL ES 3.0's minimum
  of 256 vertex uniform vectors (64 could fail to link on some Adreno GPUs).
- DA2 input 518×518 squashed → 364×280, oriented like the camera frame
  (portrait 280×364 in the app): ~2× faster on x86 fp32, and no longer distorts the image.
- Package and application ID renamed `com.arhand` → `com.neardimension.handy`
  across all modules. **An install of the old `com.arhand` build is a separate
  app**: uninstall it; its local data (scans, takes) is not carried over.
- Splash and first onboarding page now describe the app as a software LiDAR.
- The takes library's share URI uses the package name instead of a hardcoded
  authority.

## Maintenance pass (2026-10)

First pass after several months without activity: cleanup, dependency update and
restructuring. Verified locally with unit tests, lint, debug and release builds;
**not yet verified on a device**.

### Fixed
- **BVH export rotations were wrong.** `MotionRecorder.quaternionToZXY` read the
  wrong rotation-matrix elements for the `Zrotation Xrotation Yrotation` channel
  order. Re-derived, with an arbitrary-angle round-trip test.
- **Release builds dropped the 8 pose illustrations** shown during guided scans
  (looked up by name, so the resource shrinker removed them). Kept via
  `res/raw/keep.xml`.
- `StereoDepthSource` opened the second camera without checking the camera
  permission.
- CameraX experimental APIs were opted into with the wrong `OptIn` annotation.
- `QualityEngineTest` used swapped "centred"/"off-edge" coordinates.
- 5 unit tests had been failing unnoticed — CI never ran tests.

### Changed
- Gradle 9.8.1, AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21 + Compose compiler plugin.
- `compileSdk 37`, `targetSdk 36`. Edge-to-edge is now handled: controls are
  inset from system bars and cutouts; camera/GL surfaces stay full-bleed.
- Libraries: Compose BOM 2026.09.00, CameraX 1.6.2, MediaPipe Tasks 1.1.1,
  ARCore 1.57.0, ONNX Runtime 1.31.0, AndroidX and coroutines to current stable.
- Versions centralised in `gradle/libs.versions.toml`; shared library-module
  Android config moved to the root `build.gradle`.
- `MainActivity.kt` split: each panel (asset picker, stream, record/takes,
  toolbar, calibrate sheet) now lives in its own file. No code changes.
- `ENGINE_ARCHITECTURE.md` and `REDESIGN_PLAN.md` moved into `docs/`.
- CI runs unit tests and lint before building, on JDK 21, with current
  action versions; uploads reports on failure.

### Removed
- `pose_landmarker_full.task` (9.4 MB, never loaded), unused XML colours, the
  unnecessary `FLASHLIGHT` permission, compiler-warning noise.
