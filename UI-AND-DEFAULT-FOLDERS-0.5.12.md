# UI and default folders — 0.5.12

- All main-app buttons now use the same full-width, 52 dp, rounded 3D shape.
- START/STOP and Wi-Fi controls keep the violet action color while using the same geometry and pressed depth as the blue controls.
- Recording BROWSE, Output BROWSE, ADVANCED, RESET RECORDING FOLDER, and RESET OUTPUT FOLDER use the matching light-blue 3D treatment.
- Buttons inside the ADVANCED section are now the same width and height as every other main control.
- Both recording and output defaults/reset targets are the internal shared-storage folder `videos/stitched` (normally `/storage/emulated/0/videos/stitched`).
- Reset actions now persist immediately.
- Recording and output may use the same root folder; completed items are still moved into dated status subfolders, while the recorder watcher remains root-only.
