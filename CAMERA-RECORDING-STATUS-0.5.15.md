# Camera App 0.5.15 — Recording status for Client

The Camera App dashboard now includes `cameraRecording` with `available`, `recording`, `videoName`, `updatedAt`, and `source`.

The state is driven by MP4 filesystem activity in the configured Recording folder: CREATE/MODIFY marks recording active and CLOSE_WRITE/MOVED_TO marks it complete. A short recent-write fallback detects a recording that was already underway when the monitoring service started. Explicitly closed files are excluded from that fallback to prevent a false RECORDING state after completion.
