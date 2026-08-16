package com.labpano.gpxextractor.output

import com.labpano.gpxextractor.data.ProcessingStatus
import java.util.Calendar
import java.util.Locale

/** Creates one stable dated namespace for a processing operation. */
data class DatedOutputLayout(val date: String) {
    /** Every completed media/GPX file is placed below OUTPUT/dd-mm-yyyy/. */
    fun mediaSubfolder(status: ProcessingStatus): String = date
    fun reportFileName(status: ProcessingStatus): String = "${status.name}.TXT"

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
