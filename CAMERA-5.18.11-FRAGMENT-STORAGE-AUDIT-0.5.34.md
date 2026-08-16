# Camera 5.18.11 Fragment Storage audit — Main App 0.5.34

This revision is grounded in the supplied stock `Camera_5.18.11` APK rather than the public `camera.getOptions` assumption used by 0.5.31–0.5.33.

## What the stock Camera APK actually does

- Package: `com.pi.pilot.camera`, version 5.18.11, system shared UID.
- Camera settings code reads `/efs/video.properties`.
- Normal stitched video Fragment Storage uses:
  - `video.storagePart.able`
  - `video.storagePart.value`
- Other modes use the matching `video_fishEye`, `video_streetView`, and `video_timeLapse` prefixes.
- Raw values embedded in the Camera APK include `10min`, `30min`, `1h`, `2h`, `4gb`, `6gb`, `8gb`, and `10gb`.
- The stock Camera app implements Fragment Storage through `VideoHelper.Divider`: the current low-level recorder is stopped, its stop callback runs, a new timestamp filename is generated, and recording restarts into the next fragment.
- The Camera gallery `addFile` broadcast is emitted for accumulated video parts on the final overall stop path; it is not a reliable per-fragment rollover signal.

`PilotIme_5.18.11.apk` contains no Fragment Storage / `video.properties` references and is not involved in this setting.

## Main App 0.5.34 behavior

1. Read `/efs/video.properties` directly and read-only when Pilot OS permissions permit it.
2. For `/DCIM/Videos/Stitched`, publish the exact `video.storagePart.*` setting (for example `4gb` -> `4 GB`).
3. If `/efs` is permission-restricted to the system Camera app, publish that exact diagnostic instead of the misleading public-control-protocol firmware error. A later proven 4/6/8/10 GB or timed rollover can still publish an `(observed)` value.
4. Rolling transfer no longer waits for `camera.getOptions` or gallery `addFile`.
5. Once successor fragment B is proven to be writing and predecessor A has stopped changing, A is released from Camera ownership. This mirrors Camera 5.18.11's actual stop-then-restart Divider sequence.
6. The generic top-level MP4 readiness heuristic no longer blocks a filesystem-proven Divider predecessor. Snapshot/mtime/size checks still prevent a changing file from being processed or moved.
7. The currently active successor remains protected.

## Installation / permission caveat

The stock Camera APK runs with Android's system shared UID. The Main App is intentionally not changed to request that UID because a normally signed/sideloaded app cannot legitimately obtain the platform UID. Therefore direct `/efs/video.properties` access depends on the file permissions configured by the Pilot OS image. If the file is not readable, automatic pre-rollover display of the configured value is not available through this route; rolling transfer remains independent and still works from the actual fragment sequence.
