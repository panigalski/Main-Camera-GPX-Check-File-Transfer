# Fragment Storage connection sync — Main App 0.5.38

Main App 0.5.38 treats the Client's first full-dashboard request as a Camera-settings synchronization point.

When the request contains `syncCameraSettings=1`, the Main App synchronously re-reads Camera 5.18.11's `/efs/video.properties` before it builds the response. This bypasses the ordinary elapsed-realtime polling throttle for that one request.

The persisted Camera keys are:

- Stitched: `video.storagePart.able` / `video.storagePart.value`
- Unstitched/FishEye: `video_fishEye.storagePart.able` / `video_fishEye.storagePart.value`
- Google Street View: `video_streetView.storagePart.able` / `video_streetView.storagePart.value`
- Time Lapse: `video_timeLapse.storagePart.able` / `video_timeLapse.storagePart.value`

The response continues to include the selected Main-App recording family plus all four per-family Fragment Storage objects. `fragmentStorage.connectionSynced=true` is included on this initial synchronized response for diagnostics.

## Camera recording-mode limitation

The supplied Camera 5.18.11 APK persists each family's Fragment Storage value, but the currently highlighted Camera UI mode itself is held in the Camera process (`PreviewViewModel.mCameraMode`). The stock APK does not persist that current UI selection in `/efs/video.properties`, expose it through the supplied Pilot Open API, or include it in the `gallery.fileChange` broadcast. Therefore another app can reliably synchronize the Fragment Storage values, while an idle Camera UI mode cannot be read authoritatively cross-process from the supplied interfaces.
