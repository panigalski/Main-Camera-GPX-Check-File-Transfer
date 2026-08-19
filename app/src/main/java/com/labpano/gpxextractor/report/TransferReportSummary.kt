package com.labpano.gpxextractor.report

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Machine-readable/human-readable summary for cumulative OUTPUT/<STATUS>.TXT reports.
 *
 * Detail records remain tab-separated and compatible with older clients. Summary/control lines are
 * prefixed with '#', so they can be filtered out of report-entry APIs and never migrate into daily
 * reports.
 */
data class TransferReportSummary(
    val filesTransferred: Int,
    val videoDurationMillis: Long,
    val videoBytes: Long,
    val durationKnownFiles: Int,
    val bytesKnownFiles: Int
) {
    val videoHours: Double get() = videoDurationMillis.toDouble() / 3_600_000.0
    val videoGigabytes: Double get() = videoBytes.toDouble() / 1_000_000_000.0
}

object TransferReportSummaryCodec {
    const val SUMMARY_PREFIX = "#"
    private const val VIDEO_BYTES_KEY = "transferVideoBytes"
    private const val VIDEO_DURATION_KEY = "transferVideoDurationMs"

    data class DetailEntry(
        val line: String,
        val message: String,
        val identity: String,
        val videoName: String?,
        val destination: String?,
        val explicitBytes: Long?,
        val durationMillis: Long?
    )

    fun isDetailLine(line: String): Boolean {
        if (line.isBlank() || line.trimStart().startsWith(SUMMARY_PREFIX)) return false
        return line.split('\t', limit = 3).size >= 3
    }

    fun parseDetail(line: String): DetailEntry? {
        if (!isDetailLine(line)) return null
        val parts = line.split('\t', limit = 3)
        val message = parts[2]
        val transactionId = messageValue(message, "transactionId")
        val identity = transactionId?.let { "tx:$it" } ?: "line:$line"
        val bytes = messageValue(message, VIDEO_BYTES_KEY)?.toLongOrNull()?.takeIf { it >= 0L }
        val explicitDuration = messageValue(message, VIDEO_DURATION_KEY)?.toLongOrNull()?.takeIf { it >= 0L }
        val legacyDuration = if (explicitDuration == null) parseLegacyDuration(message) else null
        return DetailEntry(
            line = line,
            message = message,
            identity = identity,
            videoName = messageValue(message, "video"),
            destination = messageValue(message, "destination"),
            explicitBytes = bytes,
            durationMillis = explicitDuration ?: legacyDuration
        )
    }

    fun calculate(
        lines: Sequence<String>,
        legacyByteResolver: (DetailEntry) -> Long? = { null }
    ): TransferReportSummary {
        val unique = linkedMapOf<String, DetailEntry>()
        lines.forEach { line ->
            parseDetail(line)?.let { entry -> unique.putIfAbsent(entry.identity, entry) }
        }
        var durationMillis = 0L
        var bytes = 0L
        var durationKnown = 0
        var bytesKnown = 0
        unique.values.forEach { entry ->
            entry.durationMillis?.let {
                durationMillis = safeAdd(durationMillis, it)
                durationKnown++
            }
            val resolvedBytes = entry.explicitBytes ?: legacyByteResolver(entry)?.takeIf { it >= 0L }
            resolvedBytes?.let {
                bytes = safeAdd(bytes, it)
                bytesKnown++
            }
        }
        return TransferReportSummary(
            filesTransferred = unique.size,
            videoDurationMillis = durationMillis,
            videoBytes = bytes,
            durationKnownFiles = durationKnown,
            bytesKnownFiles = bytesKnown
        )
    }

    fun render(summary: TransferReportSummary): List<String> {
        val lines = mutableListOf(
            "# TRANSFER SUMMARY",
            "# Files transferred: ${summary.filesTransferred}",
            "# Video recording hours transferred: ${String.format(Locale.US, "%.3f", summary.videoHours)}",
            "# Data transferred: ${String.format(Locale.US, "%.3f", summary.videoGigabytes)} GB"
        )
        if (summary.durationKnownFiles < summary.filesTransferred || summary.bytesKnownFiles < summary.filesTransferred) {
            lines += "# Statistics coverage: duration=${summary.durationKnownFiles}/${summary.filesTransferred} files; data=${summary.bytesKnownFiles}/${summary.filesTransferred} files"
        }
        lines += "# ------------------------------------------------------------"
        return lines
    }

    fun withMetrics(message: String, videoBytes: Long?, videoDurationMillis: Long?): String {
        val clean = message.trim().trimEnd(';')
        val suffix = buildList {
            videoBytes?.takeIf { it >= 0L }?.let { add("$VIDEO_BYTES_KEY=$it") }
            videoDurationMillis?.takeIf { it >= 0L }?.let { add("$VIDEO_DURATION_KEY=$it") }
        }
        return if (suffix.isEmpty()) clean else clean + "; " + suffix.joinToString("; ")
    }

    private fun parseLegacyDuration(message: String): Long? {
        val start = messageValue(message, "videoStartUtc")?.takeUnless { it.equals("unknown", true) } ?: return null
        val end = messageValue(message, "videoEndUtc")?.takeUnless { it.equals("unknown", true) } ?: return null
        val startMillis = parseUtc(start) ?: return null
        val endMillis = parseUtc(end) ?: return null
        return (endMillis - startMillis).takeIf { it >= 0L }
    }

    private fun parseUtc(value: String): Long? {
        val formats = arrayOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        formats.forEach { pattern ->
            try {
                return SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }.parse(value)?.time
            } catch (_: ParseException) {
                // Try the next supported legacy format.
            }
        }
        return null
    }

    private fun messageValue(message: String, key: String): String? =
        Regex("(?:^|;)\\s*${Regex.escape(key)}=([^;]+)", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

    private fun safeAdd(left: Long, right: Long): Long = when {
        right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
        else -> left + right
    }
}
