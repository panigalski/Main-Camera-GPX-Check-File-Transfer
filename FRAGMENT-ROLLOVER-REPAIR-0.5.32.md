# Fragment Storage rollover repair — Main App 0.5.32

## What 0.5.31 still got wrong

Two independent assumptions could block the requested behavior on Pilot One:

1. Fragment Storage collection requested all recording-mode option families together. Firmware can reject an unsupported family or return only a subset, so a valid Stitched setting such as 4 GB could be lost and the Client showed `Unavailable`.
2. Releasing a completed fragment still depended too heavily on `camera.getOptions` and/or the Camera `addFile` broadcast. A real filesystem rollover could therefore occur while the previous MP4 remained protected until the user stopped the whole recording.

## 0.5.32 behavior

- Queries current Fragment Storage values individually, with Stitched first; a batch of only still-missing names is used as a compatibility fallback. Support/display tables are also filled individually when missing.
- Tries the command-specific object form documented for `camera.getOptions`, with the generic stringified interface-input form as compatibility fallback.
- If direct reads fail, performs a short-lived `alone=true` protocol session only to read the options, then closes that session. No Camera setting or recording command is issued.
- Only an empty current value is interpreted as `Off (Unlimited)`. Null/unsupported values remain unknown.
- Recognizes common size/time raw forms and maps them to the Camera-facing labels.
- Most importantly, transfer safety is independent of the setting read: while one Camera recording generation is active, appearance of a distinct next MP4/STI writer in the same directory and video family is a concrete fragment rollover. The old fragment becomes completed/processable; the new fragment becomes the only protected file; the overall recording generation does not change.
- `.part` and `.tmp` next-writer CREATE/MOVED_TO events participate in rollover detection and trigger an immediate completed-folder rescan.
- If Pilot's control endpoint remains inaccessible, the first concrete rollover can infer the configured 4/6/8/10 GB or 10/30/60/120 minute threshold when the completed fragment is close enough to a known boundary. The UI labels this as `(observed)` instead of pretending it came from the protocol.

## Expected 4 GB sequence

1. Fragment A is the active Camera-owned MP4 and is protected.
2. At the Camera's 4 GB rollover, fragment B appears (final name or temporary writer alias).
3. Main App marks A completed, keeps B protected, and keeps Camera status Recording.
4. The processing engine wakes immediately; after the existing short completed-media settling/readiness check, A can generate GPX/report data and move to OUTPUT while B keeps recording.
5. B -> C repeats the same way without requiring Stop.

The currently open fragment is never moved. Only a fragment superseded/completed by the Camera is released.
