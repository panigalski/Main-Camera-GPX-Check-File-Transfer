# Build / Verification — Main App 0.5.31

- Version: 0.5.31 / versionCode 54.
- `CameraRecordingStatusRegistry` Kotlin compilation with Android `FileObserver`/`Environment` stubs: **PASS**.
- Fragment Storage enabled: next distinct segment releases the previous segment while preserving the same Camera lifecycle generation and `Recording` display: **PASS**.
- Matching `addFile` for a segmented current fragment makes that exact completed fragment processable immediately while the overall display remains `Recording` during the continuation grace: **PASS**.
- Delayed `addFile` for an older fragment does not stop/protect the current fragment: **PASS**.
- Temporary Fragment Storage query outage: matching `addFile` + next distinct MP4 hands ownership forward instead of blocking fragments until final Stop: **PASS**.
- New real Camera `fileChange` after a completed fragment is not stolen by the fragment-rollover path and receives the next lifecycle generation: **PASS**.
- Final segmented `addFile` with no continuation stays monotonic during the anti-flash grace and then transitions to Ready: **PASS**.
- Known `Off (Unlimited)` setting retains ordinary behavior: matching `addFile` completes the recording immediately and the file is not protected afterward: **PASS**.
- `PilotFragmentStorageRegistry` standalone Kotlin syntax/API compilation with local `org.json` / logging stubs: **PASS**.
- The runtime HTTP call to Pilot One's local `camera.getOptions` service cannot be exercised in this container because there is no Pilot One camera/control service attached. The implementation follows Labpano's documented option names/request endpoint and keeps the last successful setting through transient read failures.
- START/STOP button logic was statically reviewed: folder create/write/read/delete and report access checks execute before service request; service publishes `monitoring` only after engine + `FileObserver` initialization; Activity derives STOP text from `RecordingMonitorService.isRunningInProcess()` rather than optimistic status text.
- Full Gradle compile/APK assembly could not be executed in this environment because the lean source package does not contain `gradle-wrapper.jar`, and no system Gradle/Android SDK is installed here.
