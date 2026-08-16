package com.labpano.gpxextractor.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.labpano.gpxextractor.ui.MainActivity
import com.labpano.gpxextractor.util.AppLog

/**
 * Receives the same gallery broadcasts emitted by Labpano Camera 5.18.x from its recording
 * callbacks. These are status hints only; no camera control is performed by this receiver.
 */
class PilotCameraBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ACTION_FILE_CHANGE -> {
                val fragmentRestart = CameraRecordingStatusRegistry.onPilotFileChangeBroadcast()
                AppLog.info(
                    if (fragmentRestart) "Pilot Camera fragment restart signal received"
                    else "Pilot Camera media-change signal received"
                )
                requestMonitorAction(
                    context,
                    if (fragmentRestart) RecordingMonitorService.ACTION_PILOT_FRAGMENT_RESTART
                    else RecordingMonitorService.ACTION_RESCAN_RECORDING_FOLDER
                )
            }

            ACTION_SETTING_FILE_CHANGE -> {
                val appContext = context?.applicationContext ?: return
                // Opportunistic compatibility trigger only. Camera 5.18.11's StoragePartModel itself
                // posts an in-process EventBus ModifySettingEvent, not a guaranteed cross-app Android
                // broadcast. The reliable path is the direct /efs/video.properties FileObserver plus
                // elapsed-realtime polling; if this generic Pilot action appears, refresh immediately.
                PilotFragmentStorageLocalReader.refresh(appContext, force = true)
                PilotFragmentStorageSettingsReader.refresh(appContext, force = true)
                AppLog.info("Pilot Camera setting-change signal received; Fragment Storage refreshed")
            }

            ACTION_ADD_FILE -> {
                val path = intent.getStringExtra(EXTRA_FILE_PATH)
                val fileType = intent.getIntExtra(EXTRA_FILE_TYPE, -1)
                CameraRecordingStatusRegistry.onPilotAddFileBroadcast(path, fileType)
                if (CameraRecordingStatusRegistry.isLikelyVideoPath(path.orEmpty())) {
                    AppLog.info("Pilot Camera completed video signal: ${path.orEmpty()}")
                    requestMonitorAction(
                        context,
                        RecordingMonitorService.ACTION_PILOT_VIDEO_COMPLETED,
                        path
                    )
                } else {
                    requestMonitorAction(context, RecordingMonitorService.ACTION_RESCAN_RECORDING_FOLDER)
                }
            }
        }
    }

    private fun requestMonitorAction(context: Context?, action: String, completedPath: String? = null) {
        val appContext = context?.applicationContext ?: return
        val preferences = appContext.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(MainActivity.KEY_MONITORING_ENABLED, false)) return
        // Camera broadcasts are acceleration hints only. They must never start Monitoring from a
        // stopped process/session; the user explicitly starts Monitoring from MainActivity.
        if (!RecordingMonitorService.isRunningInProcess()) return

        val serviceIntent = Intent(appContext, RecordingMonitorService::class.java).setAction(action)
        completedPath?.takeIf { it.isNotBlank() }?.let {
            serviceIntent.putExtra(RecordingMonitorService.EXTRA_COMPLETED_VIDEO_PATH, it)
        }
        runCatching { appContext.startService(serviceIntent) }
            .onFailure { AppLog.error("Cannot signal running recording monitor for Pilot media event", it) }
    }

    companion object {
        const val ACTION_FILE_CHANGE = "com.pi.pilot.gallery.fileChange"
        const val ACTION_ADD_FILE = "com.pi.pilot.gallery.addFile"
        const val ACTION_SETTING_FILE_CHANGE = "com.pi.pilot.setting.fileChange"
        const val EXTRA_FILE_PATH = "filepath"
        const val EXTRA_FILE_TYPE = "fileType"
    }
}
