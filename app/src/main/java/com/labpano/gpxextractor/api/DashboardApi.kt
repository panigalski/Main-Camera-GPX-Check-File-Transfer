package com.labpano.gpxextractor.api

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.labpano.gpxextractor.AppConfig
import com.labpano.gpxextractor.BuildConfig
import com.labpano.gpxextractor.report.ReportFileAccess
import com.labpano.gpxextractor.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

object DashboardApi {
    fun build(context: Context): String {
        val monitoringPath = monitoringPath(context)
        val directory = File(monitoringPath)

        return JSONObject().apply {
            put("apiVersion", 3)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("generatedAt", System.currentTimeMillis())
            put("monitoringDirectory", monitoringPath)
            put("internalStorage", internalStorageJson())
            put("externalStorage", externalStorageJson(context) ?: JSONObject.NULL)
            put("battery", batteryJson(context))
            put("error", readReport(File(directory, "ERROR.TXT")))
            put("failed", readReport(File(directory, "FAILED.TXT")))
            put("good", readReport(File(directory, "GOOD.TXT")))
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
        val fileName = when (reportType.lowercase()) {
            "error" -> "ERROR.TXT"
            "failed" -> "FAILED.TXT"
            "good" -> "GOOD.TXT"
            else -> return DeleteResult(false, 400, "Unknown report type")
        }
        val report = File(monitoringPath(context), fileName)
        if (!report.exists()) return DeleteResult(false, 404, "$fileName does not exist")
        if (!report.isFile || !report.canRead() || !report.canWrite()) {
            return DeleteResult(false, 403, "$fileName is not writable")
        }

        val expected = "$timestamp\t$path\t$message"
        return synchronized(ReportFileAccess.lock) {
            val lines = runCatching { report.readLines(Charsets.UTF_8) }
                .getOrElse { return@synchronized DeleteResult(false, 500, it.message ?: "Cannot read report") }
            val index = lines.indexOfFirst { it == expected }
            if (index < 0) return@synchronized DeleteResult(false, 404, "Entry no longer exists")

            val remaining = lines.toMutableList().also { it.removeAt(index) }
            val temporary = File(report.parentFile, ".${report.name}.rewrite-${System.nanoTime()}.tmp")
            val backup = File(report.parentFile, ".${report.name}.rewrite-backup")
            try {
                val bytes = buildString {
                    remaining.forEach { line -> append(line).append('\n') }
                }.toByteArray(Charsets.UTF_8)
                java.io.FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                val expectedSize = temporary.length()
                if (backup.exists()) backup.delete()
                if (!report.renameTo(backup)) throw java.io.IOException("Cannot preserve original report")
                if (!temporary.renameTo(report)) {
                    backup.renameTo(report)
                    throw java.io.IOException("Cannot replace report")
                }
                if (!report.isFile || !report.canRead() || report.length() != expectedSize) {
                    report.delete()
                    backup.renameTo(report)
                    throw java.io.IOException("Rewritten report verification failed")
                }
                backup.delete()
                DeleteResult(true, 200, "Entry deleted")
            } catch (error: Throwable) {
                temporary.delete()
                if (!report.exists() && backup.exists()) backup.renameTo(report)
                DeleteResult(false, 500, error.message ?: error.javaClass.simpleName)
            }
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

    private fun readReport(file: File): JSONArray {
        val array = JSONArray()
        if (!file.isFile || !file.canRead()) return array
        synchronized(ReportFileAccess.lock) {
            file.useLines(Charsets.UTF_8) { lines ->
                lines.filter { it.isNotBlank() }.takeLast(MAX_REPORT_ENTRIES).forEach { line ->
                    val parts = line.split('\t', limit = 3)
                    array.put(JSONObject().apply {
                        put("timestamp", parts.getOrElse(0) { "" })
                        put("path", parts.getOrElse(1) { "" })
                        put("message", parts.getOrElse(2) { "" })
                    })
                }
            }
        }
        return array
    }

    private fun Sequence<String>.takeLast(limit: Int): List<String> {
        val queue = ArrayDeque<String>(limit)
        forEach { value ->
            if (queue.size == limit) queue.removeFirst()
            queue.addLast(value)
        }
        return queue.toList()
    }

    data class DeleteResult(val deleted: Boolean, val statusCode: Int, val message: String) {
        fun toJson(): String = JSONObject().apply {
            put("deleted", deleted)
            put("message", message)
        }.toString()
    }

    private const val MAX_REPORT_ENTRIES = 500
}
