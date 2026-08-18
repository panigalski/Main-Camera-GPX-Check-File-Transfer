> **Build profile:** 0.5.43 keeps the 0.5.42 CAMM timeline repair and uses the final date-first classified OUTPUT layout with root cumulative reports and per-recording status reports.

# Labpano GPX Extractor

Offline Android application for the Labpano Pilot One. It monitors completed MP4 recordings, extracts CAMM GPS metadata, validates the track, writes GPX 1.1, organizes output files and provides the local API used by the companion Client App.

**Current version:** 0.5.43  

OUTPUT folder changes are now committed immediately and exposed to the companion client on the next dashboard poll; Monitoring does not need to be restarted.
**Minimum Android:** 7.0 (API 24)  
**Target SDK:** 28  
**Compile SDK:** 28



## 0.5.43 final date-first OUTPUT layout

- Root cumulative reports are `OUTPUT/GOOD.TXT`, `OUTPUT/FAILED.TXT`, and `OUTPUT/ERROR.TXT`.
- Recording files are stored under `OUTPUT/dd-MM-yyyy/GOOD|FAILED|ERROR/`. All three status folders are created for a processed recording date.
- Each recording gets a status report beside it: `<MP4-base>_ GOOD.txt`, `<MP4-base>_ FAILED.txt`, or `<MP4-base>_ ERROR.txt`.
- Client manual `_backup.gpx` uploads target the same `OUTPUT/dd-MM-yyyy/<STATUS>/` folder.
- Existing 0.5.42 cumulative reports under `OUTPUT/<STATUS>/<STATUS>.TXT` are accepted/migrated to the root report location when needed.

## 0.5.42 GPX timeline and classified OUTPUT repair

- CAMM media presentation time is now authoritative for the relative GPS timeline. Type-6 absolute GPS time is used to establish/diagnose the absolute start only, so a mid-recording GPS-clock jump cannot manufacture a false GPX gap while the MP4 CAMM timeline is continuous.
- The exact 5-second policy is preserved: a largest consecutive real-CAMM gap of 5.000 s still passes; 5.001 s or more is FAILED. Interpolation is never used to hide a gap larger than 5 seconds.
- OUTPUT is classified as `GOOD/dd-MM-yyyy/`, `FAILED/dd-MM-yyyy/`, and `ERROR/dd-MM-yyyy/`. Each status folder owns its cumulative `GOOD.TXT`, `FAILED.TXT`, or `ERROR.TXT`.
- Pending-media API rows now include the full MP4 start/end interval, including ERROR media when the movie interval can be read, so Client 1.10.30 can build phone backups independently of Camera GPX timing defects.
- Manual Client backup uploads are constrained to the matching `OUTPUT/<STATUS>/dd-MM-yyyy/` folder.


## 0.5.36 Recording-folder MP4 sequence transfer

Monitoring captures the list of MP4 files already present in the selected Recording folder. The first new MP4 is treated as the current file A and is protected. When another new finalized MP4 B appears, A is released for the normal CAMM/GPS gap validation and OUTPUT move while B stays protected and continues recording. C releases B, and the process repeats. The final active MP4 is released by the Camera's final `addFile` stop signal because no successor will be created. Fragment Storage collection remains informational only and has no role in this transfer policy.


## 0.5.35 live Camera Fragment Storage / Divider restart

Camera 5.18.11 writes Fragment Storage changes directly to `/efs/video.properties`; Main now watches that backing file instead of relying on the Camera app's process-local EventBus UI event. The stock Divider also emits `gallery.fileChange` again after each internal fragment stop/restart. That repeated callback now releases the previous exact fragment immediately while the next fragment and overall capture remain Recording. See `CAMERA-5.18.11-LIVE-FRAGMENT-FIX-0.5.35.md`.


## 0.5.34 Camera-APK-grounded Fragment Storage

The supplied stock Camera 5.18.11 APK shows that Fragment Storage is stored in `/efs/video.properties` and that each fragment boundary is a low-level stop followed by restart into a new timestamp file. Main 0.5.34 uses those facts directly: it reads `video.storagePart.able/value` when permissions allow and releases a settled predecessor after a distinct successor is proven active, without waiting for `camera.getOptions` or final-stop gallery `addFile`. See `CAMERA-5.18.11-FRAGMENT-STORAGE-AUDIT-0.5.34.md`.


## 0.5.33 filesystem fragment rollover

- Rolling transfer no longer depends on a successful Fragment Storage API read or on `addFile`. When a distinct next MP4 writer appears, the previous MP4 can be released only after the next writer is proven active, the previous file has stopped changing, and the MP4 readiness checker confirms a finalized container.
- Monitoring may start in the middle of an existing fragment. If the first concrete writer event received belongs to fragment B, the engine searches the already-observed finalized MP4s and bootstraps the immediately preceding fragment A, preventing the first rollover from being lost until Stop.
- `.mp4.part` and `.mp4.tmp` writer aliases are normalized to the same MP4 identity. Only the previous proven-complete stem bypasses stale Camera ownership; the current fragment remains protected.
- Fragment Storage still uses the Pilot control protocol when available. Direct read timeouts now continue into the session fallback instead of being treated as a dead host. If the protocol remains unavailable, a proven 4/6/8/10 GB or 10/30/60/120-minute rollover may be displayed as an explicitly observed setting such as `4 GB (observed)`.
- The dashboard exposes the Fragment Storage source/error so the Client can distinguish a protocol value, observed fallback, and a real control-service failure.

## 0.5.30 recording / transfer decoupling

- Recording ownership and transfer eligibility are now independent per-file states. The exact Camera-owned active recording remains protected, while an older completed recording can be processed and moved even if a newer recording has already started.
- A new Camera `fileChange` cannot reassign the previous still-finalizing MP4 to the new recording generation. Delayed completion for an older recording cannot affect the current recording.
- After the app has observed Pilot Camera lifecycle events, ordinary MP4 filesystem activity can no longer manufacture a new `Recording` state after Camera completion. Copy, verify, rename and delete activity therefore cannot make the Client flash `Recording` after Stop.
- `/api/v1/live-status` now publishes an additive Camera lifecycle `generation` used by Client 1.10.15 for monotonic status ordering.

## 0.5.29 recording / transfer / report stability

- Pilot Camera recording ownership is tracked per video and synchronized across broadcast/FileObserver threads. A delayed `addFile` for the previous video can no longer clear a newer recording.
- MP4 `CLOSE_WRITE` and IMU-close events are treated as filesystem details, not recording-stop signals.
- Camera `addFile` accelerates completed media from the generic 30-second stability delay to a 2-second settling window plus readiness validation.
- Large MP4 transfer verification no longer performs a full second SHA-256 destination read. Local copies use exact byte count, fsync, unchanged-source checks, destination size and bounded content samples; SAF copies use exact byte count, unchanged-source checks and bounded provider-size verification.
- Before a completed transaction is reported, all three OUTPUT-root `GOOD.TXT`, `FAILED.TXT` and `ERROR.TXT` files are ensured at that transaction's actual output destination.

## Processing flow

1. The app always starts with **Monitoring OFF** and **Wi-Fi file access OFF**.
2. The default/reset Recording folder is `/sdcard/DCIM/Videos/Stitched`; the user can select another folder.
3. The default/reset OUTPUT folder is `/sdcard/DCIM/Videos/Stitched`; selecting another valid OUTPUT folder commits it immediately, even while Monitoring is already running.
4. The app creates/opens `GOOD/GOOD.TXT`, `FAILED/FAILED.TXT`, and `ERROR/ERROR.TXT` under the OUTPUT root.
5. The selected Recording folder is watched by FileObserver and a 5-second scanner. The active Pilot recording is never parsed/moved/deleted.
6. Camera `addFile` completion gets a short 2-second settling guard; filesystem-only fallback still uses the conservative 30-second stability gate. The finalized MP4 must then pass ISO-BMFF readiness checks; CAMM GPS is extracted and validated.
7. The result status is determined before transfer: no gap over 5 seconds = GOOD, a gap over 5 seconds = FAILED, and extraction/validation/processing failure = ERROR.
8. Media is verified under `OUTPUT/<STATUS>/dd-mm-yyyy/` before source cleanup, and the result is appended to `OUTPUT/<STATUS>/<STATUS>.TXT`.
9. START MONITORING preflights the OUTPUT root and creates/verifies all three status folders/report files; if this fails, Monitoring is not started and the UI reports the storage error.

The dashboard API also exposes the live Monitoring-service state and report health (destination, existence/readability/writability and sizes of all three global TXT files) so the Client can distinguish a real empty report from a missing/unreadable report.

Dashboard API v3 also publishes additive `deviceDiagnostics` with connected Bluetooth-device state, passively observed RSSI when available, active Android location/mock-source metadata and system GNSS satellite/C/N0 status for Client 1.10.9+.

## Monitoring and output structure

New output is organized as:

```text
OUTPUT/
├── GOOD/
│   ├── GOOD.TXT              # cumulative GOOD report
│   └── 17-08-2026/
│       ├── recording-001.mp4
│       ├── recording-001.gpx
│       └── recording-001_backup.gpx   # optional manual Client upload
├── FAILED/
│   ├── FAILED.TXT            # cumulative FAILED report
│   └── 17-08-2026/
│       ├── recording-002.mp4
│       └── recording-002.gpx
└── ERROR/
    ├── ERROR.TXT             # cumulative ERROR report
    └── 17-08-2026/
        └── recording-003.mp4
```

Date folders contain media/GPX only; no daily TXT reports are created there. Legacy files/folders made by older releases are not automatically deleted.

A zero-byte MP4 is never treated as a valid recording. If it remains unchanged for two minutes while Pilot is not recording, it is preserved as error evidence under `OUTPUT/ERROR/dd-MM-yyyy/` and logged to `OUTPUT/ERROR/ERROR.TXT`.

## Wi-Fi service

When explicitly enabled, the app starts a foreground HTTP service on the configured port (1100 by default). It exposes the configured monitoring and output roots, dashboard data, the durable pending-GPX queue, queue-specific GPX downloads from filesystem or SAF-authorized storage, file browsing and report-entry deletion used by the Client App. The dashboard's **Pilot One Recording Status** uses the Pilot Camera start signal plus a status-only Recording-folder observer. Client 1.10.18 polls the lightweight `/api/v1/live-status` endpoint every 250 ms. Main 0.5.30 publishes a monotonic Camera lifecycle generation and keeps recording ownership separate from transfer eligibility: the exact active Camera file is protected, older completed files may continue through processing/transfer while a newer recording is active, and copy/finalization filesystem activity cannot resurrect `Recording` after Camera completion.

## Build on GitHub

The repository contains one workflow: `.github/workflows/build-apk.yml`.
It installs Java 17, Android SDK Platform 28 and Build Tools 30.0.3, runs JVM unit tests, builds the debug APK and uploads it as a workflow artifact.

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
## 0.5.11

- Recording and Output folder browse buttons now use a matching raised 3D light-blue style with a visible pressed state.

## 0.5.10

- Monitoring-root GOOD/FAILED/ERROR reports now retain all records without automatic trimming.
- Daily `dd-MM-yyyy` subfolders inside the monitoring folder contain only that date's `GOOD.TXT`, `FAILED.TXT` and `ERROR.TXT` records.

## 0.5.9

- Added persistent MP4 destination-write failure monitoring for internal and external storage.
- Records failures while preparing the output folder, writing/finalizing MP4 files, and verifying completed MP4 copies.
- Exposes recent failures to the Client App through the additive `storageWriteAlerts` field in dashboard API v3.
- Write alerts are retained for seven days, bounded to 50 entries, and identical retry failures are deduplicated for five minutes.



## Version 0.5.12

The main-screen buttons now share one full-width 52 dp 3D shape, including the Advanced-section reset controls. Recording and output defaults/reset targets are now internal `videos/stitched`. Version 0.5.14 keeps completed media directly in that shared root and safely adopts files in place.
## Version 0.5.13

Version 0.5.13 introduced status-suffix naming for dated media subfolders. Version 0.5.14 supersedes that media layout by placing new completed media directly in the OUTPUT root. The built-in Wi-Fi file server remains on port 1100 by default but can be assigned another TCP port under Advanced, allowing another app to use a different port such as 1200 at the same time.


## Version 0.5.14

Completed MP4/GPX transfers now land directly in the selected OUTPUT root instead of dated/status subfolders. When Monitoring and Output are the same physical folder, daily report subfolders are suppressed so the shared output stays flat with only the three cumulative TXT reports.


## 0.5.15 camera recording status

The dashboard API now includes an additive `cameraRecording` object used by Client 1.9.7 to show live Pilot MP4 recording activity. The dashboard remains API v3 compatible with older clients.



## Startup policy (0.5.19)

A fresh app launch, device boot, or app replacement always leaves Recording Monitoring and Wi-Fi file access stopped. Neither service is restored automatically; both require an explicit button press.

## Pilot One recording status (0.5.17+)

Recording status now follows Labpano Camera 5.18.x cross-app media broadcasts first, verified against fresh files in PilotSDK video locations (`/sdcard/DCIM/Videos/Stitched/` and related paths). FileObserver and MP4 growth detection remain fallbacks.

## 0.5.19

- Default/reset Recording path: `/sdcard/DCIM/Videos/Stitched`.
- Default/reset OUTPUT path: `/sdcard/DCIM/Videos/Stitched`.
- Media/GPX output restored to `OUTPUT/dd-mm-yyyy/`.
- Only cumulative `GOOD.TXT`, `FAILED.TXT`, and `ERROR.TXT` are created at OUTPUT root.
- Report API reads/deletes those OUTPUT-root reports.
- Active Pilot recordings are protected from processing/source cleanup.
- Stale zero-byte MP4 placeholders are safely removed and logged.
- Monitoring and Wi-Fi are always OFF on startup.
