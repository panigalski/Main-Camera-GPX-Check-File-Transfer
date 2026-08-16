# Build Verification — 0.5.23 Lean / Stable Build

## Scope

0.5.23 changes only build configuration. `app/src` is byte-for-byte identical to 0.5.22.

## Static checks performed

- Confirmed no runtime Kotlin/XML source change relative to 0.5.22.
- Confirmed `compileSdk = 28`, `targetSdk = 28`, `minSdk = 24`.
- Confirmed SDK Build Tools pinned to 30.0.3.
- Confirmed AGP 7.4.2, Gradle 7.6.4 and Kotlin plugin 1.7.22 are pinned.
- Confirmed Gradle distribution SHA-256 is pinned.
- Confirmed wrapper bootstrap JAR is present in the project ZIP.
- Confirmed source contains no explicit `Build.VERSION_CODES` references newer than API 28.
- Confirmed GitHub Actions requests Android Platform 28 and Build Tools 30.0.3 rather than API/Build Tools 35.
- Confirmed Gradle caching, daemon, VFS watching and Kotlin incremental compilation are enabled.
- Confirmed configuration cache is deliberately not enabled.

## Full Gradle build

A full Gradle build was not executed in this sandbox because the environment cannot download the pinned Gradle distribution / Maven build plugins from the external repositories. The runtime source is unchanged from the previously audited 0.5.22 project; the remaining validation item is the first real Android Studio / GitHub Actions build of this new pinned toolchain.

## Recommended local setup

Use Android Studio with Gradle JDK 17, Android SDK Platform 28 and SDK Build Tools 30.0.3 installed. The first sync must download the pinned Gradle/AGP/Kotlin tooling once; subsequent builds should reuse the global Gradle cache.
