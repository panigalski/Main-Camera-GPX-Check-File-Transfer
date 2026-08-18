# Build and GitHub repository setup

## GitHub

This archive is intentionally packaged with the project root at the ZIP root.

1. Create an empty GitHub repository.
2. Extract this ZIP locally.
3. Commit the extracted contents to the repository root.
4. Push to GitHub.
5. Open **Actions** and run **Build Debug APK**, or push a commit to trigger it automatically.

The workflow is `.github/workflows/build-apk.yml`. It installs Java 17 and the required Android SDK packages, runs JVM unit tests, builds the debug APK, and uploads the APK as an Actions artifact.

## Local build

Requirements:

- JDK 17
- Android SDK Platform 28
- Android Build Tools 30.0.3

```bash
chmod +x gradlew
./gradlew testDebugUnitTest assembleDebug
```

The Gradle wrapper is pinned to Gradle 7.6.4 and includes a SHA-256 checksum in `gradle/wrapper/gradle-wrapper.properties`.

## Files that should not be committed

The included `.gitignore` excludes Android/Gradle build directories, Android Studio metadata, `local.properties`, APK/AAB outputs and signing material. Keep signing keys and credentials outside the repository.
