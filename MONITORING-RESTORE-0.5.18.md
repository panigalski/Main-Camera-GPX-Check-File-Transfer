# Monitoring restore / processing regression fix - 0.5.18

## Root cause

The Pilot Camera broadcast receiver can update recording status without RecordingMonitorService running.
MainActivity still contained a legacy process-start reset that called `stopService()` for both Monitoring and Wi-Fi,
so opening the Main App could leave the client correctly showing Pilot recording while MP4/GPX processing was off.
`RecordingMonitorService.onDestroy()` also cleared the desired monitoring preference, making service restarts fragile.

## Fix

- MainActivity restores services according to saved user-enabled state instead of turning them off.
- RecordingMonitorService no longer clears user intent during normal destruction/restart.
- Boot/app replacement restores Recording Monitoring when it was explicitly enabled; Wi-Fi remains off until explicitly started again.
- Pilot Camera broadcasts wake the enabled monitor and request an immediate selected-folder scan.
- Completed video paths are used as a fast hint only when they are inside the selected Recording folder.
- Periodic scanning and FileObserver processing remain independent fallbacks.
