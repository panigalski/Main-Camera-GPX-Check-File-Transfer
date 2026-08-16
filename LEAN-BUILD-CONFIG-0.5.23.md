# Lean / Stable Build Configuration — Main App 0.5.23

## Purpose

0.5.23 changes only the build toolchain. Application/runtime behavior is inherited from 0.5.22.

## Pinned toolchain

- Android Gradle Plugin: **7.4.2**
- Gradle wrapper: **7.6.4** (`-bin`, SHA-256 pinned)
- Kotlin Gradle plugin: **1.7.22**
- compileSdk: **28**
- targetSdk: **28**
- minSdk: **24**
- SDK Build Tools: **30.0.3**
- Java/Kotlin bytecode target: **1.8**
- Recommended Gradle JDK in Android Studio: **JDK 17**

## Why this is leaner

The previous build used AGP 8.7.3, Gradle 8.9, Kotlin 1.9.24, compileSdk 35 and Build Tools 35.0.0 even though the application targets API 28 and does not use APIs newer than 28. The lean profile avoids requiring Android API 35 / Build Tools 35 for compilation and pins a smaller older toolchain that remains supported by current Android Studio releases.

## One-time SDK packages

In Android Studio > SDK Manager, the project only needs:

- Android SDK Platform 28
- Android SDK Build-Tools 30.0.3

Platform Tools / ADB are optional for compilation itself but useful when installing an APK over USB.

## Recommended Android Studio setting

Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK: choose a JDK 17 installation / bundled JBR 17 if available.

## Performance settings

`gradle.properties` enables:

- Gradle daemon
- Gradle build cache
- parallel execution
- file-system watching
- Kotlin incremental compilation

Configuration cache is intentionally not enabled; stability is preferred over a small additional sync/build improvement.

## Dependency footprint

The application still has no AndroidX, Compose, Retrofit, Room, Firebase or other runtime library dependency declared in the module. The only explicit dependency remains JUnit 4.13.2 for local unit tests. Kotlin stdlib/tooling is supplied by the Kotlin plugin as usual.

## Clean first build vs later builds

The first build on a computer with an empty Gradle cache still has to download the pinned Gradle / AGP / Kotlin toolchain. Subsequent projects/builds using the same versions should reuse the global Gradle cache. Do not delete `~/.gradle/caches` between versions if you want that reuse.
