# Pilot One Recording Status Stability — Main App 0.5.28

## Fixed symptom
Client could show `Recording` briefly and then return to `Ready` about one second after a Pilot One recording started, even though the camera continued recording.

## Root cause
0.5.27 treated one second without a visible MP4 write as a capture-stop signal and made that inference sticky for the current video. PilotSDK/Android filesystem notifications are not guaranteed to expose continuous sub-second writes, so a normal write gap could be mistaken for Stop.

## 0.5.28 behavior
- A Camera `fileChange` that is successfully associated with a newly-created video remains the authoritative capture-start latch.
- That latch stays `Recording` until a strong stop signal is observed: video writer close, optional IMU writer close, Camera `addFile`, or a newer lifecycle transition.
- MP4 write silence no longer closes a Camera-latched capture.
- Filesystem-only fallback remains available with a 15-second freshness window and does not create a sticky stop merely because activity becomes quiet.
- Conservative file ownership/finalization protection in the processing engine is unchanged.
