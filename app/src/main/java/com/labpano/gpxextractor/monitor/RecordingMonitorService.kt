package com.labpano.gpxextractor.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.labpano.gpxextractor.AppConfig
import com.labpano.gpxextractor.R
import com.labpano.gpxextractor.ui.MainActivity
import com.labpano.gpxextractor.util.AppLog
import java.io.File

/** Thin foreground-service shell. All filesystem work belongs to RecordingProcessingEngine. */
class RecordingMonitorService : Service() {
    private var observer: RecordingFileObserver? = null
    private var engine: RecordingProcessingEngine? = null
    private var terminalFailure = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(AppConfig.NOTIFICATION_ID, buildNotification())

        // Never display a successful status left over from a previous service run.
        publishStatus(STATUS_MONITORING)
        val directory = resolveRecordingDirectory()
        try {
            val processingEngine = RecordingProcessingEngine(this, directory, ::publishStatus)
            engine = processingEngine
            processingEngine.start()
            observer = RecordingFileObserver(directory, processingEngine::signal).also { it.startWatching() }
            publishStatus(STATUS_MONITORING)
            AppLog.info("Milestone 4 monitoring ${directory.absolutePath}")
        } catch (error: Throwable) {
            observer?.stopWatching()
            observer = null
            engine?.close()
            engine = null
            AppLog.error("Cannot start recording monitor", error)
            terminalFailure = true
            getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(MainActivity.KEY_MONITORING_ENABLED, false)
                .apply()
            publishStatus(STATUS_FAILED_PREFIX + (error.message ?: "startup"))
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        observer?.stopWatching()
        observer = null
        engine?.close()
        engine = null
        getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MainActivity.KEY_MONITORING_ENABLED, false)
            .apply()
        if (!terminalFailure) publishStatus(STATUS_IDLE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun resolveRecordingDirectory(): File {
        val preferences = getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val configured = preferences.getString(MainActivity.KEY_RECORDING_DIRECTORY, null)
        return configured?.takeIf { it.isNotBlank() }?.let(::File)
            ?: AppConfig.defaultRecordingDirectory
    }

    private fun publishStatus(status: String) {
        getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_STATUS, status)
            .apply()
        sendBroadcast(Intent(ACTION_STATUS_CHANGED).setPackage(packageName).putExtra(EXTRA_STATUS, status))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                AppConfig.NOTIFICATION_CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, AppConfig.NOTIFICATION_CHANNEL_ID)
        } else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STATUS_CHANGED = "com.labpano.gpxextractor.STATUS_CHANGED"
        const val EXTRA_STATUS = "status"
        const val KEY_LAST_STATUS = "last_monitor_status"
        const val STATUS_IDLE = "idle"
        const val STATUS_MONITORING = "monitoring"
        const val STATUS_PROCESSING_PREFIX = "processing:"
        const val STATUS_MOVED_PREFIX = "moved:"
        const val STATUS_FAILED_PREFIX = "failed:"
    }
}
