# Build / Verification — Main App 0.5.27

- Version: 0.5.27 / versionCode 50.
- Lean/stable toolchain unchanged: AGP 7.4.2, Gradle 7.6.4, Kotlin 1.7.22, compile/target SDK 28, Build Tools 30.0.3, Java/Kotlin bytecode 1.8; run Gradle with JDK 17.
- Focused Kotlin recording-flow harness passed, including late-finalization, photo-`fileChange` false-reopen, and monitor-observer restart cases.
- Changed Android-dependent Kotlin files produced no parser/syntax errors under `kotlinc`; unresolved Android symbols are expected in this sandbox because no Android `android.jar` is installed.
- XML parse validation passed.
- Source contract audit passed.
- ZIP integrity passed after packaging.
- Full Gradle build unavailable in this sandbox because `services.gradle.org` is not reachable.
