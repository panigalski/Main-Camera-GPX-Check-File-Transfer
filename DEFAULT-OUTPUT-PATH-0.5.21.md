# Default OUTPUT Path — 0.5.21

- Default/reset OUTPUT folder: `/sdcard/DCIM/Videos/Stitched`.
- New installs use this path immediately.
- Existing installs still using the previous default `/storage/emulated/0/videos/stitched` are migrated to the new default when no SAF output tree has been selected.
- A user-selected custom OUTPUT folder or SAF tree is preserved.
- OUTPUT layout remains unchanged: cumulative `GOOD.TXT`, `FAILED.TXT`, and `ERROR.TXT` at the OUTPUT root, with media/GPX under `dd-mm-yyyy/`.
