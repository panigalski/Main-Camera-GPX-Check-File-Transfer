# Changelog

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
## 0.5.9

- Added persistent MP4 destination-write failure monitoring for internal and external storage.
- Records failures while preparing the output folder, writing/finalizing MP4 files, and verifying completed MP4 copies.
- Exposes recent failures to the Client App through the additive `storageWriteAlerts` field in dashboard API v3.
- Write alerts are retained for seven days, bounded to 50 entries, and identical retry failures are deduplicated for five minutes.

