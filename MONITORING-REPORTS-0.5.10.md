# Camera App 0.5.10 — cumulative and daily monitoring reports

The monitoring folder now has two reporting layers:

- `GOOD.TXT`, `FAILED.TXT`, `ERROR.TXT` at the monitoring root are cumulative across all dates and are not automatically trimmed.
- `dd-MM-yyyy/GOOD.TXT`, `FAILED.TXT`, `ERROR.TXT` contain only records assigned to that processing date.

For each finalized GOOD, FAILED or ERROR transaction, the same logical record is committed to both the cumulative root report and the matching daily report. Transaction-ID checks keep recovery idempotent if the app stops between the two writes.

Dated MP4/GPX media folders remain in the selected output location. Dated TXT files are no longer written into the output location.
