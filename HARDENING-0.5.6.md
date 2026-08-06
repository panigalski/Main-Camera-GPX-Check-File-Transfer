# Labpano GPX Extractor 0.5.6 — hardening summary

This release applies the highest-priority items from the 0.5.5 static code review while retaining the existing dated folder structure and client API contract.

## Recovery and file safety

- Destination details are journaled in SQLite after both destination files are verified and **before** either source file is deleted.
- Rolling and dated report writes are idempotent using a stable transaction ID.
- Pending transactions are retried at monitoring startup and every 30 seconds.
- A source-cleanup failure does not repeat a verified copy; cleanup alone is retried.
- Existing exact-name destination files are reused when their sizes match. A one-sided interrupted pair can be completed without creating an unnecessary numbered duplicate.
- Local copies continue to use `.part`, `fsync`, SHA-256 verification, and source snapshot validation. SAF copies use fixed source snapshots and destination size verification.

## Processing policy

- Retryable failures use exponential backoff.
- A recording is quarantined after seven unsuccessful attempts or 30 minutes of retry age.
- Permanent validation/parser failures are moved to the dated `ERROR_dd-MM-yyyy` folder, with an optional GPX when present.
- GOOD and gap-warning FAILED recordings continue to move as matched MP4/GPX pairs.

## Long-running operation

- Rolling reports are bounded and rotated.
- Crash logs rotate at 2 MiB.
- SQLite queue, final-processing records, and completed journal entries are pruned.
- File locks use a fixed striped table instead of one permanent lock per path.
- Stability timing uses `SystemClock.elapsedRealtime()`.
- Monitoring and Wi-Fi services use non-sticky startup and release their executors/database helpers when stopped.

## GPX handling

- Empty/single-point data, invalid coordinates, duplicate timestamps, and reversed timestamps are rejected.
- Genuine CAMM points are never truncated by interpolation limits.
- Interpolation density is reduced automatically when necessary.
- Longitude interpolation handles the ±180° anti-meridian correctly.
- Reports distinguish extracted points from synthetic interpolated points.

## Compatibility

The dashboard, health, and pending-GPX endpoints report API version 3, which remains compatible with the current Labpano GPX Client. The durable queue is capped at 5,000 records so the current non-paginating client remains within its response-size limit in normal use.

## Remaining deployment checks

A device test is still recommended for SD-card removal during transfer, storage-full conditions, process termination at transfer/report boundaries, and long-running Wi-Fi use. The project includes static and core Kotlin checks, but a full Android Gradle build requires an Android SDK and the Gradle 8.7 distribution.
