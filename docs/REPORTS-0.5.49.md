# Report layout — Main App 0.5.49

Main App 0.5.49 keeps two report levels at the same time:

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


## Global transfer totals (0.5.49)

Each cumulative root report begins with a comment-prefixed summary block. Example:

```text
# TRANSFER SUMMARY
# Files transferred: 125
# Video recording hours transferred: 8.742
# Data transferred: 96.314 GB
# ------------------------------------------------------------
```

`Files transferred` counts classified MP4 video segments. `Video recording hours transferred` sums the canonical MP4 movie intervals. `Data transferred` sums MP4 bytes only and uses decimal GB (1,000,000,000 bytes). GPX sidecar bytes are intentionally excluded.

Summary lines are filtered from dashboard/report-entry APIs, so existing Client report parsing and deletion continue to operate on the normal tab-separated detail records. New 0.5.49 detail records include machine-readable transfer metrics so recovery/retry cannot double-count totals. Upgraded legacy records are counted as files; duration is recovered from existing `videoStartUtc`/`videoEndUtc` fields when present, and direct-filesystem MP4 sizes are recovered from their destination paths when possible. If old records cannot be measured, the summary adds a `Statistics coverage` line instead of inventing data.
