# Pilot Camera recording status - 0.5.17

## Why 0.5.16 could show NOT RECORDING

0.5.16 inferred Pilot recording from FileObserver events and MP4 growth in the configured folder.
Labpano Camera 5.18.x owns the actual recording state, and Pilot OS does not guarantee that another
application receives every filesystem write event. The Pilot SDK also defaults stitched video to
`/sdcard/DCIM/Videos/Stitched/`, which can differ from the GPX Extractor's configured folder.

## 0.5.17 status sources

1. Primary: Labpano Camera broadcasts observed in Camera 5.18.11:
   - `com.pi.pilot.gallery.fileChange` from `onRecordStart()` (treated as a start hint because photos
     also use this action).
   - `com.pi.pilot.gallery.addFile` with `filepath` / `fileType` when completed media is registered.
2. Verification: a fresh video file in the configured directory or Pilot's standard
   `DCIM/Videos/Stitched` / `Unstitched` locations.
3. Fallback: FileObserver and repeated size/mtime growth detection.

A start hint is latched as RECORDING only when it can be associated with a fresh video file, avoiding
false RECORDING states from photo capture. The completed-video broadcast clears the latch promptly.

No recording control is added. GPX Extractor only observes status and continues to process the folder
selected by the user.
