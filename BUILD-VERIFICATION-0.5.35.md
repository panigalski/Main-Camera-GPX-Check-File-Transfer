# Build / Verification — Main App 0.5.35

- Version: 0.5.35 / versionCode 58.
- Re-inspected the supplied stock Camera 5.18.11 DEX and confirmed:
  - Fragment Storage UI writes `/efs/video.properties` synchronously through `PropertiesUtil.saveProps`.
  - the UI notification is an in-process EventBus `ModifySettingEvent`.
  - `VideoHelper.Divider` stop/restart reaches `onRecordStart`, which emits `com.pi.pilot.gallery.fileChange` for every fragment restart.
- Targeted Kotlin compilation passed for:
  - `CameraRecordingStatusRegistry` with Android/API stubs;
  - `PilotFragmentStorageLocalReader` + direct properties `FileObserver` with Android/API stubs;
  - `PilotFragmentStorageRegistry` with JSON/API stubs;
  - pure Divider and filesystem rollover policies.
- Focused harness passed the exact lifecycle:
  - first `fileChange` + fragment A => A protected, capture Recording;
  - repeated Divider `fileChange` => A immediately released while capture remains Recording;
  - fragment B CREATE => B protected, A remains released, capture remains Recording.
- Fragment-setting harness passed `4 GB -> 6 GB`: the registry publishes `6 GB` with a newer revision and a monotonic update timestamp.
- Full Android Gradle/APK assembly was not run because the lean source tree has no `gradle-wrapper.jar` and this environment has no Android SDK/system Gradle.
