# Build / Verification — Main App 0.5.36

- Version: 0.5.36 / versionCode 59.
- Transfer eligibility no longer depends on Fragment Storage values, `camera.getOptions`, `/efs/video.properties`, Divider classification, or intermediate `gallery.addFile` callbacks.
- Added pure `RecordingSequenceTracker` implementing baseline -> A -> B releases A -> C releases B -> final stop releases last active file.
- Kotlin harness passed:
  - baseline MP4s are ignored for sequence ownership;
  - first new MP4 becomes protected active A;
  - B releases only A and becomes active;
  - C releases only B and becomes active;
  - final stop releases C and resets the baseline;
  - a subsequent new recording starts a fresh sequence.
- FileObserver fast path uses finalized MP4 `CREATE`/`MOVED_TO` ordering; periodic 5-second scan remains fallback.
- Released predecessors retain the 2-second completed-file settle guard and immutable size/mtime snapshot checks; transient MP4 finalization/parser errors use a short re-settle/retry instead of the generic 30-second retry backoff or a forced move.
- Full Android Gradle/APK assembly was attempted with `./gradlew test --offline` but cannot start because this lean source tree does not contain `gradle-wrapper.jar` (`org.gradle.wrapper.GradleWrapperMain` missing). No Android SDK/system Gradle is available in this environment.
