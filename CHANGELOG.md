# Changelog

## Unreleased — maintenance pass (2026-10)

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
