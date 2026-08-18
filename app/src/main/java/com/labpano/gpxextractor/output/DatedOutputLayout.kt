package com.labpano.gpxextractor.output

import com.labpano.gpxextractor.data.ProcessingStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Creates one stable dated namespace for a processing operation. */
data class DatedOutputLayout(val date: String) {
    /**
     * Classified media layout:
     *
     * OUTPUT/
     *   GOOD.TXT
     *   FAILED.TXT
     *   ERROR.TXT
     *   17-08-2026/GOOD/video.mp4 + video.gpx + video_ GOOD.txt
     *   17-08-2026/FAILED/video.mp4 + video.gpx + video_ FAILED.txt
     *   17-08-2026/ERROR/video.mp4 [+ partial gpx] + video_ ERROR.txt
     *
     * Date is the first path component so all results from one recording day stay together.
     */
    fun mediaSubfolder(status: ProcessingStatus): String = "$date/${status.name}"

    fun statusFolder(status: ProcessingStatus): String = status.name
    fun reportFileName(status: ProcessingStatus): String = "${status.name}.TXT"

    fun recordingReportFileName(videoName: String, status: ProcessingStatus): String {
        val base = videoName.substringAfterLast('/').substringBeforeLast('.', videoName.substringAfterLast('/'))
        return "${base}_ ${status.name}.txt"
    }

    companion object {
        private const val TWO_DIGIT_YEAR_START_MILLIS = 946_684_800_000L // 2000-01-01 UTC
        private val filenameTimestampRegex = Regex("\\d{6}_\\d{9}")

        fun forRecording(fileName: String, fallbackMillis: Long = System.currentTimeMillis()): DatedOutputLayout {
            val match = filenameTimestampRegex.find(fileName.substringAfterLast('/'))
            val captureMillis = match?.value?.let { token ->
                runCatching {
                    SimpleDateFormat("yyMMdd_HHmmssSSS", Locale.US).apply {
                        isLenient = false
                        set2DigitYearStart(Date(TWO_DIGIT_YEAR_START_MILLIS))
                    }.parse(token)?.time
                }.getOrNull()
            } ?: fallbackMillis
            return fromMillis(captureMillis)
        }

        fun now(): DatedOutputLayout = fromMillis(System.currentTimeMillis())

        private fun fromMillis(millis: Long): DatedOutputLayout {
            val calendar = Calendar.getInstance().apply { timeInMillis = millis }
            val value = String.format(
                Locale.US,
                "%02d-%02d-%04d",
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.YEAR)
            )
            return DatedOutputLayout(value)
        }
    }
}
