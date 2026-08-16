# Fragment Storage live fix — Main App 0.5.39

## Root causes fixed

1. `Recording Type` was derived from the Main App monitor directory. That is not valid: Camera 5.18.11 writes both normal stitched and Google Street View recordings under the Stitched video family, so the directory cannot distinguish those modes.
2. If `/efs/video.properties` was not readable and a fallback source supplied a value, the fallback was allowed to populate only unknown modes. A later Camera change could therefore remain cached forever.
3. A later compatibility-protocol response could replace local fallback data and reset the Fragment Storage revision, allowing the Client to reject the real newer value as stale.

## New behavior

- `/efs/video.properties` remains authoritative whenever Main App can read it.
- If `/efs` is permission-restricted but Pilot OS mirrors the Camera keys through Android Settings, that mirror is allowed to refresh known values continuously.
- Local Camera sources outrank the compatibility control protocol and revisions stay monotonic.
- The exact mode-specific Camera property that changes identifies the Fragment Storage settings family:
  - `video.*` -> Stitched
  - `video_fishEye.*` -> Unstitched
  - `video_streetView.*` -> Google Street View
  - `video_timeLapse.*` -> Time Lapse
- A live `/Unstitched/` recording path is also a concrete Unstitched signal.
- The Main App no longer fabricates Stitched from an ambiguous `/Stitched/` directory.
- Dashboard and live-status always carry all per-mode Fragment Storage values, plus `modeSource` and `modeUpdatedAt`.

## Stock Camera limitation

Camera 5.18.11 keeps its currently highlighted idle recording mode in `PreviewViewModel.mCameraMode` inside the Camera process. `setCameraMode()` only updates that field and data binding; the supplied Camera app does not persist/expose that selection through `/efs/video.properties`, its exported provider, or Pilot Open API. Therefore a pure Main-App/Client fix cannot safely observe a mode-only switch while Camera is idle. In that state Main App reports no fabricated mode and the Client can show all three persisted Fragment Storage selections.
