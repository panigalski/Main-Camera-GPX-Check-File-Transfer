package com.labpano.gpxextractor.report

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReportTailReaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readsOnlyRequestedTailInOriginalOrderIncludingUnicode() {
        val file = File(temporaryFolder.root, "GOOD.TXT")
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (index in 1..2000) {
                writer.append("line-").append(index.toString()).append(" • Lublin łódź").append('\n')
            }
        }

        val tail = ReportTailReader.lastNonBlankLines(file, 500)

        assertEquals(500, tail.size)
        assertEquals("line-1501 • Lublin łódź", tail.first())
        assertEquals("line-2000 • Lublin łódź", tail.last())
        assertEquals(listOf("line-2000 • Lublin łódź"), ReportTailReader.lastNonBlankLines(file, 1))
    }

    @Test
    fun recentMarkerSearchIsBoundedToTail() {
        val file = File(temporaryFolder.root, "GOOD.TXT")
        file.writeText("old transactionId=old\n" + "x".repeat(2048) + "\nnew transactionId=new\n")
        assertEquals(true, ReportTailReader.recentBytesContain(file, "transactionId=new", 512))
        assertEquals(false, ReportTailReader.recentBytesContain(file, "transactionId=old", 512))
    }

    @Test
    fun missingOrEmptyReportReturnsEmptyList() {
        assertEquals(emptyList<String>(), ReportTailReader.lastNonBlankLines(File(temporaryFolder.root, "missing.txt"), 500))
        val empty = File(temporaryFolder.root, "empty.txt").apply { writeText("") }
        assertEquals(emptyList<String>(), ReportTailReader.lastNonBlankLines(empty, 500))
    }
}
