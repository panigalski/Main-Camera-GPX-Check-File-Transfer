package com.labpano.gpxextractor.report

import com.labpano.gpxextractor.data.ProcessingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReportWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun preflightDoesNotCreateEmptyStatusReports() {
        val monitoring = temporaryFolder.newFolder("monitoring")
        ReportWriter(monitoring).ensureReportFiles()

        assertTrue(!File(monitoring, "GOOD.TXT").exists())
        assertTrue(!File(monitoring, "FAILED.TXT").exists())
        assertTrue(!File(monitoring, "ERROR.TXT").exists())
    }

    @Test
    fun cumulativeReportPreservesExistingRecordsWhenNewRecordIsAppended() {
        val monitoring = temporaryFolder.newFolder("monitoring")
        val report = File(monitoring, "GOOD.TXT")
        val existing = (1..6001).joinToString(separator = "\n", postfix = "\n") { index ->
            "existing-$index\t/path/$index\t${"x".repeat(1400)}"
        }
        report.writeText(existing, Charsets.UTF_8)
        assertTrue(report.length() > 8L * 1024L * 1024L)

        ReportWriter(monitoring).appendOnce(
            ProcessingStatus.GOOD,
            "/recordings/new.mp4",
            "Approved; transactionId=tx-new",
            "tx-new"
        )

        val lines = report.readLines(Charsets.UTF_8)
        assertEquals(6002, lines.size)
        assertTrue(lines.first().startsWith("existing-1\t"))
        assertTrue(lines.last().contains("transactionId=tx-new"))
    }

    @Test
    fun cumulativeReportDeduplicatesRecoveredTransaction() {
        val monitoring = temporaryFolder.newFolder("monitoring")
        val writer = ReportWriter(monitoring)

        repeat(2) {
            writer.appendOnce(
                ProcessingStatus.ERROR,
                "/recordings/bad.mp4",
                "Invalid GPS; transactionId=tx-error",
                "tx-error"
            )
        }

        assertEquals(1, File(monitoring, "ERROR.TXT").readLines().size)
    }
}
