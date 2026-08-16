# Camera App 0.5.9 — MP4 storage write alerts

`OutputMover` now records destination-side MP4 failures at the point they occur. It covers local-file and Storage Access Framework output folders, classifies the target as internal/external/unknown storage, and records prepare/write/finalize/verification errors without treating source-change or cancellation events as storage failures.

The bounded persistent registry keeps up to 50 alerts for seven days so a Client App cannot miss a short-lived error between network polls. Dashboard API v3 now includes an additive `storageWriteAlerts` JSON array. Existing v3 clients that ignore unknown fields remain compatible.
