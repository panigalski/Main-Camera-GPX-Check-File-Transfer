# Live OUTPUT Folder Update — 0.5.24

The Main App now commits a valid OUTPUT folder selection immediately instead of waiting for Monitoring to be restarted.

- Local writable selections are write-probed and GOOD.TXT / FAILED.TXT / ERROR.TXT are prepared before the preference is committed.
- SAF/removable-storage selections are committed after persistent permission and write verification succeed.
- `/api/v1/dashboard` includes `outputFolder`, read fresh from current preferences on every request.
- `RecordingProcessingEngine` already resolves OUTPUT preferences per transaction, so future transactions use the new folder without restarting Monitoring.
- A transfer already in progress stays in the destination captured at the start of that transaction for data safety.
