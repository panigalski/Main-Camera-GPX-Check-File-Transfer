package com.labpano.gpxextractor.output

import com.labpano.gpxextractor.data.ProcessingStatus
import java.util.Calendar
import java.util.Locale

/** Creates one stable output namespace for a processing operation. */
data class DatedOutputLayout(val date: String) {
    fun mediaSubfolder(status: ProcessingStatus): String = "$date/${status.name}_$date"
    fun reportFileName(status: ProcessingStatus): String = "${status.name}_$date.TXT"

    companion object {
        fun now(): DatedOutputLayout {
            val calendar = Calendar.getInstance()
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
