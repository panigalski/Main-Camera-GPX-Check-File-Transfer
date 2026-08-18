# Labpano GPX Extractor — Main App

Android application for the Labpano Pilot One that monitors completed MP4 recordings, extracts CAMM GPS metadata, validates GPX timing, organizes processed recordings, and exposes the local API used by the companion smartphone client.

**Current release:** 0.5.47 (`versionCode 70`)  
**Package:** `com.labpano.gpxextractor`  
**Minimum Android:** 7.0 / API 24  
**Target / compile SDK:** API 28  
**Recommended companion:** Labpano GPX Client 1.10.33

## Main functions

- Monitors the selected Pilot recording folder without processing the active recording.
- Extracts CAMM GPS metadata from finalized MP4 files.
- Uses MP4/CAMM presentation time as the relative GPS timeline so raw GPS-clock corrections do not create false gaps.
- Applies the recording-quality rule before interpolation:
  - maximum real CAMM gap `<= 5.000 s` → **GOOD**
  - maximum real CAMM gap `> 5.000 s` → **FAILED**
  - extraction, validation, or permanent processing failure → **ERROR**
- Writes GPX 1.1 files for processed recordings.
- Maintains cumulative root and daily status reports, created only when that status actually occurs.
- Publishes dashboard/live status used by the Client, including recording state, transfers, Fragment Storage diagnostics, reports, storage and device information.
- Accepts the Client's manual, checksum-verified `_backup.gpx` uploads into the matching date/status folder.

## Output Folder layout

```text
OUTPUT/
├── GOOD.TXT                          # only if at least one GOOD exists
├── FAILED.TXT                        # only if at least one FAILED exists
├── ERROR.TXT                         # only if at least one ERROR exists
└── dd-MM-yyyy/
    ├── GOOD/
    │   ├── dd-MM-yyyy_GOOD.txt           # only if GOOD occurred that day
    │   ├── <video>.mp4
    │   ├── <video>.gpx
    │   └── <video>_backup.gpx          # optional Client upload
    ├── FAILED/
    │   ├── dd-MM-yyyy_FAILED.txt         # only if FAILED occurred that day
    │   ├── <video>.mp4
    │   ├── <video>.gpx
    │   └── <video>_backup.gpx          # optional Client upload
    └── ERROR/
        ├── dd-MM-yyyy_ERROR.txt          # only if ERROR occurred that day
        ├── <video>.mp4
        ├── <video>.gpx                 # only when a valid Camera GPX exists
        └── <video>_backup.gpx          # optional Client upload
```

A root report exists only after the first recording of that status. A daily report exists only after that status occurs on that date. The same committed recording entry is written to both levels. There are no per-video/per-segment TXT files. Empty legacy `PROCESSING` folders are removed automatically.

See [docs/OUTPUT-LAYOUT.md](docs/OUTPUT-LAYOUT.md) for the current folder contract.

## Build

The repository includes the Gradle wrapper and a GitHub Actions workflow at `.github/workflows/build-apk.yml`.

Local prerequisites:

- JDK 17
- Android SDK Platform 28
- Android Build Tools 30.0.3

Run:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is produced under:

```text
app/build/outputs/apk/debug/
```

For repository setup and CI details, see [docs/BUILD.md](docs/BUILD.md).

## Repository contents

- `app/` — Android application source, resources, and JVM unit tests
- `gradle/`, `gradlew`, `gradlew.bat` — pinned Gradle wrapper
- `.github/workflows/` — GitHub Actions build workflow
- `docs/` — current build and output-layout documentation
- `CHANGELOG.md` — version history

Generated build output, Android Studio metadata, local SDK configuration, APK/AAB files, and signing material are intentionally excluded by `.gitignore`.
