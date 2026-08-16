# Recording stop latency fix — Main App 0.5.26

## Problem

With Camera 5.18.11, the Main App previously kept the Client-facing recording state latched to
`Recording` until the Camera app emitted `com.pi.pilot.gallery.addFile`. On the tested Pilot One this
can happen roughly 12 seconds after the user presses Stop because PilotSDK is still finalizing the
MP4.

That conservative latch is correct for **file safety**, but it is too conservative for the Client UI.

## Fix

0.5.26 separates two concepts inside `CameraRecordingStatusRegistry`:

- `recording`: conservative Camera ownership/finalization gate used internally by
  `RecordingProcessingEngine`.
- `captureRecording`: fast user-visible state returned as `cameraRecording.recording` by the
  dashboard.

The display state remains `Recording` while the current MP4 is receiving live write activity. It
switches to Ready when either:

1. the current MP4 receives a strong close/delete/move-away event, or
2. no write activity has been observed for 2.5 seconds.

The idle inference is display-only and is reversible: if writes resume, the displayed state returns
to `Recording`.

## Processing safety

The processing engine does **not** use the fast display state. It continues to use the conservative
Camera ownership latch, which remains active until the Camera completion signal or existing fallback
logic releases it. The existing 30-second stable-file period, MP4 readiness validation, snapshot
rechecks and verified transfer safeguards are unchanged.

Therefore this fix does not allow an MP4 to be parsed, moved or deleted merely because the UI has
already changed to Ready.

## API compatibility

Dashboard API stays at v3. `cameraRecording.recording` now represents the capture/display state and
an additive `cameraRecording.finalizing` boolean is included for future/newer clients. Client 1.10.11
already handles the updated `recording` value and ignores the additive field, so no Client update is
required.
