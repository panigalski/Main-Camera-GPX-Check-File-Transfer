# Build verification — Camera App 0.5.9

- Android Gradle Plugin: 8.7.3
- Gradle distribution: 8.9 (binary distribution)
- Required JDK: 17
- Compile SDK / target SDK: 35 / 28
- Android build tools installed by workflow: 35.0.0
- GitHub Actions: `actions/checkout@v6`, `actions/setup-java@v5`, `actions/upload-artifact@v7`
- Workflow command: `./gradlew testDebugUnitTest assembleDebug --no-daemon --stacktrace --console=plain`
- Gradle distribution SHA-256 is pinned in `gradle-wrapper.properties`.
- The standard wrapper bootstrap is Java-17 compatible.
- The new persistent storage-write alert registry and the modified `OutputMover` were compiled locally against focused Android/API stubs after the 0.5.9 changes.
- All production Kotlin files were additionally parser-scanned together; no Kotlin syntax, illegal-escape, unclosed-token or redeclaration diagnostics were found.
- Existing JVM tests remain in the repository and GitHub Actions runs the complete `testDebugUnitTest` suite before APK assembly.
- Manifest/resource XML, workflow YAML, workflow Bash fragments and launcher PNGs were validated.
- MP4 destination failures are recorded at output-folder preparation, MP4 write/finalization and destination verification. Alerts are persisted (bounded to 50, retained seven days) and exposed as an additive dashboard API v3 field for the Client App.

A complete Android APK cannot be built in this packaging environment because Android SDK/Maven/Gradle distributions are not available locally and outbound dependency downloads are unavailable. The included GitHub workflow performs the final online Android build.
