# Build Verification — 0.5.20

## Scope
- Corrected default/reset Recording path from `/sdcard/DCIM/Videos/Stichted` to `/sdcard/DCIM/Videos/Stitched`.
- Version bumped to 0.5.20 / code 43.
- No processing, reporting, monitoring, Wi-Fi, or output-folder behavior changed.

## Static checks
- No remaining `Stichted` spelling in source/docs.
- `AppConfig.defaultRecordingDirectory` resolves to `/sdcard/DCIM/Videos/Stitched`.
- ZIP integrity verified after packaging.

## Full Android build
Not run in this sandbox because the Gradle wrapper requires network access to download Gradle 8.9.
