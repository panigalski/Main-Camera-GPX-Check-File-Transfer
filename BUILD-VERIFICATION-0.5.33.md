# Build / Verification — Main App 0.5.33

- Version: 0.5.33 / versionCode 56.
- Actual `FilesystemFragmentRolloverPolicy.kt` compiled with a focused harness: **PASS**.
- Mid-record startup case (A already exists, B is the first concrete writer event) selects A as B's predecessor: **PASS**.
- CREATE-only successor does not release A immediately; persistence proof is required: **PASS**.
- Successor growth proves B immediately: **PASS**.
- Previous fragment must satisfy the 2.5-second writer/mtime quiet period: **PASS**.
- Actual `Mp4ReadinessChecker` / ISO-BMFF reader accepts a synthetic finalized `moov` + `mdat` MP4 box sequence: **PASS**.
- Actual `PilotFragmentStorageRegistry.kt` standalone Kotlin compilation with local `org.json` / logging stubs after the timeout/session fallback change: **PASS** (URL-constructor deprecation warnings only).
- Actual `PilotFragmentStorageLocalReader.kt` compilation with Android Settings/Context stubs: **PASS**.
- Proven 4 GiB filesystem rollover publishes `4 GB (observed)`: **PASS**.
- `RecordingFileObserver.kt` standalone callback/event compilation with Android stubs: **PASS**.
- `RecordingStatusObserverManager.kt` compilation caught and fixed the stale zero-argument observer lambda after the callback became `(event, file)`: **PASS after correction**.
- Source review confirms `RecordingMonitorService` forwards `(event, file)` to the processing engine and requests an immediate completed scan.
- Runtime `camera.getOptions` / temporary-session behavior cannot be exercised in this container because no Pilot One control service is attached.
- Full Gradle compile/APK assembly cannot be executed here: the lean source package does not contain `gradle-wrapper.jar`, and no system Gradle/Android SDK is installed.
