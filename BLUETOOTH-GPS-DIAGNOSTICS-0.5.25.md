# Main App 0.5.25 — Bluetooth / GPS diagnostics

Main App 0.5.25 adds an optional `deviceDiagnostics` object to the existing `/api/v1/dashboard` response. `apiVersion` remains 3 so older Client builds keep working and ignore the additive field.

## Bluetooth

The Main App reports Bluetooth enabled/disabled state and connected devices using three best-effort sources:

1. bonded-device connection state through the Pilot One Android 7 runtime,
2. Bluetooth GATT connected-device state,
3. low-level ACL connect/disconnect broadcasts observed while the Main App process is alive.

Likely GPS/GNSS receivers are marked from conservative device-name hints. The Client still shows every connected device; the hint only affects ordering/labeling.

RSSI is intentionally non-invasive. The Main App never starts Bluetooth discovery and never opens a second GATT/RFCOMM connection just to measure signal strength. If Android passively emits a recent discovery RSSI for a connected device, it is published in dBm. Otherwise RSSI is explicitly unavailable.

## Active camera location source

The Main App requests `ACCESS_FINE_LOCATION` and observes the passive Android location provider. This lets it observe locations already being requested by the camera/OS without activating internal GNSS merely for diagnostics.

The dashboard reports:

- provider name,
- whether the latest location is fresh,
- whether Android marks it as a mock location,
- last-fix time,
- accuracy,
- source classification.

Classification is deliberately conservative:

- non-mock `gps` provider → `Internal/system GNSS`,
- mock location plus a connected device whose name strongly suggests GPS/GNSS → `External Bluetooth GPS via mocked location (inferred)`,
- other mock location → `Mocked by another app`,
- fused/network/other system providers → labeled as those Android system providers.

Android's public Location API identifies that a fix is mocked but does not identify the app that injected it, so the Main App does not invent an app name.

## GNSS signal

Android `GnssStatus` data is published when available:

- GNSS running/stopped,
- signal sample freshness,
- satellites visible,
- satellites used in the most recent fix,
- average C/N0,
- maximum C/N0,
- visible constellation counts,
- used-in-fix constellation counts,
- time to first fix.

When the active location is mock/injected or is not the Android GPS provider, the dashboard marks system-GNSS signal as not necessarily matching the active location source. This prevents an external receiver's fix from being incorrectly paired with Pilot One internal satellite signal data.

## Failure isolation

`DashboardApi` wraps diagnostics generation in a fail-safe object. A Bluetooth/location diagnostics failure cannot turn an otherwise valid dashboard poll into an HTTP failure or disconnect the Client.
