package com.labpano.gpxextractor.output

import com.labpano.gpxextractor.data.ProcessingStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DatedOutputLayoutTest {
    @Test
    fun buildsStatusFoldersAndReportsInsideTheDateNamespace() {
        val layout = DatedOutputLayout("06-08-2026")
        assertEquals("06-08-2026/GOOD_06-08-2026", layout.mediaSubfolder(ProcessingStatus.GOOD))
        assertEquals("FAILED_06-08-2026.TXT", layout.reportFileName(ProcessingStatus.FAILED))
        assertEquals("ERROR_06-08-2026.TXT", layout.reportFileName(ProcessingStatus.ERROR))
    }
}
