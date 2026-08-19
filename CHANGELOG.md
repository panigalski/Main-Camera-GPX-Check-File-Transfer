## 0.5.49

- Added cumulative transfer totals to root `GOOD.TXT`, `FAILED.TXT`, and `ERROR.TXT`: MP4 files transferred, total video-recording hours, and MP4 data transferred in decimal GB.
- Store MP4 byte size and canonical video duration as machine-readable metadata on new report entries so retries/recovery do not double-count totals.
- Keep summary lines out of dashboard/report-entry APIs and daily-report migration so Client compatibility and date/status reports remain unchanged.
- Rebuild global summaries on startup, append, recovery, and report-entry deletion; legacy entries expose statistics coverage when old metadata cannot be recovered safely.

## 0.5.48

- Renamed the main Wi-Fi control to `START WI-FI CONNECTION` / `STOP WI-FI CONNECTION`.
- Disable and grey the Wi-Fi connection button when the camera has no active Wi-Fi/Ethernet/Bluetooth network with a usable local IPv4 address.
- Re-enable the button automatically when a usable camera network becomes available.
- Stop the Wi-Fi file-server service automatically if the camera loses its network while the service is active.
- Keep the stored Wi-Fi server state synchronized with service status broadcasts so a failed/stopped server cannot leave a stale enabled preference.

## 0.5.47

- Create GOOD/FAILED/ERROR report files lazily: no global or daily report exists until at least one recording of that status is committed.
- Remove empty placeholder report files left by 0.5.46 when there is no corresponding classified MP4.
- Preserve dual reporting: matching recordings still append to both the cumulative root report and the dated status report.
- Keep per-video TXT reports disabled and empty PROCESSING-folder cleanup unchanged.

# Changelog

## 0.5.46

- Restored cumulative `GOOD.TXT`, `FAILED.TXT`, and `ERROR.TXT` files directly in the OUTPUT root while retaining the date/status daily reports introduced in 0.5.45.
- Every committed recording is appended to both the cumulative root report and the matching `dd-MM-yyyy/<STATUS>/dd-MM-yyyy_<STATUS>.txt` daily report.
- Added upgrade backfill from 0.5.45 daily reports when a cumulative root report is missing.
- Report-entry deletion now removes the same entry from both cumulative and daily report copies.
- Kept the no-per-video-TXT rule and empty legacy `PROCESSING` folder cleanup.

## 0.5.45

- Replaced root cumulative reports with recording-day reports stored inside each date/status folder: `dd-MM-yyyy/GOOD/dd-MM-yyyy_GOOD.txt`, `FAILED/dd-MM-yyyy_FAILED.txt`, and `ERROR/dd-MM-yyyy_ERROR.txt`.
- Kept exactly one report per status per recording day; no per-video/per-segment TXT files are produced.
- Dashboard report tails now aggregate across the daily files, preserving the existing Client API.
- Added safe migration of obsolete 0.5.42–0.5.44 root/status cumulative report files into the new daily layout before deleting the old files.
- Pending-GPX legacy import now understands the current daily-report layout while retaining older-layout compatibility.
- Empty legacy `PROCESSING` folders continue to be removed; non-empty legacy folders remain protected.

## 0.5.44
- Removed per-recording/per-segment status TXT generation; only the cumulative root `GOOD.TXT`, `FAILED.TXT`, and `ERROR.TXT` reports remain.
- Stopped creating `OUTPUT/dd-MM-yyyy/PROCESSING/` as a side effect of report generation.
- Added safe cleanup of legacy empty `PROCESSING` folders on monitoring startup and after committed transactions; non-empty folders are preserved.
- Updated the on-device Output Folder description and repository documentation for the simplified layout.

## 0.5.43
- Changed classified OUTPUT layout to date-first folders: `OUTPUT/dd-MM-yyyy/GOOD/`, `FAILED/`, and `ERROR/`.
- Moved cumulative `GOOD.TXT`, `FAILED.TXT`, and `ERROR.TXT` reports to the OUTPUT root; existing 0.5.42 nested cumulative reports are read/migrated for compatibility.
- Added one per-recording status report beside each result, named `<MP4-base>_ GOOD.txt`, `<MP4-base>_ FAILED.txt`, or `<MP4-base>_ ERROR.txt`.
- Creates all three GOOD/FAILED/ERROR sibling folders for each recording date once that date is processed.
- Updated manual Client `_backup.gpx` uploads to `OUTPUT/dd-MM-yyyy/<STATUS>/` so backup GPX files sit beside the matching MP4/Camera GPX/status TXT.

## 0.5.42
- Rebuilt CAMM timestamp synchronization around MP4/CAMM presentation time. Type-6 GPS absolute time is now an absolute-start candidate/diagnostic rather than the per-sample pacing clock, preventing false gaps caused by mid-recording GPS-clock jumps.
- Added timing diagnostics for canonical timeline strategy, raw GPS-clock discontinuities and decoded/canonical sample counts.
- Preserved the strict gap rule: exactly 5.000 seconds passes; anything greater than 5 seconds is FAILED.
- Classified processed media into `OUTPUT/GOOD/dd-MM-yyyy/`, `OUTPUT/FAILED/dd-MM-yyyy/`, and `OUTPUT/ERROR/dd-MM-yyyy/`; cumulative reports now live at `OUTPUT/GOOD/GOOD.TXT`, `OUTPUT/FAILED/FAILED.TXT`, and `OUTPUT/ERROR/ERROR.TXT`.
- Added full MP4 start/end times to the durable pending-media queue and an opt-in `includeMediaOnly=1` API mode, allowing Client 1.10.30 to back up ERROR videos without depending on an extracted Camera GPX.
- Manual Client backup GPX uploads now require GOOD/FAILED/ERROR classification and are stored beside the matching recording in `OUTPUT/<STATUS>/dd-MM-yyyy/`.
- Hardened zero-byte ERROR quarantine so finalized empty placeholders are retained as error evidence instead of being silently deleted.

## 0.5.41
- Added the restricted `POST /api/v1/backup-gpx-upload` endpoint used only by the Client's manual **Send GPX Files** action.
- Uploaded Client backup GPX files are written under the configured Output Folder in the matching `dd-MM-yyyy/` subfolder.
- The write surface is constrained to date folders and `*_backup.gpx` names, requires Content-Length, caps payloads at 16 MiB, validates GPX content, and verifies SHA-256/byte size after writing.
- Supports both filesystem and persisted SAF Output Folder destinations. Same-name/same-checksum uploads are idempotent; different same-name files return a conflict instead of being overwritten.
- Replaced the HTTP request reader with a byte-safe header parser so POST bodies cannot be consumed accidentally by buffered character reads while preserving existing GET/HEAD/DELETE endpoints.

## 0.5.40
- Final-release audit hardening for Fragment Storage and Main/Client state ordering.
- Removed persistent recording-family guesses; Camera property edits are short-lived mode hints only, and the shared Stitched/Street View directory is treated as ambiguous unless Camera-derived evidence resolves it.
- Synchronized compatibility HTTP cache merges with `/efs` / Settings observers so a slow fallback response cannot overwrite a newer Camera setting.
- Moved Fragment Storage refresh throttling and command-status deadlines to monotonic device uptime, preventing GPS/NTP wall-clock rollback from freezing refresh logic.
- Hardened `/efs/video.properties` partial-rewrite handling, FileObserver retry backoff, diagnostics rate limiting, and generic settings-change refresh of both local sources.
- Added process-instance + elapsed-realtime response metadata for robust Client ordering across Main-App restarts and Pilot reboots.
- Restored the Gradle wrapper bootstrap JAR and refreshed final-release audit/build documentation.

## 0.5.39
- Fixed stale Fragment Storage values when `/efs/video.properties` is inaccessible: a readable Android Settings mirror may now refresh previously-known values instead of only filling them once.
- Fixed fallback/protocol merging so Fragment Storage revisions remain monotonic and a stale protocol response cannot overwrite a current local Camera source.
- Removed the incorrect assumption that `/DCIM/Videos/Stitched` means the Camera is in Stitched mode; Google Street View uses the same directory.
- Added a Camera mode hint based on the exact mode-specific Fragment Storage property that changes (`video`, `video_fishEye`, `video_streetView`, `video_timeLapse`).
- Fragment Storage dashboard/live payloads now include `modeSource` and `modeUpdatedAt`; all per-mode values remain available even when the stock Camera exposes no safe idle-mode signal.

## 0.5.38
- Added an explicit Client connection-settings handshake: `/api/v1/dashboard?syncCameraSettings=1` forces a synchronous re-read of Camera 5.18.11 `/efs/video.properties` before the first dashboard JSON is returned.
- The initial Client frame therefore receives the current Fragment Storage selector instead of waiting for the normal 750 ms polling window or reusing a pre-connection cache.
- The forced sync reads all persisted video families (Stitched, FishEye/Unstitched, Google Street View and Time Lapse); periodic full/live dashboard polls retain the existing throttled readers.
- Added `fragmentStorage.connectionSynced` to the handshake response for diagnostics; the API remains additive and stays at version 3.

## 0.5.37
- Fragment Storage now publishes the selected Camera raw value plus structured `limitType`, `sizeGb`, and `durationMinutes` fields, so the Client does not have to infer the segment limit from a presentation string.
- Added compatibility reads for the exact Camera 5.18.11 property keys (`video.storagePart.value/.able` and mode equivalents) through both `camera.getOptions` aliases and read-only Android Settings mirrors when direct `/efs/video.properties` access is unavailable.
- Added a device-uptime process marker so a Client can distinguish a genuine Main-App restart from a stale lower Fragment Storage revision.

## 0.5.36
- Replaced Fragment-Storage-dependent transfer release with the requested Recording-folder MP4 sequence policy. Monitoring captures a temporary baseline list of all MP4 files when it starts.
- The first MP4 created after that baseline becomes active file A and is always protected from processing/move. When a distinct finalized MP4 B appears, A is released into the normal GPS validation/output pipeline; C releases B, and so on while Camera recording continues.
- MP4 CREATE/MOVED_TO event order is used as the fast path; the existing 5-second directory scanner is a fallback if FileObserver misses an event. Temporary `.part`/`.tmp` events may trigger a rescan but do not themselves release a predecessor.
- A released predecessor must remain size/mtime-stable for the short 2-second completed-file guard and is snapshot-checked again before/after CAMM parsing. If Pilot is still writing final metadata, the predecessor uses a short re-settle/retry instead of the generic 30-second retry backoff. The current newest MP4 can never be parsed/moved by the generic stable-file path.
- Final Camera `addFile` is used only to release the last active MP4 at overall recording stop, because that final file has no successor. A fresh MP4 baseline is then captured for the next recording.
- Fragment Storage display/collection remains unchanged in this release and is not used for file-moving decisions.

## 0.5.35
- Re-inspected the supplied Camera 5.18.11 APK and fixed the real Divider lifecycle: its internal fragment restart emits the same `com.pi.pilot.gallery.fileChange` callback as the first recording start. A repeated callback while Fragment Storage is enabled now releases the previous exact fragment immediately instead of incorrectly starting a new Camera generation.
- Rolling transfer no longer waits for final overall-stop `addFile`: the stock Divider has already executed the predecessor's low-level stop before the repeated `fileChange`, so that predecessor enters the short completed-fragment processing path while the next fragment remains Recording/protected.
- Added a direct `FileObserver` on `/efs/video.properties`. Camera 5.18.11 writes this file synchronously when `StoragePartModel` changes; its UI notification is only an in-process EventBus event and cannot be relied on cross-app.
- Fragment Storage polling now uses elapsed realtime at 750 ms as a fallback, avoiding stale values after Pilot system-clock corrections.
- Fragment Storage now carries a monotonic revision in dashboard/live-status so the paired Client can apply 4 GB -> 6 GB/etc. changes without wall-clock ordering errors.

## 0.5.34
- Ground Fragment Storage in the supplied stock Camera 5.18.11 APK: read `/efs/video.properties` (`video.storagePart.able/value`) instead of assuming `camera.getOptions` support.
- Release a settled predecessor when the next fragment is proven active, matching Camera 5.18.11's real low-level stop-then-restart Divider sequence; do not wait for final-overall-stop `addFile`.
- Remove the generic MP4 structural preflight as a blocker for a filesystem-proven Divider predecessor while retaining immutable size/mtime checks before processing/move.
- For the default Stitched folder, publish the exact stitched setting rather than mixing it with Street View's independent option.

## 0.5.33
- Fixed the remaining mid-record-start rollover hole: if Monitoring begins while fragment A is already being written and the first concrete writer event observed is fragment B, the engine now bootstraps A as B's predecessor instead of losing the A→B rollover until final Stop.
- Fragment transfer eligibility is now independently proven from the Recording-folder filesystem. A next distinct MP4 writer may release only the previous MP4 after successor proof, a 2.5-second previous-file quiet period, and structural MP4 readiness. `camera.getOptions`, Camera `addFile`, and recording-status ownership are not required for this release path.
- Temporary `.mp4.part` / `.mp4.tmp` successors participate in rollover detection; a CREATE-only successor must persist for a bounded proof window before it can release the previous finalized MP4.
- A filesystem-proven completed fragment bypasses only stale Camera ownership for that exact stem; the new/current fragment remains protected. The processing engine is woken immediately when rollover is proven.
- Hardened Fragment Storage collection: Stitched is queried first, partial responses are filled with individual requests, protocol `inProgress` commands are polled to completion, and a direct read timeout is no longer misclassified as an unreachable Camera (so the documented session fallback is still attempted).
- Added read-only Android Settings-provider fallback for exact documented Fragment Storage option keys on Pilot OS builds that mirror them locally; it never overwrites a concrete protocol/rollover value.
- A structurally proven fragment near a supported 4/6/8/10 GB or 10/30/60/120-minute boundary can publish an explicitly labeled observed value such as `4 GB (observed)` if the control API remains unavailable.
- Fixed the status-only `RecordingFileObserver` callback to match its `(event, file)` signature after the rollover event plumbing change.

## 0.5.31
- Added read-only Pilot Camera Fragment Storage collection through the documented `camera.getOptions` control-protocol options for Stitched, Unstitched/FishEye, Street View and Time Lapse video. The Camera-provided support table is used to preserve its user-facing label; an empty current value is exposed as `Off (Unlimited)`.
- Added additive `fragmentStorage` data to both full dashboard and `/api/v1/live-status`, including current/display values and per-mode details for the companion Client.
- Fragment rollover is now per-file: the currently open segment remains protected, while a completed previous segment is released after Camera `addFile` or a validated next-segment rollover and can generate GPX/move/report while the next segment keeps recording.
- A segmented `addFile` no longer forces the Client to Ready between fragments. The overall Camera lifecycle remains Recording across rollovers and uses a bounded continuation grace only to distinguish the final fragment from an immediate next fragment.
- Added a completion-confirmed fallback for temporary `camera.getOptions` outages so a real `addFile` plus next distinct MP4 can still release fragments instead of blocking everything until final Stop. A known `Off (Unlimited)` setting keeps the ordinary one-file stop lifecycle.
- Hardened fragment-vs-new-recording ordering: a newer Camera `fileChange` generation can never be stolen by the fragment handoff path.
- `START MONITORING` now remains visibly `START MONITORING` (disabled while starting) until Recording and Output folders pass real create/write/read/delete probes, OUTPUT report files are prepared, and the monitor engine plus FileObserver have initialized successfully. Only then does the button change to `STOP MONITORING`.

## 0.5.30
- Decoupled Pilot Camera capture state from output-transfer activity. Once a Pilot Camera lifecycle has been observed, MP4 finalization/copy/delete filesystem events can no longer resurrect `Recording` after the matching completion.
- Added a monotonic Camera lifecycle generation to dashboard/live-status payloads for paired Client ordering protection.
- Fixed a back-to-back-recording race where a new `fileChange` could assign the new generation to the previous MP4 that was still finalizing.
- Processing ownership is now decided per file: the exact active Camera-owned video is protected, while an older completed video is allowed to process/move even when a newer recording is already active.
- Preserved filesystem fallback for the case where the Main App process starts in the middle of an already-running recording, before any Camera lifecycle broadcast has been seen.

## 0.5.29
- Reworked Pilot Camera recording status as a synchronized per-video lifecycle: a newer start supersedes an older latch, and delayed completion for the previous video cannot clear the current recording.
- MP4 writer-close and IMU-close events no longer count as Camera stop signals; matching Camera `addFile` is the completion signal for the latched video.
- The live status FileObserver now includes Camera temporary `.part` / `.tmp` video aliases so a valid start hint is not lost between high-frequency `/live-status` polls.
- Processing ownership matching normalizes temporary writer aliases to the finalized video identity, preventing a rename from bypassing the active-recording safety gate.
- Camera-completed videos use a 2-second settling gate plus readiness checks instead of the generic 30-second stability wait.
- Removed full-destination SHA-256 rereads from large MP4 transfer verification; local copies use bounded samples and SAF copies use bounded size verification.
- Ensures all three OUTPUT-root TXT reports exist before committing each completed transaction.

## 0.5.28
- Fixed Pilot One recording status dropping to Ready after about one second while capture is still running.
- Camera start lifecycle now remains authoritative until a strong stop signal (video writer close, IMU close, or Camera `addFile`) is observed.
- Filesystem-only fallback uses a 15-second non-sticky activity window so temporary MP4 write gaps cannot permanently suppress Recording.

## 0.5.27
- Full recording/data-flow audit with Client 1.10.12.
- Added lightweight `/api/v1/live-status` for high-frequency recording, Monitoring, OUTPUT-folder and transfer state without rebuilding report/storage/GNSS diagnostics several times per second.
- Added a status-only Recording-folder FileObserver that remains active while Wi-Fi access is serving the Client even when processing Monitoring is OFF.
- Client-visible capture stop is now sticky for the current video after writer close, optional `.imu` close, Camera completion, or 1 second of write inactivity; later MP4 finalization writes cannot resurrect `Recording`.
- Kept the processing safety gate separate and conservative until Camera ownership is released; MP4 stability/readiness checks are unchanged.
- Camera `addFile` now removes stale active-file ownership observations immediately instead of waiting for the filesystem-event timeout.
- Tightened Camera `fileChange` association so an unrelated photo event can never reopen an MP4 already marked capture-stopped; only a genuine new-file `CREATE` may clear that stopped latch.
- Preserved capture-stop state when the processing monitor/FileObserver is restarted.
- API remains v3 and the full `/api/v1/dashboard` is backward compatible.

## 0.5.26
- Fixed the Client-visible Pilot One Recording Status remaining `Recording` for roughly the whole Camera MP4-finalization delay after capture stopped.
- Split recording state into two purposes: a fast **capture/display state** and the existing conservative **Camera file-ownership state** used by the processing engine.
- The dashboard now reports `cameraRecording.recording` from live MP4 write activity, with a 2.5-second display-only idle threshold and strong MP4 close/delete/move-away hints.
- Added additive `cameraRecording.finalizing` so newer clients can distinguish capture stopped from Camera still finalizing the MP4.
- The processing engine still uses the conservative Pilot Camera broadcast latch, the 30-second stable-file period and MP4 readiness checks; this change cannot make a recording move early.
- A temporary write stall can recover automatically: if MP4 writes resume, the display state returns to `Recording`.
- Dashboard API remains v3 and Client 1.10.11 remains compatible; no Client update is required.

## 0.5.25
- Added additive `deviceDiagnostics` data to dashboard API v3 for Bluetooth, active Android location source and GNSS signal status.
- Reports connected Bluetooth devices using system/GATT state plus low-level ACL observations; likely GPS/GNSS devices are marked for Client display.
- Reports Bluetooth RSSI only when Android has passively exposed a recent RSSI observation; the app never starts discovery or opens another Bluetooth connection merely to measure signal strength.
- Added passive location-source observation with mock-location detection and internal/system/external-inferred classification.
- Added Android `GnssStatus` satellite counts, satellites used in fix, average/max C/N0, constellation counts and time-to-first-fix.
- Diagnostics are fail-isolated: a diagnostics failure cannot break the existing dashboard/Client connection.
- Dashboard API remains v3 so older Client versions continue to connect and ignore the additive field.

## 0.5.24
- OUTPUT folder changes are persisted immediately when selected; restarting Monitoring is no longer required.
- Dashboard now exposes a dedicated live `outputFolder` field read fresh on every request.
- New OUTPUT destinations are write-checked and the three global report files are prepared immediately.
- Active transfers continue safely in the destination captured when that transaction began; subsequent files use the new OUTPUT folder.

# 0.5.23 - Lean / Stable Build Configuration

- Runtime behavior is unchanged from 0.5.22.
- Reduced `compileSdk` from API 35 to API 28, matching the app's existing `targetSdk = 28`.
- Pinned Android SDK Build Tools to 30.0.3.
- Reduced Android Gradle Plugin from 8.7.3 to 7.4.2.
- Reduced Gradle wrapper from 8.9 to 7.6.4.
- Reduced Kotlin Gradle plugin from 1.9.24 to 1.7.22.
- Enabled Gradle build cache, daemon, file-system watching, and Kotlin incremental compilation.
- GitHub Actions now installs only Android API 28 + Build Tools 30.0.3 for the app build.
- No application dependency was added.

# 0.5.22

- Fixed upgrade migration from the accidentally saved `/sdcard/DCIM/Videos/Stichted` default to `/sdcard/DCIM/Videos/Stitched` for both Recording and OUTPUT preferences.
- START MONITORING now uses the live service state instead of stale status text, preventing a START press from becoming an unexpected STOP.
- START MONITORING now performs an OUTPUT report preflight and creates/verifies `GOOD.TXT`, `FAILED.TXT` and `ERROR.TXT` before enabling the monitor service.
- Added additive dashboard diagnostics for actual Monitoring service state and report destination/file health.
- Report read failures are returned as explicit health errors instead of looking like valid empty `(0)` reports.
- Wi-Fi API request failures now return a bounded HTTP 500 JSON error instead of silently closing the socket.
- Pilot Camera broadcasts can accelerate an already-running monitor but can never start Monitoring from a stopped state.
- Bounded cumulative-report tail reads / recovery-marker checks to avoid progressively slower report operations as global TXT files grow.
- Legacy pending-GPX import is attempted once instead of rescanning permanent report logs every client poll.
- Boot receiver is no longer exported. Fresh process startup remains Monitoring OFF and Wi-Fi OFF.

# 0.5.21

- Changed the default/reset OUTPUT folder to `/sdcard/DCIM/Videos/Stitched`.
- Existing installs using the previous default `/storage/emulated/0/videos/stitched` are migrated automatically when no SAF output tree is selected.
- Explicitly selected custom OUTPUT folders remain unchanged.
- No other 0.5.20 processing, reporting, startup, or recording-status behavior changed.

# 0.5.20

- Corrected the default/reset Recording path spelling to `/sdcard/DCIM/Videos/Stitched`.
- No other 0.5.19 behavior changed.

# 0.5.19

- Default/reset Recording path changed to `/sdcard/DCIM/Videos/Stitched`.
- Restored dated OUTPUT media folders (`dd-mm-yyyy`) while keeping only global GOOD/FAILED/ERROR TXT reports at OUTPUT root.
- Moved cumulative report storage and dashboard reads from Recording to OUTPUT.
- Added Pilot-recording gate and safe stale zero-byte MP4 cleanup to prevent active-file moves.
- Fresh startup, boot and app replacement always leave Monitoring and Wi-Fi file access stopped.

# 0.5.18

- Fixed a regression where opening/recreating MainActivity forcibly stopped Recording Monitoring even though Pilot recording-status broadcasts continued to work.
- Monitoring enabled state now represents persistent user intent and is restored after process recreation, boot, and app replacement; Wi-Fi keeps its existing explicit-start behavior after reboot/update.
- Normal RecordingMonitorService destruction no longer clears the monitoring-enabled preference; explicit STOP and terminal startup failure still do.
- Pilot Camera file-change/completed-video broadcasts now wake the enabled monitor and request an immediate Recording-folder rescan.
- A completed Pilot MP4 is directly signaled to the processing engine when it belongs to the selected Recording folder; paths outside that folder are logged but never moved implicitly.
- Existing 5-second periodic Recording-folder scan and FileObserver remain active and independent of recording-status detection.

# 0.5.17

- Fixed Pilot One recording status by using Labpano Camera 5.18.x gallery broadcasts as the primary cross-app signal.
- Verifies the Camera record-start hint against fresh video files so photo captures do not create false RECORDING status.
- Added scanning of PilotSDK default stitched/unstitched paths under `DCIM/Videos` plus the user-configured recording folder.
- Keeps FileObserver / MP4 growth detection as a fallback for firmware variants.

# 0.5.15

- Added additive `cameraRecording` dashboard status for the companion Client App.
- Recording state is derived from actual MP4 CREATE/MODIFY/CLOSE_WRITE activity in the configured recording folder.
- Added recent-file activity fallback so a recording already in progress when the monitor starts becomes visible.
- Recently closed MP4 files are suppressed from fallback detection so status returns to NOT RECORDING promptly after finalization.

# 0.5.14

- Completed MP4/GPX files now move directly into the selected OUTPUT root; no dated/status media subfolders are created for new transfers.
- When the Monitoring and Output selections resolve to the same physical folder, day-specific TXT report folders are suppressed so only the cumulative `GOOD.TXT`, `FAILED.TXT` and `ERROR.TXT` files remain alongside media.
- Daily monitoring report folders are preserved when Monitoring and Output are separate locations.
- Added an in-place completion path for the default shared `videos/stitched` folder so a file is never deleted as its own source after verification.
- Final processing markers are now retained while the corresponding source/output MP4 still exists, preventing old files kept in the monitored root from being reprocessed after retention pruning.
- Updated FAILED/ERROR status text to reflect flat output placement.

# 0.5.13

- Changed dated media subfolders from `<STATUS>_<DATE>` to `<DATE>_<STATUS>` (for example `09-08-2026_GOOD`).
- Added a configurable Wi-Fi file-server port under Advanced; default remains 1100.
- Added explicit port-in-use reporting so another app can safely use a different port such as 1200.


## 0.5.11

- Restyled both Recording and Output folder-selection buttons with the same raised 3D light-blue design.
- Added a darker lower edge, rounded outline, subtle elevation and a pressed push-down state for clearer tactile feedback.
- Kept folder-selection behavior and all monitoring/output logic unchanged.

## 0.5.10

- Made monitoring-root `GOOD.TXT`, `FAILED.TXT` and `ERROR.TXT` cumulative across all processing dates.
- Removed automatic size/line trimming from the cumulative monitoring reports so older records are retained.
- Added `monitoring/dd-MM-yyyy/GOOD.TXT`, `FAILED.TXT` and `ERROR.TXT` daily reports containing only that date's records.
- Kept dated media folders in the selected output location while moving dated TXT reporting into the monitoring folder.
- Preserved transaction-ID deduplication so crash recovery does not duplicate root or daily report entries.

## 0.5.9

- Added persistent MP4 destination-write failure monitoring for internal and external storage.
- Records failures while preparing the output folder, writing/finalizing MP4 files, and verifying completed MP4 copies.
- Exposes recent failures to the Client App through the additive `storageWriteAlerts` field in dashboard API v3.
- Write alerts are retained for seven days, bounded to 50 entries, and identical retry failures are deduplicated for five minutes.

## 0.5.8

- Replaced the nonstandard Java-21-only wrapper with a standard Java-compatible Gradle wrapper.
- Updated the build stack to Android Gradle Plugin 8.7.3, Gradle 8.9 and Java 17.
- Added one audited GitHub Actions debug-APK workflow.
- Fixed the missing `java.util.ArrayDeque` import in the dashboard API.
- Added unit tests for GPX timestamp output and dated output paths.
- Preserved startup failure status instead of immediately overwriting it with Idle.
- Ensured unexpected monitoring/Wi-Fi service termination clears stale running preferences.
- Extended the durable transfer journal to retain exact destination video/GPX paths.
- Increased the database schema version to migrate the new journal fields safely.
- Preserved real SAF document URIs in the transfer journal and pending-GPX queue.
- Added a queue-specific GPX download endpoint so the Client App can download backups from both direct filesystem output and SAF-authorized external storage.

## 0.5.7

- Added Street View MP4/GPX timeline synchronization and overlap validation.

## 0.5.6

- Added durable transfer journaling, bounded retries, quarantine routing and persistent pending-GPX queue.

## 0.5.16

- Hardened Pilot One recording detection with repeated MP4 size/mtime growth scanning in addition to `FileObserver` events; `CLOSE_WRITE` still stops the live status immediately when available.
- Added transfer-journal guards so a verified flat-output recording cannot start a second processing transaction while crash recovery is completing the first.
- Fixed large cumulative report entry deletion to stream rather than loading the entire TXT file into memory.
- Dashboard report polling now reads only the requested recent tail instead of walking every line of indefinitely growing global reports.
- Normal report commits no longer rescan the entire cumulative report for a transaction marker; the streaming duplicate scan is reserved for crash recovery.
- Strengthened existing output-file reuse with SHA-256 content verification, including SAF/document-tree destinations.
- Added SHA-256 read-back verification for new SAF/document-tree copies before source cleanup.
- Kept crash logs in app-private storage so shared OUTPUT remains limited to GOOD.TXT, FAILED.TXT and ERROR.TXT plus media/GPX files.
- Bumped the internal database schema to 7 to install the journal source-state index on existing installations.
- Full source audit performed together with Client App 1.9.8.
