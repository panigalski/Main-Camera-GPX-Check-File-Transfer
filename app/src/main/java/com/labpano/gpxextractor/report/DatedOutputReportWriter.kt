package com.labpano.gpxextractor.report

import com.labpano.gpxextractor.data.ProcessingStatus
import com.labpano.gpxextractor.output.DatedOutputLayout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Legacy day-specific report writer retained only for compatibility/tests.
 * Production processing no longer calls this class as of 0.5.19; all reports are cumulative and
 * live at the selected OUTPUT root, while date subfolders contain media/GPX only.
 */
@Deprecated("Daily TXT reports are no longer part of the production output layout")
class DatedOutputReportWriter(private val reportDirectory: File) {
    fun append(
        status: ProcessingStatus,
        sourcePath: String,
        message: String,
        layout: DatedOutputLayout
    ) = appendOnce(status, sourcePath, message, null, layout)

    fun appendOnce(
        status: ProcessingStatus,
        sourcePath: String,
        message: String,
        transactionId: String?,
        layout: DatedOutputLayout
    ) = synchronized(ReportFileAccess.lock) {
        val daily = File(reportDirectory, layout.date)
        if (!daily.exists() && !daily.mkdirs()) {
            throw IOException("Cannot create daily monitoring folder: ${daily.absolutePath}")
        }

        ensureDailyReportFiles(daily)
        val report = File(daily, layout.reportFileName(status))
        val marker = transactionId?.let { "transactionId=$it" }
        if (marker != null && report.isFile && containsMarker(report, marker)) return@synchronized

        val safePath = sanitize(sourcePath)
        val safeMessage = sanitize(message)
        val line = "${utcTimestamp()}\t$safePath\t$safeMessage\n"
        FileOutputStream(report, true).use { output ->
            output.write(line.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun ensureDailyReportFiles(daily: File) {
        listOf(ProcessingStatus.GOOD, ProcessingStatus.FAILED, ProcessingStatus.ERROR).forEach { status ->
            val report = File(daily, "${status.name}.TXT")
            if (!report.exists() && !report.createNewFile()) {
                throw IOException("Cannot create daily report file: ${report.absolutePath}")
            }
        }
    }

    private fun containsMarker(report: File, marker: String): Boolean {
        if (!report.isFile || report.length() == 0L) return false
        return report.useLines(Charsets.UTF_8) { lines -> lines.any { marker in it } }
    }

    private fun sanitize(value: String): String =
        value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

    private fun utcTimestamp(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
