# Main App 0.5.30 — Recording / Transfer Decoupling

## Remaining problem in 0.5.29

Two independent workflows were still sharing too much state:

1. A new Camera `fileChange` could arrive while the previous MP4 was still active/finalizing. Because that old file was recent, 0.5.29 could reassign the new lifecycle generation to the old file before the genuinely new MP4 appeared.
2. After Camera completion, MP4 filesystem activity from finalization/copy/rename/delete could still participate in fallback recording detection.
3. The processing engine asked a global recording snapshot whether a file should be protected. That is overly broad for back-to-back recordings: completed video A must be movable while current video B remains protected.

## 0.5.30 behavior

- Camera lifecycle is generation-based and monotonic.
- A new `fileChange` never reuses the video already latched to the previous generation.
- Once any Pilot Camera lifecycle has been observed in this Main App process, generic filesystem activity cannot resurrect `Recording` after completion. Filesystem-only recording detection remains available only for a process that starts mid-recording before receiving any Camera lifecycle broadcast.
- `cameraRecording.generation` is added to both dashboard and live-status API payloads.
- Processing uses `CameraRecordingStatusRegistry.isRecordingFile(file)` rather than a global recording Boolean/name snapshot.
- While video B is recording, only B (including `.part`/`.tmp` aliases) is protected. A Camera-completed video A can settle, validate, generate GPX, transfer, report and clean up concurrently with B's ongoing recording.

## Important safety boundary

The MP4 that Pilot Camera is **currently writing** is still never moved or deleted. The public Pilot API starts a recording to one file and finalizes/stops it through the record-stop lifecycle. 0.5.30 improves handoff of already-completed files while a later recording is running; it does not copy/delete the open current recording prematurely.
