# Build verification — Main App 0.5.25

Baseline: Main App 0.5.24 Live Output Folder / Lean Stable.

## Static verification completed

PASS:

- versionName `0.5.25`, versionCode `48`;
- dashboard API remains `apiVersion = 3`;
- additive `deviceDiagnostics` field is present;
- diagnostics generation is wrapped in a fail-isolated fallback object;
- `ACCESS_FINE_LOCATION` and `BLUETOOTH` are declared;
- passive Android location observation is used;
- `GnssStatus.Callback`, C/N0 and used-in-fix satellite data are implemented;
- Android mock-location state is reported;
- connected BLE GATT state plus ACL connection observations are implemented;
- the Main App does not call `BluetoothAdapter.startDiscovery()`;
- the Main App does not call `connectGatt()` or create Bluetooth sockets for diagnostics;
- AndroidManifest XML parses successfully;
- Kotlin parser-oriented checks found no syntax errors in changed Kotlin sources;
- root Gradle/Kotlin/wrapper configuration is byte-for-byte unchanged from 0.5.24; only the app version metadata changed in `app/build.gradle.kts`.

## Full Gradle build

A complete Gradle build could not be run in this sandbox. `./gradlew --version` correctly attempts Gradle 7.6.4 but fails before Gradle starts because outbound DNS to `services.gradle.org` is unavailable:

`java.net.UnknownHostException: services.gradle.org`

Therefore this verification does not claim that an APK was assembled here.
