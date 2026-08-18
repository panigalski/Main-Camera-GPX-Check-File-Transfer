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
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun createsOnlyTheReportForTheStatusThatOccurred() {
        val output = temporaryFolder.newFolder("output")
        val layout = DatedOutputLayout("08-08-2026")
        DatedOutputReportWriter(output).append(
            ProcessingStatus.GOOD,
            "/recordings/260808_120000000.mp4",
            "ok",
            layout
        )

        val good = File(output, "08-08-2026/GOOD/08-08-2026_GOOD.txt")
        val failed = File(output, "08-08-2026/FAILED/08-08-2026_FAILED.txt")
        val error = File(output, "08-08-2026/ERROR/08-08-2026_ERROR.txt")
        assertTrue(good.isFile)
        assertTrue(!failed.exists())
        assertTrue(!error.exists())
        assertEquals(1, good.readLines().size)
    }

    @Test
    fun separatesReportsByRecordingDay() {
        val output = temporaryFolder.newFolder("output")
        val writer = DatedOutputReportWriter(output)
        writer.append(ProcessingStatus.FAILED, "/a/260808_120000000.mp4", "gap", DatedOutputLayout("08-08-2026"))
        writer.append(ProcessingStatus.FAILED, "/a/260809_120000000.mp4", "gap", DatedOutputLayout("09-08-2026"))

        assertEquals(1, File(output, "08-08-2026/FAILED/08-08-2026_FAILED.txt").readLines().size)
        assertEquals(1, File(output, "09-08-2026/FAILED/09-08-2026_FAILED.txt").readLines().size)
    }
}
