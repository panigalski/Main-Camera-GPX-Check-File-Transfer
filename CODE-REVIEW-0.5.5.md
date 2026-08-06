# Labpano GPX Extractor 0.5.5 — Static Code Review

Reviewed project: `Labpano-GPX-Extractor-Main-App-0.5.5-Dated-Output-Framework`

Scope reviewed:

- Gradle configuration and Android manifest
- Main activity, folder selection and service lifecycle
- Recording monitor and processing engine
- MP4/CAMM parser and readiness checks
- GPX validation, densification and writing
- Local and SAF output transfer
- Rolling and dated reports
- SQLite duplicate-processing state
- Dashboard and pending-GPX APIs
- Wi-Fi HTTP file server
- Existing unit tests

Review type: static source review. The pure Kotlin MP4/GPX core compiled successfully with the local Kotlin compiler. A complete Android Gradle build could not run because the Gradle distribution was not cached and the environment could not reach `services.gradle.org`.

## Overall conclusion

Version 0.5.5 contains several good safety ideas—single-file processing, structural MP4 preflight, source snapshot checks, hidden temporary GPX files, local SHA-256 copy verification, path traversal protection, and serialized report access. However, it is not yet robust enough for unattended long-term operation with many recordings. The most serious risks are incomplete transaction recovery, endless retry loops, report loss, unbounded report/database growth, service lifecycle resets, and unauthenticated exposure of shared storage over Wi-Fi.

## Critical findings

### 1. Successful media transfer can be followed by report failure, leaving no recoverable queue entry

Files are moved and source files deleted before both report writes complete (`RecordingProcessingEngine.kt`, approximately lines 247–280). If either rolling or dated report append fails afterward, the catch path records an ERROR against a source file that no longer exists. The client pending-GPX queue depends on rolling reports, so a successfully moved GPX may never be advertised.

Recommended fix: introduce a durable transaction journal with states such as `PREPARED`, `COPIED`, `SOURCE_REMOVED`, `REPORTS_COMMITTED`, and recover incomplete transactions on service startup. Report appends must be idempotent and keyed by a stable transaction ID.

### 2. SAF report append can silently claim success without writing anything

`DatedOutputReportWriter.kt` lines 63–66 ignore the result of the nullable output stream expression and then always return `true`. If `openOutputStream(report, "wa")` returns `null`, the fallback rewrite is skipped and the report entry is silently lost.

Recommended fix: return `false` when the stream is null, verify the resulting document size, and use a provider-safe append strategy.

### 3. Permanent bad recordings are retried indefinitely

Incomplete or corrupt MP4 structures return to WAITING forever (`Mp4ReadinessChecker` plus `RecordingProcessingEngine.consume`). Parser, storage and transfer errors also return to WAITING without attempt limits or backoff. A permanent error can therefore be processed every stability cycle and append repeated ERROR records indefinitely.

Recommended fix: persist attempt count, first-seen time, last error and next retry time. Use exponential backoff and quarantine permanent errors after a defined number of attempts or age threshold.

### 4. The requested ERROR media folder is never used

The engine moves only GOOD and gap-warning FAILED pairs. `outputLayout.mediaSubfolder(ProcessingStatus.ERROR)` is never called. Validation failures are marked final but remain in the monitoring folder, while parser errors retry forever. The dated `ERROR_<date>` media folder is therefore not populated as designed.

Recommended fix: separate permanent media errors from retryable environmental errors. Add video-only or optional-pair quarantine transfer support for permanent ERROR results.

### 5. Validation failures become final while the source video remains stranded

`savePermanentFailure()` saves FAILED as a final result and removes the candidate, but does not move the MP4 or write the dated FAILED report. `ProcessedRecordingStore.hasFinalResult()` then prevents reprocessing the unchanged file on future service runs.

Recommended fix: either move the video to the dated ERROR/FAILED area before marking it final, or keep it retryable. Never mark a source final while leaving it unorganized without an explicit user-visible quarantine state.

### 6. Copy/delete operations are not crash-safe transactions

A process crash can occur after video copy, after GPX copy, after one source deletion, or after source deletion but before reports. Local `.part` recovery covers only part of this. SAF copies can leave orphan documents. There is no startup reconciliation.

Recommended fix: durable transfer journal, temporary destination names for both local and SAF output, atomic finalization where supported, and startup reconciliation of partial copies and cleanup-pending transactions.

### 7. Source deletion failure causes duplicate destination pairs on retry

The mover deletes GPX and video separately. If one deletion fails, verified destination files remain but the operation throws. A later retry chooses a numbered name and copies the same recording again.

Recommended fix: record `COPY_COMPLETE_CLEANUP_PENDING` and retry only source cleanup. Do not repeat a verified copy.

### 8. Rolling reports and SQLite state grow without limit

`GOOD.TXT`, `FAILED.TXT`, `ERROR.TXT`, `CRASH.TXT`, and `processed_recordings` have no pruning or rotation policy. The dashboard and pending queue scan rolling reports repeatedly. Long-term operation will gradually increase storage, I/O and latency.

Recommended fix: daily/size-based report rotation, bounded crash logs, database retention, indexes, and a dedicated queue table rather than deriving queue state from text reports.

### 9. Pending GPX queue drops older entries after 500 report lines

`buildPendingGpxQueue()` reads only the last 500 GOOD and FAILED entries. If the client is offline while more than 500 recordings are processed, older GPX files disappear from the API before they can be backed up.

Recommended fix: persistent queue table with unique IDs, acknowledgement state, pagination and retention only after acknowledgement.

### 10. Activity recreation stops both monitoring and Wi-Fi services

`MainActivity.onCreate()` always calls `resetRuntimeServicesToOff()`. Rotation, configuration changes, task recreation or Activity restoration can stop active services and reset the interface, even though the user did not press Stop.

Recommended fix: reset runtime state only once for a genuinely new process/session, not every Activity creation. Preserve UI state through saved state or a ViewModel-equivalent architecture suitable for this project.

### 11. Wi-Fi service uses `START_STICKY`, contradicting the explicit-start policy

The Wi-Fi service can be restarted by Android after process/service termination even though the UI and documentation say it must start only after the user presses the button.

Recommended fix: use `START_NOT_STICKY` and store a deliberate user-start session token if restart recovery is required.

### 12. Wi-Fi server exposes all shared storage without authentication

The server binds to `0.0.0.0`, exposes internal and removable shared-storage roots, permits file download/streaming, and allows report-entry deletion without authentication. Anyone on the reachable network can access camera files while the service is active. The server is not strictly read-only because the DELETE API modifies reports.

Recommended fix: restrict roots to configured output/report folders, add a per-session token or pairing code, bind only to the intended interface where possible, remove or authenticate DELETE, and update the documentation.

## High-priority correctness and efficiency findings

### 13. Dashboard polling becomes progressively slower

Each dashboard request scans each entire rolling report to retain only the last 500 lines. The global report lock blocks appends and deletions during each scan.

Recommended fix: query indexed database rows or maintain bounded in-memory/database-backed report summaries.

### 14. SAF report fallback is O(n²) and can exhaust memory

When append mode is unsupported, the code reads the whole report into a byte array and rewrites it for every entry. Daily reports with many records will become slow and memory-heavy.

Recommended fix: store report events in SQLite and export TXT snapshots, or write one event file per record and compact later.

### 15. Same-volume moves unnecessarily copy and hash multi-gigabyte videos

Local output always copies the video, hashes the source and destination, then deletes the source. On the same filesystem, an atomic rename can be far faster and reduce heat and flash wear.

Recommended fix: attempt atomic rename/move for same-volume destinations, with verified copy fallback across volumes.

### 16. SAF copy does not validate an immutable source snapshot

Local copies capture and recheck source length and modification time. SAF copies compare bytes written with repeated calls to `source.length()` but do not retain and assert a fixed snapshot. Another application can still modify the source during transfer.

Recommended fix: use the same `SourceSnapshot` logic for SAF transfers and validate before source deletion.

### 17. Wi-Fi downloads can block processing indefinitely

`sendFile()` holds the file read lock for the entire network transfer. A slow or stalled receiver can hold the lock while the processing engine waits to move the file. Socket read timeout does not impose a reliable write timeout.

Recommended fix: avoid holding the app-level lock for the complete network send. Open a stable file descriptor/snapshot under lock, release the lock, then stream with bounded connection time and cancellation.

### 18. HTTP server has unbounded request and connection queues

Request lines and headers have no size/count limit. The fixed thread pool uses an unbounded work queue. Several slow clients can occupy all workers; many accepted connections can accumulate in memory.

Recommended fix: bounded executor queue, rejection handling, maximum request-line/header sizes, connection count limits and stricter timeouts.

### 19. Server socket reuse is configured after binding

`ServerSocket(PORT, ...)` binds immediately, then `reuseAddress` is set. This may not help quick restarts after a failure.

Recommended fix: create an unbound `ServerSocket`, set options, then bind.

### 20. Report rewrite deletion is not fully atomic

Dashboard deletion writes a temporary file, then attempts rename over the existing report and falls back to overwrite-copy. A failure during fallback can damage the original report.

Recommended fix: fsync temporary content, keep a backup, use an atomic replace primitive where available, and verify the final file before deleting backup.

### 21. Storage lock registry never evicts entries

`StorageAccessCoordinator` retains one lock for every canonical path ever seen. With many unique recordings this map grows indefinitely.

Recommended fix: reference-count lock entries and remove unused locks, or use a bounded striped-lock implementation.

### 22. Stability timing uses wall-clock time

Candidate stability uses `System.currentTimeMillis()`. Clock correction can cause premature processing or unexpectedly long waiting.

Recommended fix: use `SystemClock.elapsedRealtime()` for elapsed durations and wall-clock only for report timestamps/date folders.

### 23. Date formatter is a shared non-thread-safe `SimpleDateFormat`

`DatedOutputLayout` stores a shared formatter in its companion object. It is currently usually reached from one consumer, but future API or parallel processing changes can corrupt results.

Recommended fix: use `java.time` on supported/desugared builds or synchronize/create the formatter per call.

### 24. Densification silently truncates after the point cap

When `maximumOutputPoints` is reached, later genuine points can be omitted without an error. An abnormal timestamp span can also cause a large upfront allocation.

Recommended fix: never discard genuine points. Reduce interpolation density dynamically, validate timestamp span, and report when interpolation is limited.

### 25. Longitude interpolation is incorrect across the anti-meridian

Linear interpolation from +179° to −179° travels through 0°, creating an artificial route around most of the world.

Recommended fix: interpolate longitude using wrapped angular distance.

### 26. Gap validation ignores missing GPS at the beginning and end of a video

The validator checks only intervals between extracted points. A recording with GPS only in the middle can still be marked GOOD.

Recommended fix: return track/video timing metadata from the parser and validate start coverage, end coverage, minimum point count and total GPS coverage.

### 27. Dense GPX points are synthetic, not additional extracted CAMM fixes

The current 250 ms densifier increases output count by interpolation. It does not demonstrate that all recorded GPS samples were extracted. If another tool finds genuinely distinct samples, parser/sample-table behavior must be compared directly.

Recommended fix: add diagnostics that report total CAMM samples, packet type counts, decoded GPS fixes, skipped malformed packets and interpolation count separately.

### 28. Report format is not safely escaped

Tab-separated lines and semicolon-separated message fields can be corrupted by filenames or paths containing tabs, newlines or semicolons.

Recommended fix: use JSON Lines or a normalized SQLite event table; generate human-readable TXT as a derived export.

### 29. Core state cleanup is incomplete

`ProcessedRecordingStore` is not closed when the engine stops. If engine startup throws before assignment to the service field, its executors and database helper may remain alive until process death.

Recommended fix: make the engine `Closeable`, roll back `running` on startup failure, assign before start or close in the catch block, and await executor termination.

### 30. Stop is not fully cooperative

`shutdownNow()` interrupts threads, but parsing, hashing, copying and SAF I/O do not consistently check cancellation. Processing may continue after the user presses Stop.

Recommended fix: cancellation token checks at each stage and inside copy loops, followed by bounded executor termination waiting.

## Medium-priority findings

- Existing local GPX is deleted before the replacement rename succeeds.
- Output collision checks assume `.mp4` rather than the exact original extension/case.
- Old persisted SAF grants are not released when the output folder changes.
- Canonical-path comparisons in the UI can throw and crash the main thread.
- Folder probes and SAF operations run synchronously on the UI thread.
- Directory browsing/sorting runs on the UI thread and may stall in very large folders.
- Hidden and partial files can appear in Wi-Fi directory listings.
- Custom broadcasts are unprotected and can be spoofed by another installed application.
- `LOCKED_BOOT_COMPLETED` is declared without direct-boot-aware storage handling.
- API version values are inconsistent between dashboard, health and pending-GPX endpoints.
- `StableFileDetector` and `FILE_STABILITY_CHECK_DELAY_MS` are unused and should be removed or integrated.
- Test coverage is limited to one gap test, two packet tests and one box-header test.

## Existing strengths

- Heavy MP4 parsing and transfer work is off the Android main thread.
- A single consumer avoids concurrent parser and disk workloads.
- FileObserver is treated only as a hint; periodic scanning provides recovery.
- MP4 structure is checked before CAMM parsing.
- Source size and modification time are rechecked around parsing.
- Local copies use temporary files, fsync and SHA-256 verification.
- Local path traversal protection uses canonical paths.
- Report writes and rewrites are serialized within the process.
- GPX XML escaping is implemented for track names.
- The parser core compiled successfully with the local Kotlin compiler.

## Recommended hardening order

1. Durable processing/transfer transaction journal and startup recovery.
2. Retry policy with backoff, attempt limits and permanent-error quarantine.
3. Correct GOOD/FAILED/ERROR media routing, including video-only ERROR handling.
4. Replace report-derived pending queue with a persistent acknowledged queue.
5. Fix SAF report append and transactional report commits.
6. Fix Activity/service lifecycle and change Wi-Fi to non-sticky startup.
7. Restrict and authenticate Wi-Fi access.
8. Add report/database retention and bounded lock storage.
9. Improve GPX coverage validation and densifier behavior.
10. Add integration tests for crash points, copy failures, SAF providers, service recreation and long-running scale.

## Minimum test matrix before unattended deployment

- Process kill at every transfer stage and restart recovery.
- Full output volume during copy and during report append.
- Source deletion denied after successful copy.
- SD card removed during video copy, GPX copy, verification and report write.
- Corrupt/truncated MP4 that never becomes complete.
- MP4 with no GPS, one point, duplicate timestamps, bad coordinates and start/end GPS loss.
- More than 500 recordings while the client is offline.
- More than 10,000 report entries and database rows.
- Activity rotation/recreation while monitoring and Wi-Fi are active.
- Slow/stalled Wi-Fi download while a file is ready to move.
- Repeated server stop/start on port 1100.
- Filenames containing spaces, Unicode, semicolons, tabs and very long names.
