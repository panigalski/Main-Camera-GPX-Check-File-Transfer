# Main App 0.5.29 — Recording / Transfer / Report Stability

## Root causes found by checking Main + Client together

1. Main 0.5.28 still treated MP4 `CLOSE_WRITE` and IMU sidecar close as authoritative recording-stop signals. The supplied Labpano Open API exposes explicit `startRecord(...)` / `stopRecord(...)`; its public `MediaRecorderListener` does not provide a cross-app recording-state callback, so a filesystem handle close is not a safe substitute for Camera stop.
2. Camera completion (`com.pi.pilot.gallery.addFile`) was applied globally. Because Camera can finish/register an older video after the user has already started a newer one, a delayed completion could clear the newer recording latch.
3. The completion/broadcast and FileObserver paths were not serialized around the multi-field recording latch, leaving a race where an older completion could clear a newly assigned latch.
4. Even when Camera supplied an explicit completed-video `addFile`, the processing engine still imposed the generic 30-second stable-file delay.
5. Output copy verification read the entire destination MP4 again with SHA-256 after the full copy. For large Pilot videos this held the Client at `VERIFYING` for another full-file I/O pass and delayed transaction commit / TXT reporting.

## Fix

- Recording ownership is now per video and synchronized.
- The high-frequency status-only FileObserver now forwards Camera temporary writer aliases (`.part` / `.tmp`) to the recording registry; only finalized `.mp4` files are forwarded to the processing engine.
- A newly-created video associated with a newer Camera `fileChange` supersedes an older latch even when the prior video's delayed `addFile` has not arrived.
- Only `addFile` matching the currently latched video releases that Camera recording latch.
- MP4 `CLOSE_WRITE` and IMU close are retained only as filesystem observations; they do not set user-visible Ready.
- Completed-video hints survive final late MODIFY events and are matched by canonical path or basename alias.
- Camera `addFile` uses a 2-second settling window and immediate + delayed rescans; files without a Camera completion signal retain the conservative 30-second fallback gate.
- Local transfer verification now uses exact copied byte count, fsync, unchanged-source validation, exact destination size, and four bounded 256 KiB content samples instead of a full second read.
- SAF transfer verification uses exact copied byte count, unchanged-source validation, and bounded destination-size confirmation. Existing SAF files are reused only when bounded random-access samples match; otherwise a unique copy is made.
- Transaction commit ensures `GOOD.TXT`, `FAILED.TXT`, and `ERROR.TXT` all exist at the actual OUTPUT root before appending the result.

## Expected paired behavior with Client 1.10.14

- `Pilot One Recording Status:` remains **Recording** for the active video.
- A delayed completion for the previous video cannot change a new active recording to **Ready**.
- Once the matching video is registered complete, the status changes to **Ready** and processing begins after the short settling/readiness gate.
- Transfer `VERIFYING` is bounded and no longer scales with the entire MP4 size as a second read.
- The cumulative TXT report files are present at OUTPUT and the completed recording is appended after the verified transfer transaction commits.
