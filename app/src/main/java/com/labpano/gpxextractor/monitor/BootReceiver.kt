package com.labpano.gpxextractor.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.labpano.gpxextractor.ui.MainActivity
import com.labpano.gpxextractor.util.AppLog
import com.labpano.gpxextractor.wifi.WifiFileServerService

/**
 * Boot / app-update policy: never auto-connect and never auto-monitor.
 * The user must explicitly start Monitoring and Wi-Fi file access from the main UI after startup.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return

        val preferences = context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putBoolean(MainActivity.KEY_MONITORING_ENABLED, false)
            .putBoolean(MainActivity.KEY_WIFI_SERVER_ENABLED, false)
            .putString(RecordingMonitorService.KEY_LAST_STATUS, RecordingMonitorService.STATUS_IDLE)
            .apply()
        runCatching { context.stopService(Intent(context, RecordingMonitorService::class.java)) }
        runCatching { context.stopService(Intent(context, WifiFileServerService::class.java)) }
        CameraRecordingStatusRegistry.resetFileObservation()
        AppLog.info("Startup policy after ${intent.action}: monitoring=false; wifi=false")
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
