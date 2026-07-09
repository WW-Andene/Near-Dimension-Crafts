Place bundled .glb files here.

Expected files (loaded by AssetLoader.Bundled):
  hand_default.glb   — CC0 hand rig (HAND_PUPPET slot, ≤20 joints)
  body_default.glb   — CC0 full-body VRM-compatible rig (BODY_CHARACTER slot, ≥21 joints)

These files are not included in the repository due to licensing constraints.
Add CC0-licensed GLB files before building for distribution.

The app builds and runs without these files — loadBundledAsset() returns
DEFAULT_PUPPET gracefully if the file is absent.
