# Final Release Audit — Main Camera App 0.5.40

Audit target: `com.labpano.gpxextractor`, versionCode **63**, versionName **0.5.40**.

## Release conclusion

No additional source-level protocol conflict or known release-blocking defect was found after the fixes below and the final static/focused-runtime checks. This is not a substitute for installing the APK on a Pilot One and running the final acceptance sequence against Camera 5.18.11.

## Release-blocking issues corrected in this audit

- Removed the persisted recording-family hint. Camera 5.18.11 does not expose its idle `PreviewViewModel.mCameraMode` cross-process, so an old Stitched/Unstitched/Street View edit must not become a permanent future `Recording Type`. A Camera property edit is now a process-local 30-second hint only; an unambiguous active recording path can override it.
- Removed the unsafe `/DCIM/Videos/Stitched` => Stitched assumption from Fragment Storage policy. Camera 5.18.11 also uses that directory for Google Street View, so ambiguous state is not fabricated.
- Synchronized asynchronous protocol fallback cache writes with `/efs` and Android Settings observers. A slower compatibility request can no longer overwrite a newer Camera property update that arrived while the request was in flight.
- Switched Fragment Storage refresh throttling and command-status deadlines to device elapsed time. GPS/NTP/system wall-clock corrections cannot freeze compatibility polling or extend an in-progress command deadline indefinitely.
- Added retry backoff for an unavailable `/efs/video.properties` FileObserver and rate-limited repeated read diagnostics, preventing high-frequency live polling from causing repeated failing opens/log spam.
- Hardened `/efs/video.properties` baseline detection so a poll that catches the Properties file mid-rewrite cannot be used to infer a recording family unless Stitched, Unstitched and Street View keys are visible together.
- The generic Pilot `setting.fileChange` broadcast now forces both the `/efs` reader and Android Settings mirror reader.
- Added root response ordering fields: `generatedElapsedRealtime`, `processStartedElapsedRealtime`, and an opaque `processInstanceId`. These are additive API v3 fields consumed by Client 1.10.25.
- Restored `gradle/wrapper/gradle-wrapper.jar` so the source archive is a bootstrappable Gradle project rather than a wrapper script with a missing bootstrap JAR.

## Camera 5.18.11 Fragment Storage contract retained

- Stitched: `video.storagePart.able` / `video.storagePart.value`
- Unstitched/FishEye: `video_fishEye.storagePart.able` / `video_fishEye.storagePart.value`
- Google Street View: `video_streetView.storagePart.able` / `video_streetView.storagePart.value`
- Time Lapse: `video_timeLapse.storagePart.able` / `video_timeLapse.storagePart.value`
- Supported parsed values include `4gb`, `6gb`, `8gb`, `10gb`, `10min`, `30min`, `1h`, and `2h`.

When Camera's current recording family cannot be proven, Main App sends the concrete per-mode Fragment Storage values but leaves the selected mode unknown. This is intentional; it is safer than showing a stale or guessed mode.

## Checks completed

- Final Main Fragment Storage core compiled with Android/JSON API stubs: PASS.
- Actual `/efs` reader and Android Settings mirror reader compiled with Android API stubs: PASS.
- Actual Camera broadcast receiver compiled with Android API stubs: PASS.
- Focused executable regression harness: PASS (`MAIN_AUDIT_OK`). It covers 4/6/8/10 GB, time limits, Camera property-to-mode mapping, mode-hint expiry, ambiguous Stitched/Street View handling, and monotonic refresh-throttle behavior.
- Targeted Main Fragment Storage JUnit test sources compile with JUnit API stubs: PASS.
- Manifest/resource XML parse: PASS.
- GitHub workflow YAML parse: PASS.
- Manifest component-source lookup: PASS.
- App resource-reference scan: PASS.
- Duplicate Kotlin source-unit scan: PASS.
- Merge-marker and unfinished-task-marker scan: PASS.
- No APK/AAB/build directory/keystore/local.properties is packaged: PASS.
- Gradle wrapper JAR contains `org.gradle.wrapper.GradleWrapperMain`: PASS.

## Build-system state

- Android Gradle Plugin: 7.4.2
- Kotlin Android plugin: 1.7.22
- Gradle distribution: 7.6.4 with distribution SHA-256 configured
- compileSdk / targetSdk / minSdk: 28 / 28 / 24
- Java/Kotlin bytecode target: 1.8
- GitHub workflow uses Java 17 and runs `testDebugUnitTest assembleDebug`.

The local audit environment cannot download the Gradle distribution/Android artifacts, so a complete Android unit-test + APK assembly was not executed locally. The restored wrapper starts and reaches the configured Gradle 7.6.4 distribution download before network/DNS access blocks it.

## Required final device acceptance

Before declaring the binaries immutable, install the paired Main 0.5.40 + Client 1.10.25 and test on the real Pilot One: connect; change Fragment Storage through 4/6/8/10 GB in each of Stitched, Unstitched and Street View; verify the Client updates; disconnect/reconnect; restart Main App; reboot Pilot; record each family through at least one fragment rollover; and verify GPX/MP4 transfer and Recording/Ready state.
