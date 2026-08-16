# Labpano GPX Extractor / Camera App 0.5.16 — Full Source Audit

Audit date: 2026-08-09
Companion Client: 1.9.8

## Scope

Reviewed all 34 production Kotlin files (~6,350 lines), 11 JVM test files, Android manifest/resources, Gradle configuration, recording observation, MP4 readiness/CAMM parsing, GPX generation, flat output transfers, SAF/document-tree output, transfer journaling/recovery, cumulative reports, dashboard API, local Wi-Fi file server, storage alerts, boot/lifecycle behavior and crash logging.

## Recording-status fault and fix

The 0.5.15 recording state relied heavily on `FileObserver` events plus a very short recent-mtime fallback. Vendor Android builds may omit or delay MODIFY/CLOSE_WRITE events for files written by another camera process, causing the dashboard to keep reporting NOT RECORDING even while an MP4 is growing.

**0.5.16 uses a hybrid detector:**

- consumes CREATE/MODIFY/CLOSE_WRITE/MOVE/DELETE events when Android delivers them;
- independently scans MP4 length + modification time on dashboard polls;
- marks a file recording when it is observed growing/changing;
- clears immediately on CLOSE_WRITE when delivered;
- otherwise lets recording state expire after 12 seconds without observed activity;
- suppresses recently closed files so a completed MP4 is not immediately rediscovered as active.

A focused Kotlin smoke test passed first-seen, growth detection, CLOSE_WRITE stop and old-stable-file cases.

## Audit findings fixed

### High — same-size existing output could be mistaken for the source
Existing destination reuse was based on name/size. Two different files can have the same size; in that case source cleanup could have occurred after accepting the wrong file.

**Fix:** exact-name reuse now requires SHA-256 content equality for both filesystem and SAF/document-tree destinations.

### High — SAF copy verification previously relied primarily on size/provider metadata
A successful byte count is useful but does not prove the destination content matches the source, especially with removable media/provider failures.

**Fix:** new SAF copies calculate SHA-256 while reading the source, reopen the destination, calculate its SHA-256 and compare before finalization/source cleanup.

### High — journaled flat-output transfer could race a normal retry
With Recording and Output pointing to the same flat directory, a commit failure after a verified/journaled transfer could leave the MP4 present. A normal retry could race crash recovery and begin another transaction.

**Fix:** processing now checks for an existing non-committed transfer-journal entry for the exact source path/size/mtime both before queueing and before processing. Recovery exclusively owns a journaled transfer until it commits. Database schema 7 adds a supporting source/state index on existing installations.

### Medium — cumulative report deletion could consume excessive memory
Deleting one dashboard report entry used to materialize an entire indefinitely growing TXT report in memory.

**Fix:** rewrite is now streamed line-by-line through a temporary file, fsynced and replacement-verified.

### Medium — dashboard polling walked entire cumulative logs every few seconds
The Client only needs the latest 500 entries, but the Camera App could scan all historical lines on each dashboard request. Cost would grow continuously over months/years.

**Fix:** `ReportTailReader` reads backward in bounded chunks and returns only the requested recent tail. A 2,000-line Unicode smoke test passed; a dedicated JVM test was added.

### Medium — normal report append did a full duplicate-marker scan
Transaction-marker deduplication is only required for crash/recovery replay, but normal commits also scanned the whole cumulative report.

**Fix:** new transactions append without the historical scan. Recovery transactions still perform the streaming marker check, preserving idempotence after a crash.

### Medium — shared output could receive `CRASH.TXT`
The global-output rule requires only GOOD.TXT, FAILED.TXT and ERROR.TXT alongside media/GPX files. Crash diagnostics could add a fourth TXT file.

**Fix:** crash logs now stay in app-private storage (`filesDir/CRASH.TXT`).

## Existing safeguards confirmed

- MP4 must remain stable and pass ISO-BMFF readiness before processing.
- CAMM GPS is validated and timeline overlap is checked.
- GPX is written/validated before transfer finalization.
- Transfer journal is persisted before source cleanup.
- Same-directory Recording/Output uses in-place adoption and never deletes a file as its own destination.
- Final-result records are retained while the source/output MP4 remains, preventing old flat-output recordings from being reprocessed after retention pruning.
- GOOD/FAILED/ERROR global reports remain cumulative by design.
- Daily monitoring TXT folders are suppressed when Monitoring and Output are the same folder.
- Wi-Fi server port is configurable and defaults to 1100, so another app can independently use 1200.
- Services are not exported.

## Residual risks / design constraints

### Medium — Wi-Fi server is unauthenticated on the local LAN
The file/dashboard service binds to `0.0.0.0`, permits CORS `*`, and intentionally has no authentication. A device on the same LAN can reach exposed read endpoints and the report-entry DELETE endpoint. This is acceptable only on a trusted/private vehicle network. A future optional PIN/shared-secret is recommended without changing the current protocol in this release.

### Medium — target SDK 28 is legacy
The Camera App compiles with SDK 35 but targets API 28, presumably for Pilot One / legacy shared-storage compatibility. This preserves appliance compatibility but does not receive the full modern Android security/storage behavior and is unsuitable for current Play Store policy without modernization.

### Low — recording status is inferred from filesystem activity
There is no documented direct recording-state API from the Pilot recording process in these sources. The hybrid file-growth method is significantly more robust, but it is still observational. If CLOSE_WRITE is missing, NOT RECORDING may take up to ~12 seconds after the last growth signal.

### By design — cumulative reports are unbounded
The user requirement is to retain all global GOOD/FAILED/ERROR records permanently. Disk usage therefore grows over time. 0.5.16 removes the major O(N) dashboard/normal-append costs, but an explicit manual archive policy may eventually be useful.

## Verification performed

- XML resources/manifests parsed successfully.
- No merge-conflict markers found.
- Camera recording-status focused Kotlin smoke test passed.
- Report-tail focused Kotlin smoke test passed.
- Existing report-writer transaction dedupe behavior was smoke-tested.
- Kotlin syntax scan found no parser-level errors in the modified Android-dependent output code.
- Full Gradle test/build was attempted but could not start because this sandbox cannot resolve `services.gradle.org` to download Gradle 8.9 (`UnknownHostException`).

## Recommended real-device acceptance test

1. Install Camera App 0.5.16 and Client 1.9.8.
2. Keep Camera App Wi-Fi access on; start and stop several recordings of different lengths while watching Client status.
3. Confirm green RECORDING while the MP4 grows and red NOT RECORDING after stop.
4. Test with Monitoring=Output=`/storage/emulated/0/videos/stitched`; confirm no duplicate reprocessing and only GOOD.TXT/FAILED.TXT/ERROR.TXT plus media/GPX remain in the shared root.
5. Test at least one removable/SAF output transfer and verify source cleanup occurs only after successful hash verification.
6. Power-cycle/restart during a transfer/commit test and confirm journal recovery produces one report/queue result, not duplicates.
