# Build / verification notes — Main App 0.5.32

Date: 2026-08-13

## Targeted checks completed

- `PilotFragmentStorageRegistry.kt` compiles with local Kotlin/JVM stubs.
- Fragment-setting harness passes normalization for `4G`, 4096 MiB, binary/decimal 4 GB byte counts and 600-second time values.
- The same harness passes observed 4 GB fallback and verifies that a later partial protocol response does not erase an observed Stitched value, including mixed protocol+observed snapshots.
- `CameraRecordingStatusRegistry.kt` compiles with Android/Pilot registry stubs and its state-machine harness passes: same-generation A -> B -> C rollover, previous-fragment release, active-fragment protection, delayed previous `addFile` isolation, no-`addFile` rollover, new-recording generation separation, and final completion grace.
- `RecordingFileObserver.kt` compiles with stubs and its harness verifies that a temporary next-fragment CREATE wakes completed-fragment processing while ordinary temporary MODIFY does not.

## Full Android build limitation

A full Gradle Android compile could not be executed in this sandbox because the supplied lean project contains `gradle-wrapper.properties` but not `gradle-wrapper.jar`. Running `./gradlew --offline :app:compileDebugKotlin` fails with `ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain`. No system Android Gradle/SDK installation is available here to substitute for the missing wrapper.

The targeted Kotlin/state-machine checks do not replace a physical Pilot One test. In particular, actual `camera.getOptions` availability/response shape and fragment filesystem event ordering must be confirmed on the camera.
