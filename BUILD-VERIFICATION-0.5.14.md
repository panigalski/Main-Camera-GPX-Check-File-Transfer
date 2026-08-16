# Build Verification — 0.5.14

## Focused checks completed

- Kotlin 1.9.0 compiled `ProcessingStatus`, `DatedOutputLayout`, and `OutputLayoutPolicy` successfully.
- Focused execution verified that GOOD/FAILED/ERROR media subfolder generation returns no subfolder, so completed media targets the OUTPUT root.
- Focused execution verified that daily monitoring reports are suppressed when Monitoring and Output resolve to the same filesystem directory and remain enabled for separate directories.
- Static Kotlin parser checks on the modified `OutputMover`, `RecordingProcessingEngine`, and `ProcessedRecordingStore` found no syntax-level errors.
- Same-root output now uses an in-place verification result with `sourceCleanupPending=false`, avoiding deletion of a file that is already in its destination folder.
- Final processed-record rows older than the queue retention window are pruned only when their source path no longer exists, preventing retained root-level MP4 files from being reprocessed later.

## Full Gradle build

`./gradlew testDebugUnitTest --no-daemon` could not start because the wrapper needs to download Gradle 8.9 from `https://services.gradle.org`, and outbound DNS/network access is unavailable in this sandbox (`UnknownHostException: services.gradle.org`).

The repository's GitHub Actions workflow can perform the full Android test/build in a network-enabled environment.
