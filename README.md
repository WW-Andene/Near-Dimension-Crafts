# Handy — Near Dimension Crafts

**Handy** is an Android app that turns a phone's single RGB camera into a passive,
markerless full-body and hand motion-capture rig — a software replacement for
LiDAR/depth hardware. It fuses up to twelve depth-sensing channels (ARCore Depth API,
structured light, photometric stereo, structure-from-motion, a monocular neural
depth model, rolling-shutter stereo, and more) into a single live point cloud,
tracks hands/face/body via MediaPipe, retargets the result onto a 3D rig, and
streams or exports it (OSC, BVH-style recordings, glTF/GLB).

No active illumination is used beyond the device's own torch — see
[`docs/depth-weakness-solutions.md`](docs/depth-weakness-solutions.md) for the
engineering notes behind the passive depth pipeline (outdoor light, low-light,
motion blur, etc.).

> Gradle project name is `Handy`; the repository is `Near-Dimension-Crafts`.

## Status

The codebase was split from a single `app` module into the focused Gradle
modules described below (see `settings.gradle`'s "R2" comment). `app/` now
only contains its own UI/ViewModel/feature-glue code and depends on the
library modules via `project(':x')` for everything else.

## Architecture

```
                         ┌─────────────┐
                         │    util     │  math, filters, perf/model budget, point cloud store
                         └──────┬──────┘
              ┌─────────────────┼─────────────────┐
              ▼                 ▼                 
        ┌───────────┐    ┌────────────┐
        │  camera    │    │  tracking  │  MediaPipe hand/face/body pipelines,
        │ (CameraX)  │    │            │  gesture classification, One Euro filtering
        └─────┬──────┘    └─────┬──────┘
              └────────┬────────┘
                        ▼
                  ┌───────────┐
                  │   depth    │  12-channel depth fusion (ARCore, structured
                  │            │  light, photometric, SfM, DA2 neural…),
                  │            │  marching cubes, TSDF, mesh smoothing/remeshing
                  └─────┬──────┘
                        ▼
                  ┌───────────┐
                  │  scanner   │  8-pose guided scan state machine, quality
                  │            │  gating, hand biometrics, personal model store
                  └─────┬──────┘
                        ▼
                  ┌───────────┐
                  │   mocap    │  bone/body retargeting, OSC streaming (send +
                  │            │  receive), motion recording, VRM blend shapes
                  └────┬──┬────┘
                       │  └────────────────┐
                       ▼                   ▼
                 ┌───────────┐       ┌───────────┐
                 │   render   │       │   export   │  GLB / glTF animation /
                 │ (OpenGL ES)│       │            │  PLY point cloud writers
                 └─────┬──────┘       └─────┬──────┘
                       └──────────┬─────────┘
                                  ▼
                            ┌───────────┐
                            │    app     │  Jetpack Compose UI, ViewModel,
                            │            │  feature glue (scan/record/stream)
                            └───────────┘
```

Module dependency order (each arrow is a Gradle `project()` dependency),
per `settings.gradle`:

```
util ← camera
util ← tracking
util, camera, tracking ← depth
util, tracking, depth ← scanner
util, tracking, depth, scanner ← mocap
util, tracking, mocap ← render
util, tracking, depth, scanner, mocap ← export
util, tracking, depth, scanner, mocap, render, export, camera ← app
```

### Modules

| Module     | Responsibility |
|------------|-----------------|
| `util`     | Shared math (lerp, quaternions), One Euro filter (3D), adaptive frame throttler, per-model inference budget manager, ring-buffer point cloud store, perf monitor state. |
| `camera`   | CameraX capture pipeline, YUV→Bitmap conversion, torch control. |
| `tracking` | MediaPipe Tasks wrappers for hand/face/body landmarks, One Euro smoothing, occlusion inference, temporal depth fusion, gesture vocabulary classification. |
| `depth`    | The core "software LiDAR": per-source depth estimators (ARCore, structured light, photometric stereo, SfM, Depth Anything v2, rolling-shutter stereo), the 12-channel `CrossChannelArbiter` fusion, `SpatialLayer` orchestration, marching cubes meshing, TSDF volume, mesh smoothing/remeshing. |
| `scanner`  | Guided 8-pose scan workflow (`Scanner`, `ScanPoses`), freeform continuous scanning, CLAHE image enhancement, quality gating, hand biometrics, joint range-of-motion accumulation, personal model persistence. |
| `mocap`    | Converts live tracking landmarks into joint rotations (bone/body retargeting), biomechanical constraint filtering, quaternion EMA smoothing, OSC send/receive networking, motion recording, VRM blend shape parsing, bundled asset generation/loading. |
| `render`   | OpenGL ES 3.0 renderers for camera passthrough, hand/body/face skeletons, live-deforming mesh, point cloud, skinned GLB meshes, and the standalone model viewer. |
| `export`   | Writes GLB (static + animated glTF), and ASCII PLY point clouds; texture atlas baking. |
| `app`      | Jetpack Compose UI (onboarding, scan/record/settings/model-viewer screens, HUD overlays), `AppViewModel`, and feature-layer glue (asset management, recording, scan pipeline orchestration, spatial frame routing, OSC discovery/management, tracking manager). |

## Requirements

- JDK 17
- Android SDK (`compileSdk 34`, `minSdk 26`, `targetSdk 34`)
- Gradle 8.7 (via the included wrapper — no local Gradle install needed)

## Building

```bash
./gradlew assembleDebug
```

CI (`.github/workflows/build.yml`) runs the same command on every push to
`main`/`claude/**` and on pull requests, uploading the debug APK as an artifact.

### Optional bundled 3D assets

`app/src/main/assets/models/` expects `hand_default.glb` and `body_default.glb`
(CC0-licensed rigs). These are **not included** in the repo due to licensing —
the app builds and runs without them, falling back to a default puppet.

### Feature flags

`app/build.gradle` defines `FEATURE_SMPL_BODY` (default `false`). Keep it
`false` for any public/Play Store build — it gates an experimental SMPL/BODY-8
body model whose weights (if added locally) carry a non-commercial research
licence.

## Permissions

The app requests `CAMERA`, `FLASHLIGHT`, `INTERNET`, and
`CHANGE_WIFI_MULTICAST_STATE` (for OSC/mDNS discovery on the local network).
ARCore (`com.google.ar.core`) is declared `optional` — the app is designed to
install and run on devices without Play Services for AR, degrading gracefully
to SfM/photometric depth sources.

