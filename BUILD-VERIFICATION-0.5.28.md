# Build / Verification — Main App 0.5.28

- Version: 0.5.28 / versionCode 51.
- Changed recording-state files compile successfully with local Kotlin compiler using minimal Android `Environment` / `FileObserver` API stubs.
- Recording-policy harness: PASS.
  - Camera start latch remains Recording without depending on MP4 write cadence.
  - Strong stop after the start latch returns Ready.
  - A newer start supersedes an older stop.
  - Filesystem fallback remains active for 15 seconds and expires after that window.
- Static check: active Main App source no longer contains `pilot-camera-write-idle` or a one-second idle-stop path.
- Full Gradle `testDebugUnitTest assembleDebug` could not run in this sandbox because Gradle 7.6.4 is not cached and outbound DNS/downloads for the Gradle wrapper are unavailable. The project workflow should run the normal full build in a network-enabled environment.
