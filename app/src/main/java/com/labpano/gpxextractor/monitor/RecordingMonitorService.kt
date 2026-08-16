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

        // Do not publish MONITORING yet. The Activity keeps START MONITORING unchanged until the
        // selected folders have passed real access probes and this service has fully initialized.
        val directory = resolveRecordingDirectory()
        PilotFragmentStorageLocalReader.refresh(this, force = true)
        PilotFragmentStorageRegistry.refreshAsync(force = true)
        CameraRecordingStatusRegistry.resetFileObservation()
        try {
            val processingEngine = RecordingProcessingEngine(this, directory, ::publishStatus)
            engine = processingEngine
            processingEngine.start()
            observer = RecordingFileObserver(directory) { event, file ->
                // The engine maintains its own temporary baseline/current MP4 sequence. A new MP4
                // automatically releases only its predecessor; MODIFY/CLOSE events never do.
                processingEngine.signal(file, event)
            }.also { it.startWatching() }
            runningInProcess = true
            publishStatus(STATUS_MONITORING)
            AppLog.info("Milestone 4 monitoring ${directory.absolutePath}")
        } catch (error: Throwable) {
            observer?.stopWatching()
            observer = null
            engine?.close()
            engine = null
            AppLog.error("Cannot start recording monitor", error)
            terminalFailure = true
            runningInProcess = false
            getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(MainActivity.KEY_MONITORING_ENABLED, false)
                .apply()
            publishStatus(STATUS_FAILED_PREFIX + (error.message ?: "startup"))
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESCAN_RECORDING_FOLDER -> engine?.requestScan()
            // Divider broadcasts are status hints only. File movement waits for the next concrete
            // MP4 to appear in the Recording folder, exactly matching the A -> B sequence policy.
            ACTION_PILOT_FRAGMENT_RESTART -> engine?.requestScan()
            ACTION_PILOT_VIDEO_COMPLETED -> {
                val completedPath = intent.getStringExtra(EXTRA_COMPLETED_VIDEO_PATH).orEmpty()
                if (completedPath.isNotBlank()) {
                    val completedFile = File(completedPath)
                    if (isInsideRecordingDirectory(completedFile)) {
                        engine?.signal(completedFile)
                    } else {
                        AppLog.warn(
                            "Pilot completed video is outside selected Recording folder; " +
                                "selected=${resolveRecordingDirectory().absolutePath}; completed=$completedPath"
                        )
                    }
                }
                // addFile is used only as the final overall stop signal. Intermediate fragments are
                // released by the next MP4 appearing in the Recording folder, so they do not wait
                // for this callback. At final stop there is no successor, therefore release the
                // sequence's last active MP4 and take a fresh baseline for the next recording.
                engine?.finishCurrentRecordingSequence()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observer?.stopWatching()
        observer = null
        engine?.close()
        engine = null
        runningInProcess = false
        // Monitoring is explicitly manual-only. If this service is destroyed for any reason, clear
        // the requested flag so a later Camera broadcast cannot resurrect it behind the user's back.
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

    private fun isInsideRecordingDirectory(file: File): Boolean {
        val root = runCatching { resolveRecordingDirectory().canonicalFile }.getOrElse { resolveRecordingDirectory().absoluteFile }
        val candidate = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
        return candidate.parentFile == root
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
        const val ACTION_RESCAN_RECORDING_FOLDER = "com.labpano.gpxextractor.RESCAN_RECORDING_FOLDER"
        const val ACTION_PILOT_FRAGMENT_RESTART = "com.labpano.gpxextractor.PILOT_FRAGMENT_RESTART"
        const val ACTION_PILOT_VIDEO_COMPLETED = "com.labpano.gpxextractor.PILOT_VIDEO_COMPLETED"
        const val EXTRA_COMPLETED_VIDEO_PATH = "completed_video_path"
        const val EXTRA_STATUS = "status"
        const val KEY_LAST_STATUS = "last_monitor_status"
        const val STATUS_IDLE = "idle"
        const val STATUS_MONITORING = "monitoring"
        const val STATUS_PROCESSING_PREFIX = "processing:"
        const val STATUS_MOVED_PREFIX = "moved:"
        const val STATUS_FAILED_PREFIX = "failed:"

        @Volatile private var runningInProcess = false
        fun isRunningInProcess(): Boolean = runningInProcess
    }
}
