# 0.5.19 — Output date folders, global reports, zero-byte protection, stopped startup

## Requested behavior

- Default/reset Recording folder: `/sdcard/DCIM/Videos/Stitched`
- Every processed MP4/GPX pair is moved under `OUTPUT/dd-mm-yyyy/`.
- The OUTPUT root owns only the cumulative `GOOD.TXT`, `FAILED.TXT`, and `ERROR.TXT` reports created by this app.
- No daily TXT files are created inside the date subfolders.
- On a fresh app start, Monitoring is OFF and Wi-Fi file access is OFF. Neither service is auto-restored after boot or app replacement.

## Reliability fixes

- The processing engine checks Pilot One recording state before finalization. It will not process the active recording file even if its filesystem size appears stable.
- Zero-length MP4 placeholders are never treated as valid video. A zero-byte file that remains unchanged for two minutes while Pilot is not recording is removed and logged to global `ERROR.TXT`.
- Global report creation/writes were moved from the Recording folder to the selected OUTPUT root, including SAF output trees.
- Dashboard/client report reads and report-entry deletion now use the selected OUTPUT report store.
- The three global report files are created immediately when Monitoring is manually started, before any recording is processed.

## Date folders

Example:

```
OUTPUT/
  GOOD.TXT
  FAILED.TXT
  ERROR.TXT
  11-08-2026/
    recording001.mp4
    recording001.gpx
    recording002.mp4
    recording002.gpx
```

Legacy files/folders created by earlier versions are not automatically deleted.
