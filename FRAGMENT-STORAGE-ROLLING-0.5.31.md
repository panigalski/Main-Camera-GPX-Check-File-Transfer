# Main App 0.5.31 — Fragment Storage Rolling Processing

## What changed

Main App now treats Pilot One **Fragment Storage** as a camera setting and a per-file lifecycle, rather than treating one Camera recording session as one indivisible MP4.

### Camera setting collection

The app performs a read-only `camera.getOptions` request against the Pilot control service and asks for the current value plus the support table for:

- Stitched video: `_camera$video$storagePart` / `_camera$video$storagePartSupport`
- Unstitched/FishEye video: `_camera$videoFishEye$storagePart` / `_camera$videoFishEye$storagePartSupport`
- Street View video: `_camera$videoStreetView$storagePart` / `_camera$videoStreetView$storagePartSupport`
- Time Lapse video: `_camera$videoTimeLapse$storagePart` / `_camera$videoTimeLapse$storagePartSupport`

The Camera's support table supplies the display label, so firmware labels such as `10 min`, `30 min`, `1 hour`, `2 hours`, `4 GB`, `6 GB`, `8 GB`, and `10 GB` do not need to be duplicated/hard-coded in this app. An empty current value is reported as `Off (Unlimited)`.

The query is read-only. Main App does not call `camera.startSession`, does not change the selected Camera mode, and does not write the Fragment Storage option.

For the selected Stitched recording folder, the API exposes both Stitched and Street View values when they differ because both modes may use the Stitched media tree and the passive option query does not take control of the Camera to determine its active UI mode.

### Rolling fragment ownership

With Fragment Storage enabled:

1. The currently open fragment is protected from parsing, moving and deletion.
2. A matching Camera `addFile` marks that exact fragment complete and releases it to the processing engine immediately. The existing 2-second completed-media settle gate and MP4 readiness validation still apply before a move.
3. If the next distinct MP4 appears first, that rollover also releases the previous fragment after the readiness/stability gate and hands active ownership to the new fragment.
4. The Camera recording lifecycle generation does **not** change across fragment A -> B -> C, so the Client remains `Recording` during rollover.
5. If a segmented `addFile` is not followed by another fragment, a 5-second continuation grace converts that event into the final overall Stop/Ready transition. This grace affects display state only; the completed final fragment is already eligible for processing.
6. A newer real Camera `fileChange` generation always wins over fragment handoff, preventing back-to-back recordings from being merged accidentally.

If the read-only Fragment Storage query is temporarily unavailable, a matching `addFile` followed by a distinct next MP4 is accepted as a concrete rollover boundary. If the Camera setting is successfully known to be `Off (Unlimited)`, ordinary one-file behavior is retained and the matching `addFile` completes the recording immediately.

### Dashboard/API

Both the full dashboard and `/api/v1/live-status` now include additive `fragmentStorage` data:

- `available`
- `enabled`
- `display`
- `updatedAt`
- `source`
- `error`
- `stitched`, `streetView`, `unstitched`, `timeLapse` mode objects with `known`, `enabled`, `rawValue`, `displayValue`

Older clients can ignore this additive field.

## START MONITORING button

`START MONITORING` no longer changes to `STOP MONITORING` merely because service startup was requested.

Before service startup, Main App performs real create/write/read/delete access probes on local folders (or persisted SAF access probes for a document-tree output), and verifies/creates `GOOD.TXT`, `FAILED.TXT`, and `ERROR.TXT` in OUTPUT. During service startup the button remains labelled `START MONITORING` and is temporarily disabled. It changes to `STOP MONITORING` only after the processing engine and Recording-folder `FileObserver` have initialized and the service reports itself running in-process.
