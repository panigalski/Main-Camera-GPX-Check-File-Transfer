# Full Recording / Information-Flow Audit — Main App 0.5.27

Baseline audited: Main App 0.5.26 with Client App 1.10.11, plus the supplied Labpano Camera 5.18.11 APK behavior previously inspected for the recording lifecycle.

## Recording-status findings

1. The previous Client-visible delay had multiple layers, not one bug. Main 0.5.26 used a 2.5-second write-idle display threshold, Client polled the large dashboard every 3 seconds, and the visible Activity normally synchronized shared state on a 1-second timer.
2. Camera completion and live capture are not the same state. The Main App must keep the MP4 protected while Camera/PilotSDK finalizes it, but the Client must already show `Ready` once image capture ends.
3. A late MP4 finalization write could make a write-activity based display look active again. 0.5.27 makes capture-stop sticky for the current video after a strong close/completion signal or after 1 second of write inactivity. A later write to that same video cannot resurrect `Recording`.
4. Camera's `fileChange` action is not video-only. The deeper audit reproduced a false-reopen case: a photo `fileChange` arriving soon after a stopped MP4 could re-associate that old MP4 and clear its stopped latch. 0.5.27 now forbids generic start hints and MODIFY events from reopening an MP4 already marked capture-stopped. Only a genuine new-file `CREATE` can clear that stopped state.
5. Recording status previously got its best FileObserver stream from the processing monitor. 0.5.27 adds a separate status-only observer while the Wi-Fi API is active, so live status does not depend on START MONITORING.
6. Camera `addFile` is treated as definitive ownership release and stale active-file observations are removed immediately. This prevents an already-completed file from holding the conservative recording gate for the old 15-second event timeout.
7. Restarting the processing monitor now clears raw file observations without clearing a known capture-stop state, so a monitor restart cannot make finalization look like a new recording.

## Safety separation

`cameraRecording.recording` in the HTTP API is the client-visible capture state. Internally, `CameraRecordingStatusRegistry.Snapshot.recording` remains the conservative Camera-ownership state used by `RecordingProcessingEngine`. The processing engine still refuses the active Camera-owned file, then applies the existing stable-file and MP4-readiness gates before processing/moving it. No early file move was introduced by the UI-status fix.

## Live information endpoint

`GET /api/v1/live-status` is additive to API v3 and returns only:
- `generatedAt`
- `outputFolder`
- `monitoring`
- `cameraRecording` including `finalizing`
- `transfers`

The full `/api/v1/dashboard` remains unchanged for existing clients and continues to carry storage, battery/temperature, reports, storage-write alerts and Bluetooth/GPS diagnostics. Both endpoints are `Cache-Control: no-store`.

## Expected update cadence with Client 1.10.12

- Recording / Monitoring / OUTPUT folder / transfer progress: 250 ms Client polling cadence, plus network/processing time.
- Capture idle fallback: 1 second. Writer-close or optional Camera `.imu` close can end the display state earlier.
- Storage / battery / temperature / reports / Bluetooth-GPS diagnostics: 3-second full dashboard cadence.

There is no public cross-app signal in the audited Camera flow that reports the user's Stop tap at the exact instant it is pressed. Therefore the implementation does not claim zero-latency stop. It uses the earliest safe externally observable signal and prevents later finalization activity from changing `Ready` back to `Recording`.

## Focused verification

- Standalone Kotlin recording-state harness: PASS.
- Fresh start -> Recording: PASS.
- Write-idle -> Ready while ownership remains protected/finalizing: PASS.
- Late finalization MODIFY cannot resurrect Recording: PASS.
- Camera `addFile` -> Ready and immediate ownership release: PASS.
- New recording after previous stop -> Recording: PASS.
- Writer close -> immediate display stop: PASS.
- Photo `fileChange` + old-video finalization MODIFY cannot resurrect Recording: PASS.
- Processing-monitor/FileObserver restart + old-video MODIFY cannot resurrect Recording: PASS.
- Static Main/Client information-flow contract checks: PASS (27/27 combined assertions during audit).
- Kotlin parser checks for changed Android-dependent files: no syntax/parser errors.
- Full Gradle/APK build: not executed in this sandbox because the Gradle distribution cannot be downloaded from `services.gradle.org`. Use JDK 17 locally with Gradle 7.6.4.
