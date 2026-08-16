# Build / Source Verification — Main 0.5.24

- Baseline: 0.5.23 lean/stable.
- Build toolchain unchanged: AGP 7.4.2, Gradle 7.6.4, Kotlin 1.7.22, compileSdk/targetSdk 28.
- OUTPUT local selection is persisted immediately after write/report-file preflight.
- SAF OUTPUT selection is persisted immediately after permission, tree-write verification and report-file preflight.
- Dashboard includes a dedicated `outputFolder` field read from current preferences on every request.
- RecordingProcessingEngine already resolves OUTPUT preferences per transaction; no Monitoring restart is required for subsequent transactions.
- An already-running transfer remains pinned to its original destination for transaction safety.

## Full Gradle test attempt

`./gradlew test --offline` was attempted. The wrapper tried to obtain Gradle 7.6.4 from `services.gradle.org` and failed with `UnknownHostException` because this sandbox has no outbound DNS/network access. A complete Android Gradle build is therefore not claimed here.
