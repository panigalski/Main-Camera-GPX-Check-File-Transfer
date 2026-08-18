# Current Output Folder contract

Main App 0.5.47 uses a **date-first** classified output structure while retaining cumulative reports in the OUTPUT root:

```text
OUTPUT/
├── GOOD.TXT                  # only after at least one GOOD recording
├── FAILED.TXT                # only after at least one FAILED recording
├── ERROR.TXT                 # only after at least one ERROR recording
└── dd-MM-yyyy/
    ├── GOOD/
    │   ├── dd-MM-yyyy_GOOD.txt   # only if GOOD occurred that day
    │   ├── <video>.mp4
    │   ├── <video>.gpx
    │   └── <video>_backup.gpx
    ├── FAILED/
    │   ├── dd-MM-yyyy_FAILED.txt # only if FAILED occurred that day
    │   ├── <video>.mp4
    │   ├── <video>.gpx
    │   └── <video>_backup.gpx
    └── ERROR/
        ├── dd-MM-yyyy_ERROR.txt  # only if ERROR occurred that day
        ├── <video>.mp4
        ├── <video>.gpx             # when extraction produced a valid GPX
        └── <video>_backup.gpx      # when supplied by Client Automatic Backup
```

For example, recordings made on 17 August 2026 use:

- `OUTPUT/17-08-2026/GOOD/17-08-2026_GOOD.txt`
- `OUTPUT/17-08-2026/FAILED/17-08-2026_FAILED.txt`
- `OUTPUT/17-08-2026/ERROR/17-08-2026_ERROR.txt`

and the cumulative copies remain at:

- `OUTPUT/GOOD.TXT`
- `OUTPUT/FAILED.TXT`
- `OUTPUT/ERROR.TXT`

## Classification

- **GOOD:** largest consecutive real CAMM gap is at most 5.000 seconds.
- **FAILED:** largest consecutive real CAMM gap is greater than 5.000 seconds.
- **ERROR:** extraction, validation or permanent processing failure.

Classification is determined before GPX densification. Interpolation cannot convert a recording with a real gap over 5 seconds into GOOD.

## Reports

- Root reports are cumulative across all recording days, but each one is created only after the first recording of that status: no GOOD recordings means no `GOOD.TXT`, and likewise for FAILED/ERROR.
- A daily `dd-MM-yyyy_<STATUS>.txt` report is created only after the first recording of that status on that date. Empty status reports are never pre-created.
- Each committed recording is appended to both the matching root cumulative report and matching daily report.
- There are **no per-recording/per-segment TXT files**.
- Deleting a report entry through the API removes the entry from both report levels.
- When upgrading from 0.5.45, newly-created cumulative root reports are backfilled from the existing daily reports.
- If extraction fails before a valid Camera GPX exists, ERROR does not create a fake `.gpx` file.

## Client backup upload

Client 1.10.32 sends only per-video `_backup.gpx` files. Main resolves each upload to the matching `OUTPUT/dd-MM-yyyy/<STATUS>/` directory and verifies the stored file before acknowledging success.

## Temporary PROCESSING folder cleanup

`PROCESSING` is an internal state, not an Output Folder classification. Main App 0.5.47 does not create `OUTPUT/dd-MM-yyyy/PROCESSING/`. Empty legacy `PROCESSING` directories are removed automatically. A non-empty legacy directory is preserved to avoid deleting unknown user data.
