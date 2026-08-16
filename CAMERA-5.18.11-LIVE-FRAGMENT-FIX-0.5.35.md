# Camera 5.18.11 Live Fragment Fix — Main App 0.5.35

## Hardware symptom fixed

- `Fragment Storage` was read correctly at Main-App startup but did not follow later changes made in the stock Camera UI.
- Completed 4/6/8/10 GB (or timed) fragments stayed in the Recording folder until the user stopped the entire Camera recording.

## Stock Camera APK findings

The supplied `Camera_5.18.11(2).apk` was inspected directly.

1. `StoragePartModel$select$1` writes the selected value through `Video.set(...)` to `/efs/video.properties` using the keys such as `video.storagePart.value`.
2. `StoragePartModel.toggleSwitch` writes `video.storagePart.able` to the same file.
3. `PropertiesUtil.set` loads the properties, sets the key and calls `saveProps`; `saveProps` opens the same path with `FileOutputStream`, stores/flushed the properties and closes it. Therefore the backing file changes immediately when the Camera UI setting changes.
4. The Camera UI posts `ModifySettingEvent` through greenrobot EventBus. That event is process-local and is not a reliable cross-app Android broadcast.
5. Camera 5.18.11 `VideoHelper.Divider` performs a low-level fragment stop and then `restart()`. The new fragment's `onRecordStart` callback invokes `PilotBroadcastSendHelper.notifyChangeFile`, so `com.pi.pilot.gallery.fileChange` is emitted again at every internal Fragment Storage restart.
6. Gallery `addFile` is on the final overall stop path, so waiting for it prevents rolling transfer.

## 0.5.35 behavior

- Watches `/efs/video.properties` directly with `FileObserver` and force-rereads the file on MODIFY/CLOSE_WRITE. A 750 ms elapsed-realtime poll remains as fallback.
- Publishes a monotonic Fragment Storage revision to the Client so a real setting change cannot be rejected because the Pilot system wall clock moved backwards.
- When Fragment Storage is enabled and a second `gallery.fileChange` arrives while a video is already latched, it is treated as Divider's fragment restart, not a new user recording generation.
- Because the stock Divider has already stopped the previous low-level recorder before that callback, the exact previous fragment is immediately released from Camera ownership and receives the short completed-fragment processing path.
- The overall Camera capture remains `Recording`; the next MP4 becomes the protected current fragment as soon as it appears.
- The filesystem rollover detector remains as a secondary independent fallback.

No Camera setting is modified by the Main App.
