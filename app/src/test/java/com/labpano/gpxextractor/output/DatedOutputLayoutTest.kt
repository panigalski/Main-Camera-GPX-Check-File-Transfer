package com.labpano.gpxextractor.output

import com.labpano.gpxextractor.data.ProcessingStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DatedOutputLayoutTest {
    @Test
    fun classifiesEveryCompletedFileByDateThenStatus() {
        val layout = DatedOutputLayout("06-08-2026")
        assertEquals("06-08-2026/GOOD", layout.mediaSubfolder(ProcessingStatus.GOOD))
        assertEquals("06-08-2026/FAILED", layout.mediaSubfolder(ProcessingStatus.FAILED))
        assertEquals("06-08-2026/ERROR", layout.mediaSubfolder(ProcessingStatus.ERROR))
        assertEquals("06-08-2026_GOOD.txt", layout.reportFileName(ProcessingStatus.GOOD))
        assertEquals("06-08-2026_FAILED.txt", layout.reportFileName(ProcessingStatus.FAILED))
        assertEquals("06-08-2026_ERROR.txt", layout.reportFileName(ProcessingStatus.ERROR))
    }

    @Test
    fun recordingFilenameControlsDateFolderEvenWhenProcessingOccursLater() {
        val layout = DatedOutputLayout.forRecording(
            fileName = "260817_235959999.mp4",
            fallbackMillis = 0L
        )
        assertEquals("17-08-2026", layout.date)
        assertEquals("17-08-2026/GOOD", layout.mediaSubfolder(ProcessingStatus.GOOD))
    }
}
