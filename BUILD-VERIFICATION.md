# Build / Verification — Main App 0.5.40

- Version: **0.5.40** / versionCode **63**.
- Package: `com.labpano.gpxextractor`.
- Build stack: Android Gradle Plugin **7.4.2**, Kotlin **1.7.22**, Gradle **7.6.4**, compileSdk/targetSdk/minSdk **28/28/24**, JVM target **1.8**.
- `gradle/wrapper/gradle-wrapper.jar` is present and contains `GradleWrapperMain`.
- Fragment Storage final-audit core compile and executable regression harness: **PASS**.
- `/efs` + Android Settings readers compile with API stubs: **PASS**.
- Camera broadcast receiver compile with API stubs: **PASS**.
- XML, workflow YAML, manifest components, app resource references, duplicate source units, merge markers and packaging hygiene: **PASS**.
- The configured wrapper starts correctly but this audit environment cannot download Gradle/Android dependencies, so `testDebugUnitTest assembleDebug` must be run in the included online GitHub workflow or another Android build environment.

See `AUDIT-RESULTS.md` for the final release findings and target-device acceptance checklist.
