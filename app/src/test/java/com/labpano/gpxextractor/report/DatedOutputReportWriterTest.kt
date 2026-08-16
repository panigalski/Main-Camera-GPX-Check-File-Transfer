package com.labpano.gpxextractor.report

import com.labpano.gpxextractor.data.ProcessingStatus
import com.labpano.gpxextractor.output.DatedOutputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DatedOutputReportWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesOnlyIntoTheSelectedDateFolderAndCreatesAllStatusFiles() {
        val monitoring = temporaryFolder.newFolder("monitoring")
        val writer = DatedOutputReportWriter(monitoring)
        val layout = DatedOutputLayout("08-08-2026")

        writer.appendOnce(
            ProcessingStatus.GOOD,
            "/recordings/a.mp4",
            "Approved; transactionId=tx-good",
            "tx-good",
            layout
        )

        val daily = File(monitoring, "08-08-2026")
        assertTrue(File(daily, "GOOD.TXT").isFile)
        assertTrue(File(daily, "FAILED.TXT").isFile)
        assertTrue(File(daily, "ERROR.TXT").isFile)
        assertEquals(1, File(daily, "GOOD.TXT").readLines().size)
        assertEquals(0, File(daily, "FAILED.TXT").readLines().size)
        assertEquals(0, File(daily, "ERROR.TXT").readLines().size)
    }

    @Test
    fun keepsDifferentDatesSeparatedAndDeduplicatesRecoveredTransactions() {
        val monitoring = temporaryFolder.newFolder("monitoring")
        val writer = DatedOutputReportWriter(monitoring)
        val firstDay = DatedOutputLayout("08-08-2026")
        val secondDay = DatedOutputLayout("09-08-2026")

        repeat(2) {
            writer.appendOnce(
                ProcessingStatus.FAILED,
                "/recordings/a.mp4",
                "GPS gap; transactionId=tx-1",
                "tx-1",
                firstDay
            )
        }
        writer.appendOnce(
            ProcessingStatus.FAILED,
            "/recordings/b.mp4",
            "GPS gap; transactionId=tx-2",
            "tx-2",
            secondDay
        )

        assertEquals(1, File(monitoring, "08-08-2026/FAILED.TXT").readLines().size)
        assertEquals(1, File(monitoring, "09-08-2026/FAILED.TXT").readLines().size)
    }
}
