package com.labpano.gpxextractor.report

import com.labpano.gpxextractor.data.ProcessingStatus
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Deprecated("Production reports are stored by GlobalOutputReportStore")
class ReportWriter(private val reportDirectory: File) {
    fun ensureReportFiles() = synchronized(ReportFileAccess.lock) {
        if (!reportDirectory.exists() && !reportDirectory.mkdirs()) {
            throw IllegalStateException("Cannot create report directory: ${reportDirectory.absolutePath}")
        }
        listOf(ProcessingStatus.GOOD, ProcessingStatus.FAILED, ProcessingStatus.ERROR).forEach { status ->
            val report = File(reportDirectory, "${status.name}.TXT")
            if (!report.exists() && !report.createNewFile()) {
                throw IllegalStateException("Cannot create report file: ${report.absolutePath}")
            }
        }
    }

    fun append(status: ProcessingStatus, source: File, message: String) =
        appendOnce(status, source.absolutePath, message, null)

    fun appendOnce(
        status: ProcessingStatus,
        sourcePath: String,
        message: String,
        transactionId: String?
    ) = synchronized(ReportFileAccess.lock) {
        reportDirectory.mkdirs()
        val report = File(reportDirectory, "${status.name}.TXT")
        val marker = transactionId?.let { "transactionId=$it" }
        if (marker != null && containsMarker(report, marker)) return@synchronized

        val safePath = sanitize(sourcePath)
        val safeMessage = sanitize(message)
        val line = "${utcTimestamp()}\t$safePath\t$safeMessage\n"
        FileOutputStream(report, true).use { output ->
            output.write(line.toByteArray(Charsets.UTF_8))
            output.fd.sync()
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
