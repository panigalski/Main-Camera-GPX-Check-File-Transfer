package com.labpano.gpxextractor

import android.app.Application
import android.content.Context
import android.content.Intent
import com.labpano.gpxextractor.monitor.CameraRecordingStatusRegistry
import com.labpano.gpxextractor.monitor.PilotFragmentStorageFileObserver
import com.labpano.gpxextractor.monitor.PilotFragmentStorageSettingsReader
import com.labpano.gpxextractor.api.DeviceDiagnosticsRegistry
import com.labpano.gpxextractor.monitor.RecordingMonitorService
import com.labpano.gpxextractor.ui.MainActivity
import com.labpano.gpxextractor.wifi.WifiFileServerService
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Installs process-wide crash logging before any activity or service starts. */
class LabpanoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A fresh app process always starts disconnected and idle. Runtime services are started only
        // by an explicit button press in MainActivity; no previous enabled state is restored.
        val preferences = getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putBoolean(MainActivity.KEY_MONITORING_ENABLED, false)
            .putBoolean(MainActivity.KEY_WIFI_SERVER_ENABLED, false)
            .putString(RecordingMonitorService.KEY_LAST_STATUS, RecordingMonitorService.STATUS_IDLE)
            .apply()
        runCatching { stopService(Intent(this, RecordingMonitorService::class.java)) }
        runCatching { stopService(Intent(this, WifiFileServerService::class.java)) }
        CameraRecordingStatusRegistry.resetFileObservation()
        PilotFragmentStorageFileObserver.ensureWatching(this)
        PilotFragmentStorageSettingsReader.refresh(this, force = true)
        DeviceDiagnosticsRegistry.ensureStarted(this)
        installCrashLogger()
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrashReport(thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun writeCrashReport(thread: Thread, error: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val trace = StringWriter().also { writer ->
            error.printStackTrace(PrintWriter(writer))
        }.toString()
        val report = buildString {
            append("\n=== CRASH ").append(timestamp).append(" ===\n")
            append("App version: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Thread: ").append(thread.name).append(" [").append(thread.id).append("]\n")
            append("Exception: ").append(error.javaClass.name).append(": ")
                .append(error.message ?: "").append("\n")
            append(trace).append("\n")
        }

        // Keep diagnostics private. The shared recording/output root is intentionally limited to
        // Root GOOD.TXT, FAILED.TXT and ERROR.TXT plus date/status recording media/GPX files.
        val target = File(filesDir, "CRASH.TXT")
        runCatching {
            target.parentFile?.mkdirs()
            rotateCrashLogIfNeeded(target)
            target.appendText(report, Charsets.UTF_8)
        }
    }

    private fun rotateCrashLogIfNeeded(target: File) {
        if (!target.isFile || target.length() <= MAX_CRASH_LOG_BYTES) return
        val backup = File(target.parentFile, "CRASH-PREVIOUS.TXT")
        if (backup.exists()) backup.delete()
        if (!target.renameTo(backup)) {
            // A failed rotation must never prevent the current crash from being recorded.
            target.writeText("", Charsets.UTF_8)
        }
    }

    companion object {
        private const val MAX_CRASH_LOG_BYTES = 2L * 1024L * 1024L
    }
}
