# Build / verification — 0.5.22

## Passed focused checks

- Kotlin pure-source compile: CAMM parser, ISO-BMFF reader/readiness, GPS densifier/validator/writer, dated output layout, output layout policy.
- Runtime path migration check: old `Stichted` preference -> `/sdcard/DCIM/Videos/Stitched`; legacy `/storage/emulated/0/videos/stitched` -> current default; custom path unchanged.
- Runtime report-tail check: bounded recent-marker search and last-lines reader.
- Runtime local report-store harness: created `GOOD.TXT`, `FAILED.TXT`, `ERROR.TXT`; appended GOOD/FAILED records; read report tails; verified report-health metadata.
- Focused Kotlin compile of `DashboardApi` with Android/JSON API stubs after report diagnostics changes.
- Focused Kotlin compile of `PilotCameraBroadcastReceiver` after manual-only monitor signaling changes.
- Main manifest XML parse passed.
- Kotlin source brace sanity check passed.

## Full Gradle attempt

`./gradlew test --no-daemon` was attempted. The Gradle wrapper tried to download `gradle-8.9-bin.zip` but the execution sandbox cannot resolve `services.gradle.org`, producing `java.net.UnknownHostException`.

Therefore this document does **not** claim a complete Android Gradle/APK build in this environment. The included GitHub Actions workflow or a networked Android/Gradle machine should run `testDebugUnitTest assembleDebug`.
