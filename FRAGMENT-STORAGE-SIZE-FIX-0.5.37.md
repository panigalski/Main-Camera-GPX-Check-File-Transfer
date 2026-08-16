# Fragment Storage Size Fix — Main App 0.5.37

## Camera 5.18.11 source of truth

The supplied Camera 5.18.11 APK was inspected directly. Its `StoragePartModel` writes the selected value to `/efs/video.properties` and posts only an in-process EventBus notification.

For Stitched video it uses:

- `video.storagePart.able`
- `video.storagePart.value`

Equivalent prefixes are used for the other recording families:

- `video_fishEye.storagePart.*`
- `video_streetView.storagePart.*`
- `video_timeLapse.storagePart.*`

The Camera selection values include the size limits `4gb`, `6gb`, `8gb`, and `10gb`, plus time limits `10min`, `30min`, `1h`, and `2h`.

The supplied `pilot-open-api-master` does not expose a Fragment Storage / `storagePart` getter. The Camera app's exported `CameraProvider` is unrelated to Fragment Storage.

## Main-App changes

1. Keep `/efs/video.properties` as the authoritative read path and FileObserver source.
2. Do not publish a partially-written `able=true` state unless the corresponding value is present.
3. Parse the Camera raw selector into a structured value:
   - `limitType = "size"`, `sizeGb = 4|6|8|10`, or
   - `limitType = "time"`, `durationMinutes = 10|30|60|120`.
4. Publish the selected recording mode and the exact raw Camera value in both dashboard endpoints.
5. Include the same structured fields for all four per-mode objects.
6. Add a device-elapsed-realtime Main-App process marker so the Client can order Fragment Storage revisions across a Main-App process restart.
7. Retain read-only compatibility fallbacks for Pilot builds that make the same Camera keys visible through a control-service alias or an Android Settings mirror. These fallbacks never overwrite a concrete direct Camera-file value.

## API example

```json
"fragmentStorage": {
  "available": true,
  "enabled": true,
  "display": "6 GB",
  "mode": "stitched",
  "rawValue": "6gb",
  "limitType": "size",
  "sizeGb": 6,
  "durationMinutes": null,
  "revision": 8,
  "processStartedElapsedRealtime": 1234567,
  "source": "camera-efs-video.properties"
}
```

This is additive to API version 3, so older Clients can continue using `display`.
