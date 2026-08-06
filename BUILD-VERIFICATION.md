# Build verification

- Android Gradle Plugin: 8.7.3
- Gradle distribution: 8.9 (binary distribution)
- Required JDK: 17
- Compile SDK / target SDK: 35 / 28
- Android build tools installed by workflow: 35.0.0
- Workflow command: `./gradlew testDebugUnitTest assembleDebug --no-daemon --stacktrace --console=plain`
- Gradle distribution SHA-256 is pinned in `gradle-wrapper.properties`.
- The standard wrapper bootstrap loads without a Java class-version error.
- All production Kotlin files were type-checked together against Android API-compatible compile stubs.
- Ten JVM tests covering CAMM parsing, video/GPX timeline synchronization, GPX writing, validation, interpolation and dated paths passed.
- Filesystem and SAF-backed pending-GPX download paths were reviewed and type-checked.
- Manifest, resource XML, workflow YAML, shell fragments and PNG icons were validated.

A complete Android APK could not be built in the packaging environment because Gradle, Android SDK 35 and Maven artifacts were not available offline. The included GitHub workflow performs the final online Gradle build.
