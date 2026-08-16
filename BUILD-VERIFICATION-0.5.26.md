# Build verification — Main App 0.5.26

## Version

- versionCode: 49
- versionName: 0.5.26
- Lean/stable build configuration unchanged from 0.5.25 / 0.5.23 toolchain.

## Focused verification performed

- `CaptureDisplayPolicy` compiled with local Kotlin compiler.
- Policy cases passed:
  - fresh writes => Recording
  - write idle beyond display threshold => Ready
  - strong close/stop => Ready immediately
  - a newer recording start supersedes an older stop
  - resumed writes recover from an idle-inferred Ready state
- `CameraRecordingStatusRegistry` compiled against minimal Android `Environment` / `FileObserver`
  stubs to catch Kotlin syntax/type errors in the changed logic.
- Integration simulation passed:
  - capture starts => both display and conservative safety state are active
  - close/idle stop => display state becomes Ready while conservative safety remains active
  - Camera addFile completion => conservative safety state is released
- Processing engine source remains wired to `snapshot.recording` (conservative state), while
  `DashboardApi` publishes `snapshot.captureRecording` to the Client.

## Full Gradle build

Not completed in this sandbox. The project is pinned to Gradle 7.6.4 / JDK 17; this environment does
not have JDK 17 installed and outbound access needed to obtain Gradle dependencies is unavailable.
The full Android build should be run in Android Studio/CI with Gradle JDK 17.
