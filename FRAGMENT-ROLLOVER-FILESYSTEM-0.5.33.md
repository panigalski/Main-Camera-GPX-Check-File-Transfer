# Filesystem Fragment Rollover — Main App 0.5.33

## Problem reproduced in the 0.5.32 source flow

0.5.32 could still miss the first fragmented-video rollover when Monitoring began after fragment A had already started. The periodic scan knew A existed, but if no concrete A writer event had been delivered, `activeWriterStem` remained empty. The first CREATE/MODIFY received for fragment B then made B active without creating an A → B rollover candidate. A remained protected by stale Camera ownership and did not process until the recording finally stopped.

Fragment Storage collection also treated a socket read timeout like a hard unreachable-host failure. That skipped the documented temporary-session compatibility path even though the Camera control socket could have been reachable but waiting for a session.

## 0.5.33 behavior

The processing engine now has an independent filesystem rollover proof path:

1. Observe MP4 writer aliases (`.mp4`, `.mp4.part`, `.mp4.tmp`) and their size/mtime changes.
2. If Monitoring started mid-fragment and B is the first concrete writer event, select the latest plausible finalized predecessor as A.
3. Prove B is real by subsequent activity, or by bounded CREATE-only persistence.
4. Require A to remain unchanged for 2.5 seconds.
5. Require `Mp4ReadinessChecker` to report A structurally ready.
6. Mark only A's normalized stem filesystem-complete, wake the processing scanner immediately, and keep B protected.

This path does not require `camera.getOptions`, `addFile`, or the recording-status registry to release A. Those sources remain useful for display/acceleration but cannot deadlock segmented transfer.

## Fragment Storage display

The collector still prefers the documented Pilot `camera.getOptions` value. Stitched is requested first and missing fields are queried separately. A direct read timeout is allowed to fall through to the temporary-session path. Exact documented option keys are also checked read-only in Android Settings as a best-effort Pilot-OS fallback.

If no API/local setting is available, a filesystem-proven completed fragment may infer only one of the supported boundaries (4/6/8/10 GB or 10/30/60/120 minutes) within a bounded tolerance. Such values are always labeled `(observed)` so they are not confused with a Camera-provided setting.

## Safety invariants

- The current/newest fragment is never moved by rollover inference.
- A previous fragment is never released from inference until MP4 structural readiness passes.
- Only the exact proven-complete normalized stem can bypass stale Camera ownership.
- Generic single-file recordings continue using the ordinary conservative completion path.
