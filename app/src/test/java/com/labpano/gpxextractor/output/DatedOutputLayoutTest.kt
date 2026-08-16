package com.labpano.gpxextractor.output

import com.labpano.gpxextractor.data.ProcessingStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DatedOutputLayoutTest {
    @Test
    fun placesEveryCompletedFileInTheCurrentDateFolder() {
        val layout = DatedOutputLayout("06-08-2026")
        assertEquals("06-08-2026", layout.mediaSubfolder(ProcessingStatus.GOOD))
        assertEquals("06-08-2026", layout.mediaSubfolder(ProcessingStatus.FAILED))
        assertEquals("06-08-2026", layout.mediaSubfolder(ProcessingStatus.ERROR))
        assertEquals("FAILED.TXT", layout.reportFileName(ProcessingStatus.FAILED))
        assertEquals("ERROR.TXT", layout.reportFileName(ProcessingStatus.ERROR))
    }
}
