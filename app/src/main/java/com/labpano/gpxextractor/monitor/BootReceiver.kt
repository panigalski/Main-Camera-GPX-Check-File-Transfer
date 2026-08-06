package com.labpano.gpxextractor.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.labpano.gpxextractor.ui.MainActivity
import com.labpano.gpxextractor.util.AppLog

/**
 * Keeps both optional runtime features disabled after boot or app replacement.
 * The user must start Monitoring and Wi-Fi file access explicitly from the UI.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return

        context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MainActivity.KEY_MONITORING_ENABLED, false)
            .putBoolean(MainActivity.KEY_WIFI_SERVER_ENABLED, false)
            .putString(RecordingMonitorService.KEY_LAST_STATUS, RecordingMonitorService.STATUS_IDLE)
            .apply()

        AppLog.info("Runtime services remain off after ${intent.action}")
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
