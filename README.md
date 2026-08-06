# Labpano GPX Extractor

Offline Android application for the Labpano Pilot One. It monitors completed MP4 recordings, extracts CAMM GPS metadata, validates the track, writes GPX 1.1, organizes output files and provides the local API used by the companion Client App.

**Current version:** 0.5.8  
**Minimum Android:** 7.0 (API 24)  
**Target SDK:** 28  
**Compile SDK:** 35

## Processing flow

1. The user selects monitoring and output folders and presses **Start Monitoring**.
2. Both folders receive real create/write/read/delete access checks.
3. An MP4 must remain unchanged for 30 seconds and pass ISO-BMFF readiness checks.
4. CAMM type 5/6 GPS samples are extracted and synchronized to the MP4 timeline.
5. Invalid coordinates are routed to ERROR; GPS gaps over five seconds are routed to FAILED; otherwise the result is GOOD.
6. The GPX is generated, verified and transferred with its MP4.
7. Source files are deleted only after the destination is verified and the transfer journal is durable.

## Output structure

```text
Output/
└── dd-MM-yyyy/
    ├── GOOD_dd-MM-yyyy/
    ├── FAILED_dd-MM-yyyy/
    ├── ERROR_dd-MM-yyyy/
    ├── GOOD_dd-MM-yyyy.TXT
    ├── FAILED_dd-MM-yyyy.TXT
    └── ERROR_dd-MM-yyyy.TXT
```

Rolling `GOOD.TXT`, `FAILED.TXT` and `ERROR.TXT` files remain in the monitoring folder for the dashboard API.

## Wi-Fi service

When explicitly enabled, the app starts a foreground HTTP service on port 1100. It exposes only the configured monitoring and output roots, dashboard data, the durable pending-GPX queue, queue-specific GPX downloads from filesystem or SAF-authorized storage, file browsing and report-entry deletion used by the Client App.

## Build on GitHub

The repository contains one workflow: `.github/workflows/build-apk.yml`.
It installs Java 17 and Android SDK 35, runs JVM unit tests, builds the debug APK and uploads it as a workflow artifact.

See [GITHUB-UPLOAD-AND-BUILD.md](GITHUB-UPLOAD-AND-BUILD.md).

## Local build

Install JDK 17 and Android SDK 35, then run:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APK is created under `app/build/outputs/apk/debug/`.

## GitHub Actions runtime update

- `actions/checkout@v6` (Node.js 24)
- `actions/setup-java@v5` (Node.js 24)
- `actions/upload-artifact@v7` (Node.js 24)
- Java remains Temurin 17 for the Android/Gradle build.
- GitHub-hosted runners satisfy the required Actions runner version.
