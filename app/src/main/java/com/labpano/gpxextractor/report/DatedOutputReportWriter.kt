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

/** Filesystem-only helper retained for unit tests and compatibility utilities. */
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
        val report = ensureDailyReportFile(layout, status)
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

    private fun ensureDailyReportFile(layout: DatedOutputLayout, status: ProcessingStatus): File {
        val folder = File(File(reportDirectory, layout.date), status.name)
        if (!folder.exists() && !folder.mkdirs()) {
            throw IOException("Cannot create daily report folder: ${folder.absolutePath}")
        }
        val report = reportFile(layout, status)
        if (!report.exists() && !report.createNewFile()) {
            throw IOException("Cannot create daily report file: ${report.absolutePath}")
        }
        return report
    }

    private fun reportFile(layout: DatedOutputLayout, status: ProcessingStatus): File =
        File(File(File(reportDirectory, layout.date), status.name), layout.reportFileName(status))

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
