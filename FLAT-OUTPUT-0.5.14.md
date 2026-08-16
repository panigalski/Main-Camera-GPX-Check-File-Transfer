# Flat Output Layout — 0.5.14

## Behaviour

- Completed MP4 and GPX files are written directly to the selected OUTPUT root.
- When Recording/Monitoring and Output are the same physical folder, files are verified and adopted in place; they are not copied and then deleted.
- New dated/status media subfolders are not created.
- The three cumulative reports remain `GOOD.TXT`, `FAILED.TXT`, and `ERROR.TXT`.
- If Monitoring and Output are the same physical folder, daily `dd-MM-yyyy` report folders are not created.
- If Monitoring and Output are different locations, day-specific reports continue to live under the Monitoring folder only.
- Final processing markers are kept as long as their MP4 path still exists, so retained files in a shared Monitoring/Output root are not reprocessed after 180 days.

## Safety

Existing non-empty folders from older app versions are not recursively deleted or flattened automatically, which avoids moving or deleting previously stored user data without an explicit migration. New 0.5.14 transfers use the flat layout.
