package com.labpano.gpxextractor.api

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.labpano.gpxextractor.AppConfig
import com.labpano.gpxextractor.AppProcessClock
import com.labpano.gpxextractor.BuildConfig
import com.labpano.gpxextractor.report.GlobalOutputReportStore
import com.labpano.gpxextractor.monitor.CameraRecordingStatusRegistry
import com.labpano.gpxextractor.monitor.RecordingMonitorService
import com.labpano.gpxextractor.monitor.PilotFragmentStorageRegistry
import com.labpano.gpxextractor.monitor.PilotCameraModeRegistry
import com.labpano.gpxextractor.monitor.PilotFragmentStorageLocalReader
import com.labpano.gpxextractor.monitor.PilotFragmentStorageFileObserver
import com.labpano.gpxextractor.monitor.PilotFragmentStorageSettingsReader
import com.labpano.gpxextractor.monitor.RecordingStatusObserverManager
import com.labpano.gpxextractor.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DashboardApi {
    /**
     * Builds the full dashboard. When [forceCameraSettingsRefresh] is true this request is the
     * Client connection handshake: read Camera 5.18.11's persisted Fragment Storage settings
     * synchronously before returning JSON so the first Client frame is not populated from an
     * earlier poll/cache. Normal periodic dashboard requests keep the low-I/O throttled refresh.
     */
    fun build(context: Context, forceCameraSettingsRefresh: Boolean = false): String {
        PilotFragmentStorageFileObserver.ensureWatching(context)
        PilotFragmentStorageLocalReader.refresh(context, force = forceCameraSettingsRefresh)
        PilotFragmentStorageSettingsReader.refresh(context, force = forceCameraSettingsRefresh)
        val monitoringPath = monitoringPath(context)
        val directory = File(monitoringPath)
        val reportStore = GlobalOutputReportStore(context)

        val reportReadErrors = mutableListOf<String>()
        val errorReport = readReport(reportStore, com.labpano.gpxextractor.data.ProcessingStatus.ERROR, reportReadErrors)
        val failedReport = readReport(reportStore, com.labpano.gpxextractor.data.ProcessingStatus.FAILED, reportReadErrors)
        val goodReport = readReport(reportStore, com.labpano.gpxextractor.data.ProcessingStatus.GOOD, reportReadErrors)

        return JSONObject().apply {
            put("apiVersion", 3)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("generatedAt", System.currentTimeMillis())
            put("generatedElapsedRealtime", AppProcessClock.nowElapsedRealtime())
            put("processStartedElapsedRealtime", AppProcessClock.processStartedElapsedRealtime)
            put("processInstanceId", AppProcessClock.processInstanceId)
            put("monitoringDirectory", monitoringPath)
            put("outputFolder", outputPath(context))
            put("monitoring", monitoringJson(context))
            put("internalStorage", internalStorageJson())
            put("externalStorage", externalStorageJson(context) ?: JSONObject.NULL)
            put("battery", batteryJson(context))
            put("cameraRecording", cameraRecordingJson(directory))
            put("fragmentStorage", fragmentStorageJson(context, connectionSynced = forceCameraSettingsRefresh))
            put("error", errorReport)
            put("failed", failedReport)
            put("good", goodReport)
            // Build health after all report reads and preserve any error from this exact dashboard poll.
            put("reportHealth", reportHealthJson(reportStore, reportReadErrors))
            put("transfers", TransferProgressRegistry.toJson())
            put("storageWriteAlerts", StorageWriteAlertRegistry.toJson(context))
            put("deviceDiagnostics", safeDeviceDiagnosticsJson(context))
        }.toString()
    }

    /**
     * Small high-frequency payload for the companion Client.
     *
     * The full dashboard includes report tails, storage statistics, battery/thermal data and device
     * diagnostics. Rebuilding all of that several times per second is unnecessary and can delay a
     * recording-state transition. This endpoint contains only live UI state and is safe to poll at
     * sub-second cadence.
     */
    fun buildLiveStatus(context: Context): String {
        PilotFragmentStorageFileObserver.ensureWatching(context)
        PilotFragmentStorageLocalReader.refresh(context)
        PilotFragmentStorageSettingsReader.refresh(context)
        val monitoringPath = monitoringPath(context)
        val directory = File(monitoringPath)
        RecordingStatusObserverManager.ensureWatching(directory)
        val recording = CameraRecordingStatusRegistry.fastSnapshot()
        return JSONObject().apply {
            put("apiVersion", 3)
            put("generatedAt", System.currentTimeMillis())
            put("generatedElapsedRealtime", AppProcessClock.nowElapsedRealtime())
            put("processStartedElapsedRealtime", AppProcessClock.processStartedElapsedRealtime)
            put("processInstanceId", AppProcessClock.processInstanceId)
            put("outputFolder", outputPath(context))
            put("monitoring", monitoringJson(context))
            put("cameraRecording", cameraRecordingJson(recording))
            put("fragmentStorage", fragmentStorageJson(context))
            put("transfers", TransferProgressRegistry.toJson())
        }.toString()
    }

    fun deleteEntry(
        context: Context,
        reportType: String,
        timestamp: String,
        path: String,
        message: String
    ): DeleteResult {
        val status = when (reportType.lowercase()) {
            "error" -> com.labpano.gpxextractor.data.ProcessingStatus.ERROR
            "failed" -> com.labpano.gpxextractor.data.ProcessingStatus.FAILED
            "good" -> com.labpano.gpxextractor.data.ProcessingStatus.GOOD
            else -> return DeleteResult(false, 400, "Unknown report type")
        }
        val expected = "$timestamp\t$path\t$message"
        val result = GlobalOutputReportStore(context).deleteExactLine(status, expected)
        return DeleteResult(result.deleted, result.statusCode, result.message)
    }


    private fun safeDeviceDiagnosticsJson(context: Context): JSONObject {
        return runCatching { DeviceDiagnosticsRegistry.toJson(context) }.getOrElse { error ->
            JSONObject().apply {
                put("error", error.message ?: error.javaClass.simpleName)
                put("bluetooth", JSONObject().apply {
                    put("available", false)
                    put("enabled", false)
                    put("devices", JSONArray())
                    put("error", "Diagnostics unavailable")
                })
                put("location", JSONObject().apply {
                    put("available", false)
                    put("permissionGranted", false)
                    put("fresh", false)
                    put("sourceType", "UNKNOWN")
                    put("sourceLabel", "Diagnostics unavailable")
                    put("provider", "")
                    put("mocked", false)
                    put("lastFixAt", 0L)
                })
                put("gnss", JSONObject().apply {
                    put("supported", false)
                    put("running", false)
                    put("fresh", false)
                    put("satellitesVisible", 0)
                    put("satellitesUsedInFix", 0)
                    put("updatedAt", 0L)
                })
            }
        }
    }

    private fun cameraRecordingJson(recordingDirectory: File): JSONObject {
        // Keep recording status alive even when the processing monitor is OFF. The companion Client
        // is allowed to connect independently from START MONITORING.
        RecordingStatusObserverManager.ensureWatching(recordingDirectory)
        return cameraRecordingJson(CameraRecordingStatusRegistry.snapshot(recordingDirectory))
    }

    private fun cameraRecordingJson(snapshot: CameraRecordingStatusRegistry.Snapshot): JSONObject {
        return JSONObject().apply {
            put("available", snapshot.available)
            // Client-facing capture state. The internal processing gate remains conservative until
            // Camera finalization/addFile completes.
            put("recording", snapshot.captureRecording)
            put("finalizing", snapshot.finalizing)
            put("videoName", snapshot.videoName)
            put("updatedAt", snapshot.updatedAt)
            put("source", snapshot.source)
            put("generation", snapshot.lifecycleGeneration)
        }
    }

    private fun fragmentStorageJson(context: Context, connectionSynced: Boolean = false): JSONObject {
        // Refreshers run immediately before this method. The selected mode is NOT inferred from the
        // Main App monitoring directory: /DCIM/Videos/Stitched is shared by normal stitched and
        // Google Street View recordings. Instead, use the exact mode-specific Camera property that
        // most recently changed, with an active Unstitched path as an unambiguous live override.
        val snapshot = PilotFragmentStorageRegistry.snapshot()
        val modeHint = PilotCameraModeRegistry.snapshot(
            context = context,
            activeVideoPath = CameraRecordingStatusRegistry.activeVideoPath()
        )
        val selected = PilotFragmentStorageRegistry.selectedForMode(modeHint.mode, snapshot)
        val selectedKnown = selected?.setting?.known == true
        val limit = selected?.limit
        return JSONObject().apply {
            // Even when Camera's idle recording family is not externally observable, per-mode
            // settings are concrete and useful. Keep available=true so the Client can render all
            // three values rather than preserving a stale formerly-selected value.
            put("available", snapshot.available)
            put("connectionSynced", connectionSynced)
            put("enabled", selectedKnown && selected!!.setting.enabled)
            put(
                "display",
                when {
                    selectedKnown -> limit!!.display
                    snapshot.available -> "See per-mode Fragment Storage values"
                    else -> "Unavailable"
                }
            )
            put("mode", modeHint.mode)
            put("modeSource", modeHint.source)
            put("modeUpdatedAt", modeHint.updatedAt)
            put("rawValue", if (selectedKnown) selected!!.setting.rawValue else "")
            put("limitType", if (selectedKnown) limit!!.type else "unknown")
            put("sizeGb", if (selectedKnown) limit!!.sizeGb ?: JSONObject.NULL else JSONObject.NULL)
            put("durationMinutes", if (selectedKnown) limit!!.durationMinutes ?: JSONObject.NULL else JSONObject.NULL)
            put("updatedAt", maxOf(snapshot.updatedAt, modeHint.updatedAt))
            put("revision", snapshot.revision)
            put("processStartedElapsedRealtime", PilotFragmentStorageRegistry.processStartedElapsedRealtime)
            put("source", snapshot.source)
            put("error", snapshot.error)
            put("stitched", fragmentStorageModeJson(snapshot.stitched))
            put("streetView", fragmentStorageModeJson(snapshot.streetView))
            put("unstitched", fragmentStorageModeJson(snapshot.unstitched))
            put("timeLapse", fragmentStorageModeJson(snapshot.timeLapse))
        }
    }

    private fun fragmentStorageModeJson(mode: PilotFragmentStorageRegistry.ModeSetting): JSONObject {
        val limit = PilotFragmentStorageRegistry.limitValue(mode)
        return JSONObject().apply {
            put("known", mode.known)
            put("enabled", mode.enabled)
            put("rawValue", mode.rawValue)
            put("displayValue", limit.display)
            put("limitType", limit.type)
            put("sizeGb", limit.sizeGb ?: JSONObject.NULL)
            put("durationMinutes", limit.durationMinutes ?: JSONObject.NULL)
        }
    }

    private fun batteryJson(context: Context): JSONObject {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return JSONObject().apply {
                    put("available", false)
                    put("error", "Battery status unavailable")
                }

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) {
                ((level * 100f) / scale).toInt().coerceIn(0, 100)
            } else {
                -1
            }
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val officialTemperatureC = readOfficialPilotTemperatureC()
            val voltageMillivolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)

            JSONObject().apply {
                put("available", percent >= 0)
                put("percent", percent)
                put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
                put("full", status == BatteryManager.BATTERY_STATUS_FULL)
                put("status", batteryStatusName(status))
                put("powerSource", powerSourceName(plugged))
                put("temperatureC", officialTemperatureC ?: JSONObject.NULL)
                put("temperatureSource", if (officialTemperatureC != null) "Labpano thermal_zone0" else "Unavailable")
                put("voltageMillivolts", voltageMillivolts)
                put("health", batteryHealthName(health))
            }
        } catch (error: Throwable) {
            JSONObject().apply {
                put("available", false)
                put("percent", -1)
                put("charging", false)
                put("full", false)
                put("status", "Unknown")
                put("powerSource", "None")
                put("temperatureC", JSONObject.NULL)
                put("temperatureSource", "Unavailable")
                put("voltageMillivolts", -1)
                put("health", "Unknown")
                put("error", error.message ?: error.javaClass.simpleName)
            }
        }
    }


    /**
     * Uses the same thermal source as Labpano's official Pilot Open API
     * (CpuTemperatureReader): /sys/class/thermal/thermal_zone0/temp.
     * The Pilot API documents the value as degrees Celsius multiplied by 1000.
     */
    private fun readOfficialPilotTemperatureC(): Double? {
        return try {
            val raw = File("/sys/class/thermal/thermal_zone0/temp")
                .readText(Charsets.UTF_8)
                .trim()
                .toDoubleOrNull()
                ?: return null
            (raw / 1000.0).takeIf { it in -40.0..150.0 }
        } catch (_: Throwable) {
            null
        }
    }

    private fun batteryStatusName(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }

    private fun powerSourceName(plugged: Int): String = when {
        plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC"
        plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
        plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "Wireless"
        else -> "Battery"
    }

    private fun batteryHealthName(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        else -> "Unknown"
    }

    private fun externalStorageJson(context: Context): JSONObject? {
        val internalPath = runCatching { Environment.getExternalStorageDirectory().canonicalPath }
            .getOrElse { Environment.getExternalStorageDirectory().absolutePath }

        val candidates = linkedSetOf<File>()
        context.getExternalFilesDirs(null).orEmpty().drop(1).forEach { appDirectory ->
            if (appDirectory != null) {
                val path = appDirectory.absolutePath
                val marker = "/Android/"
                val rootPath = path.substringBefore(marker, missingDelimiterValue = "")
                if (rootPath.isNotBlank()) candidates += File(rootPath)
            }
        }
        File("/storage").listFiles().orEmpty().forEach { directory ->
            if (directory.isDirectory &&
                !directory.name.equals("emulated", ignoreCase = true) &&
                !directory.name.equals("self", ignoreCase = true) &&
                !directory.name.equals("enc_emulated", ignoreCase = true)
            ) candidates += directory
        }

        return candidates
            .asSequence()
            .filter { directory ->
                runCatching { directory.canonicalPath }.getOrElse { directory.absolutePath } != internalPath
            }
            .mapNotNull { directory ->
                runCatching {
                    val stats = StatFs(directory.absolutePath)
                    val blockSize = stats.blockSizeLong
                    val total = stats.blockCountLong * blockSize
                    val free = stats.availableBlocksLong * blockSize
                    val used = (total - free).coerceAtLeast(0L)
                    if (total <= 0L) null else JSONObject().apply {
                        put("path", directory.absolutePath)
                        put("totalBytes", total)
                        put("freeBytes", free)
                        put("usedBytes", used)
                        put("usedPercent", ((used * 100L) / total).toInt())
                    }
                }.getOrNull()
            }
            .firstOrNull()
    }

    private fun internalStorageJson(): JSONObject {
        val directory = Environment.getExternalStorageDirectory()
        return try {
            val stats = StatFs(directory.absolutePath)
            val blockSize = stats.blockSizeLong
            val total = stats.blockCountLong * blockSize
            val free = stats.availableBlocksLong * blockSize
            val used = (total - free).coerceAtLeast(0L)
            JSONObject().apply {
                put("path", directory.absolutePath)
                put("totalBytes", total)
                put("freeBytes", free)
                put("usedBytes", used)
                put("usedPercent", if (total > 0L) ((used * 100L) / total).toInt() else 0)
            }
        } catch (error: Throwable) {
            JSONObject().apply {
                put("path", directory.absolutePath)
                put("totalBytes", 0L)
                put("freeBytes", 0L)
                put("usedBytes", 0L)
                put("usedPercent", 0)
                put("error", error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun monitoringPath(context: Context): String {
        val preferences = context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
        return preferences.getString(MainActivity.KEY_RECORDING_DIRECTORY, null)
            ?.takeIf { it.isNotBlank() && !it.startsWith("content://") }
            ?: AppConfig.defaultRecordingDirectory.absolutePath
    }

    /** Current selected OUTPUT folder, read fresh for every dashboard request. */
    private fun outputPath(context: Context): String {
        val preferences = context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
        return preferences.getString(MainActivity.KEY_OUTPUT_DIRECTORY, null)
            ?.takeIf { it.isNotBlank() && !it.startsWith("content://") }
            ?: preferences.getString(MainActivity.KEY_OUTPUT_TREE_URI, null)
                ?.takeIf { it.isNotBlank() }
            ?: AppConfig.defaultOutputDirectory.absolutePath
    }

    private fun readReport(
        store: GlobalOutputReportStore,
        status: com.labpano.gpxextractor.data.ProcessingStatus,
        readErrors: MutableList<String>
    ): JSONArray {
        val array = JSONArray()
        val lines = try {
            store.readTail(status, MAX_REPORT_ENTRIES)
        } catch (error: Throwable) {
            readErrors += "${status.name} reports: ${error.message ?: error.javaClass.simpleName}"
            emptyList()
        }
        lines.forEach { line ->
            val parts = line.split('\t', limit = 3)
            array.put(JSONObject().apply {
                put("timestamp", parts.getOrElse(0) { "" })
                put("path", parts.getOrElse(1) { "" })
                put("message", parts.getOrElse(2) { "" })
            })
        }
        return array
    }

    private fun monitoringJson(context: Context): JSONObject {
        val preferences = context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("requested", preferences.getBoolean(MainActivity.KEY_MONITORING_ENABLED, false))
            put("serviceRunning", RecordingMonitorService.isRunningInProcess())
            put(
                "lastStatus",
                preferences.getString(RecordingMonitorService.KEY_LAST_STATUS, RecordingMonitorService.STATUS_IDLE)
                    ?: RecordingMonitorService.STATUS_IDLE
            )
        }
    }

    private fun reportHealthJson(store: GlobalOutputReportStore, readErrors: List<String>): JSONObject {
        val health = store.healthSnapshot()
        val persistedIoHealthy = health.lastFailureAt <= health.lastSuccessAt || health.lastFailureAt == 0L
        val currentReadError = readErrors.joinToString(" | ")
        return JSONObject().apply {
            put("destination", health.destination)
            put("destinationType", health.destinationType)
            put("available", health.available)
            put("writable", health.writable)
            put("ioHealthy", persistedIoHealthy && readErrors.isEmpty())
            put("lastSuccessAt", health.lastSuccessAt)
            put("lastFailureAt", health.lastFailureAt)
            put("lastOperation", if (readErrors.isEmpty()) health.lastOperation else "dashboard-report-read")
            put("lastError", currentReadError.ifBlank { health.lastError })
            put("files", JSONArray().apply {
                health.files.forEach { file ->
                    put(JSONObject().apply {
                        put("name", file.name)
                        put("exists", file.exists)
                        put("readable", file.readable)
                        put("writable", file.writable)
                        put("sizeBytes", file.sizeBytes)
                    })
                }
            })
        }
    }


    data class DeleteResult(val deleted: Boolean, val statusCode: Int, val message: String) {
        fun toJson(): String = JSONObject().apply {
            put("deleted", deleted)
            put("message", message)
        }.toString()
    }

    private const val MAX_REPORT_ENTRIES = 500
}
