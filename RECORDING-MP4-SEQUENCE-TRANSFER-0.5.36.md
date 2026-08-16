# Recording MP4 Sequence Transfer — Main App 0.5.36

## Requested rule

This release intentionally ignores Fragment Storage when deciding whether a recording fragment may move.

1. When Monitoring starts, capture a temporary baseline list of every `.mp4` already in the selected Recording folder.
2. The first new `.mp4` after that baseline is file **A**. A is the active/current recording file and is protected from parsing, deletion and movement.
3. Keep watching the Recording folder.
4. When a distinct new `.mp4` **B** is created, release only A. B becomes the new protected active file.
5. A must remain unchanged for the short completed-file settling guard; the engine then runs the normal CAMM/GPS extraction, GPS-gap validation, GPX/report creation and OUTPUT move.
6. When **C** appears, release B. Repeat for every successor while the overall Camera recording continues.
7. At final overall Camera stop there is no successor for the last fragment, so the final Camera `addFile` signal releases the last active MP4 and resets the baseline for the next recording.

## Safety properties

- The newest discovered MP4 is always protected even if Camera recording-status heuristics are stale.
- A predecessor released by a successor can bypass stale Camera ownership for that exact file only.
- Release never occurs from `MODIFY`, `CLOSE_WRITE`, Fragment Storage values, or Divider broadcasts alone.
- MP4 `CREATE`/`MOVED_TO` event order is the primary discovery path. A 5-second directory scan supplies a fallback if FileObserver drops an event.
- The predecessor waits at least 2 seconds after release and must remain size/mtime-stable.
- Immutable file snapshots are checked before processing and after CAMM parsing. If MP4 parsing shows incomplete finalization, a released fragment uses a short re-settle/retry rather than the generic 30-second backoff or moving a changing file.
- Fragment Storage display remains untouched in 0.5.36 and is not consulted by this policy.

## Expected 4 GB example

With Camera Fragment Storage set to 4 GB:

- A is created and grows: A stays in Recording.
- B is created at rollover: A is released; B is protected.
- After A settles, A is checked for GPS gaps and moved to the appropriate dated GOOD/FAILED/ERROR subfolder while B continues recording.
- C is created: B is released and handled the same way.
- User stops recording while C is active: final Camera addFile releases C; after settling, C is processed/moved.
