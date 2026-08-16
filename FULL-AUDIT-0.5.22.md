# Main Camera App 0.5.22 — deep audit

## Scope

Audited the 0.5.21 source end-to-end with emphasis on the reported symptom: global `GOOD.TXT`, `FAILED.TXT`, `ERROR.TXT` are not created/updated and the Client receives no report entries. The review also covered startup/service lifecycle, Recording/OUTPUT path persistence, MP4 readiness, Pilot recording gating, CAMM/GPX processing, transfer verification, transaction recovery, SQLite schema, report persistence, HTTP dashboard/report delivery, Wi-Fi lifecycle, crash logging and long-run behavior with cumulative logs.

## Confirmed root causes fixed

### Critical — legacy typo preference can monitor the wrong folder
0.5.19 could persist `/sdcard/DCIM/Videos/Stichted`. Later releases corrected the source default but did not migrate that stored preference. An upgraded camera could therefore display/use an empty misspelled Recording folder indefinitely. With no discovered MP4, no processing transaction exists and no GOOD/FAILED/ERROR entry can be generated.

**Fix:** `PathMigrationPolicy` now migrates the typo (including the `/storage/emulated/0/.../Stichted` alias) and the older `/storage/emulated/0/videos/stitched` default to `/sdcard/DCIM/Videos/Stitched`, for both Recording and OUTPUT. Explicit custom folders are preserved.

### High — START/STOP decision used stale status text
The 0.5.21 UI decided whether START MONITORING should start or stop by reading persisted `lastStatus`. A prior failure or stale non-idle status could make a user press labelled START execute the STOP path.

**Fix:** the in-process `RecordingMonitorService` state is authoritative for the button action. A stale preference/status can no longer convert START into STOP.

### High — report creation failure happened asynchronously after the UI claimed Monitoring started
The engine creates report files, but in 0.5.21 this happened only after the service was launched. A permission/path/report problem could therefore fail asynchronously, after the Activity already displayed “Monitoring started”.

**Fix:** before setting Monitoring enabled or launching the service, the Activity now performs real Recording/OUTPUT read-write probes and explicitly creates/verifies `GOOD.TXT`, `FAILED.TXT` and `ERROR.TXT`. Monitoring is not started if this preflight fails.

### High — missing/unreadable reports were indistinguishable from legitimately empty reports
The dashboard returned empty arrays when local report files did not exist, and a report I/O exception could terminate a request. On the Client this looked like `(0)` and gave no way to tell whether there were truly no records or the report pipeline was broken.

**Fix:** dashboard API v3 now adds backward-compatible `monitoring` and `reportHealth` objects. They expose requested/live service state, last monitor status, report destination/type, each report's exists/readable/writable/size state, and the latest report I/O failure. A failed report read is preserved for that dashboard poll rather than being hidden by a later successful read.

### Medium — Pilot broadcast could resurrect Monitoring contrary to manual-only policy
A stale `monitoring_enabled=true` preference allowed an exported Pilot media broadcast to start the monitor service even after the monitor process/session was gone.

**Fix:** Pilot broadcasts are acceleration hints only. They may request a rescan only when the monitor service is already live in this process. They can never start Monitoring. `LabpanoApplication`, `MainActivity` cold startup and the boot/update policy keep Monitoring and Wi-Fi disabled until explicit user action.

### Medium — report/API failures could close HTTP sockets without a useful response
An exception in a routed Wi-Fi API request could leave the Client with a generic transport failure.

**Fix:** routed request failures are logged and return a bounded HTTP 500 JSON error where possible. The Client already reads the JSON `message` from error responses.

## Data integrity review

- Processing is non-recursive and scans only root-level `.mp4` files in the selected Recording folder. OUTPUT `dd-mm-yyyy/` folders are therefore not reprocessed when Recording and OUTPUT share the same root.
- An active Pilot recording is gated twice: before queueing and immediately before readiness/CAMM processing. A file identified as the active recording is never parsed, moved or deleted.
- Zero-byte MP4 cleanup requires a two-minute unchanged interval and Pilot NOT RECORDING state; cleanup is logged as ERROR.
- MP4 readiness requires finalized ISO-BMFF structure before CAMM extraction.
- MP4/GPX transfers are verified before source cleanup. Existing same-name files are reusable only after content equality checks, not merely matching file size.
- A durable transfer journal is persisted before source deletion. Crash recovery completes report/queue/database commit without recopied media.
- Recovery checks pending source+metadata using an indexed transfer-journal lookup.
- Crash diagnostics are stored app-private; `CRASH.TXT` is not added to OUTPUT.
- Dated OUTPUT folders contain media/GPX only; global TXT reports remain at the OUTPUT root.

## Long-run scalability fixes

- Local dashboard report reads use a reverse tail reader instead of walking the complete cumulative TXT on every Client poll.
- Crash-recovery transaction-marker lookup is limited to a recent 4 MiB tail, where an uncommitted transaction marker can realistically reside.
- Seekable SAF reports use an 8 MiB recent tail for dashboard reads; a full-stream fallback remains only for providers that cannot seek/stat.
- Legacy GOOD/FAILED import into the pending-GPX queue is attempted once rather than being repeated every 3-second Client poll when the queue is empty.
- Report entry deletion rewrites by streaming and does not load the full permanent report into memory.

## Remaining risks / design constraints

### Medium — unauthenticated LAN API
The Wi-Fi service is plain HTTP and does not authenticate clients. Anyone on the same reachable LAN who knows the camera address/port can read dashboard/storage endpoints and can call report-entry deletion. Use a private vehicle Wi-Fi network. A future shared-secret/PIN is recommended if the network is not trusted.

### Low/medium — exported Pilot media receiver
`PilotCameraBroadcastReceiver` must be exported to receive Labpano Camera broadcasts. Another app on the Pilot could spoof those broadcasts. The receiver cannot start Monitoring in 0.5.22 and processing remains constrained to the configured Recording folder, limiting impact, but a signature/permission-based Labpano signal would be preferable if available.

### Low — SAF provider performance fallback
For a non-seekable third-party SAF provider, report tail/marker recovery may fall back to streaming a large cumulative report. The default Pilot internal filesystem path does not use this fallback.

### Informational — Client receives a recent window, not the entire global log
The permanent camera TXT files remain unlimited/cumulative. The dashboard intentionally returns at most the most recent 500 entries per report to bound a 3-second polling response. This does not delete older records.

### Informational — folder changes while Monitoring is live
Local folder picker choices are staged in the Activity and take effect when Monitoring is next started. For predictable operation, stop Monitoring before changing Recording/OUTPUT.

## Validation performed

- Pure Kotlin compilation of CAMM/ISO-BMFF/GPX/output-layout source succeeded.
- `PathMigrationPolicy` runtime checks passed for the `Stichted` typo, legacy path and custom-path preservation.
- `ReportTailReader` recent-tail marker and last-lines runtime checks passed.
- `GlobalOutputReportStore` compiled against Android API stubs and a local runtime harness created all three global reports, appended GOOD/FAILED records, tailed them and returned healthy file metadata.
- `DashboardApi` compiled against focused Android/JSON stubs after the diagnostics changes.
- `PilotCameraBroadcastReceiver` compiled against focused Android stubs after the manual-only lifecycle change.
- Android manifests parse as valid XML and Kotlin source brace sanity checks passed.
- Full Gradle tests/build were attempted but the wrapper cannot download Gradle 8.9 because this sandbox cannot resolve `services.gradle.org`. This is an environment limitation, not a passing full Android build claim.
