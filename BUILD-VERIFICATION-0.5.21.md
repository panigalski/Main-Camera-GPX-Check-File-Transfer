# Build Verification — 0.5.21

## Change verified

- `AppConfig.defaultOutputDirectory` is `/sdcard/DCIM/Videos/Stitched`.
- `RESET OUTPUT FOLDER` uses `AppConfig.defaultOutputDirectory`, so reset targets the same path.
- New installs with no stored OUTPUT setting use the same path.
- Existing installs that still store the previous default `/storage/emulated/0/videos/stitched` migrate to the new default when no SAF output tree is selected.
- Explicit custom OUTPUT paths and SAF output trees are preserved.
- Recording default remains `/sdcard/DCIM/Videos/Stitched`.
- Startup OFF policy, dated `OUTPUT/dd-mm-yyyy/` media layout, and global GOOD/FAILED/ERROR reports are unchanged.

## Source checks

- Version bumped to 0.5.21 / versionCode 44.
- No application logic outside default OUTPUT selection/migration was changed.
