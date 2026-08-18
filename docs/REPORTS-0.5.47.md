# Report layout — Main App 0.5.47

Main App 0.5.47 keeps two report levels at the same time:

```text
OUTPUT/
├── GOOD.TXT
├── FAILED.TXT
├── ERROR.TXT
└── 17-08-2026/
    ├── GOOD/17-08-2026_GOOD.txt
    ├── FAILED/17-08-2026_FAILED.txt
    └── ERROR/17-08-2026_ERROR.txt
```

The root reports are cumulative across all dates. The dated reports contain only records for that recording date. Every committed recording is appended to both matching reports. No per-video TXT files are created.

When upgrading from 0.5.45, the app recreates any missing root report and backfills it from the existing daily reports, preserving the daily reports in place.


## Lazy report creation (0.5.47)

Report files are not placeholders. `GOOD.TXT`, `FAILED.TXT`, `ERROR.TXT` and the matching daily report are created only after the first recording with that status. Empty placeholder reports left by 0.5.46 are removed when no matching classified MP4 exists.
