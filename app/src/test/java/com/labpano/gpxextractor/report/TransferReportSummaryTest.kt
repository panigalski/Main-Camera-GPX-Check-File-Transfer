package com.labpano.gpxextractor.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferReportSummaryTest {
    @Test
    fun calculatesUniqueTransferTotalsAndIgnoresSummaryLines() {
        val first = detail(
            transactionId = "tx-1",
            video = "a.mp4",
            extra = "transferVideoBytes=1500000000; transferVideoDurationMs=3600000"
        )
        val second = detail(
            transactionId = "tx-2",
            video = "b.mp4",
            extra = "videoStartUtc=2026-08-19T11:00:00.000Z; videoEndUtc=2026-08-19T11:30:00.000Z; transferVideoBytes=500000000"
        )

        val summary = TransferReportSummaryCodec.calculate(
            sequenceOf("# TRANSFER SUMMARY", first, second, first)
        )

        assertEquals(2, summary.filesTransferred)
        assertEquals(5_400_000L, summary.videoDurationMillis)
        assertEquals(2_000_000_000L, summary.videoBytes)
        assertEquals(2, summary.durationKnownFiles)
        assertEquals(2, summary.bytesKnownFiles)
        val rendered = TransferReportSummaryCodec.render(summary).joinToString("\n")
        assertTrue(rendered.contains("Files transferred: 2"))
        assertTrue(rendered.contains("Video recording hours transferred: 1.500"))
        assertTrue(rendered.contains("Data transferred: 2.000 GB"))
        assertFalse(rendered.contains("Statistics coverage:"))
    }

    @Test
    fun reportsCoverageWhenLegacyEntryHasUnknownMetrics() {
        val known = detail("tx-1", "a.mp4", "transferVideoBytes=1000000000; transferVideoDurationMs=3600000")
        val legacyUnknown = detail("tx-2", "b.mp4", "Approved and moved")
        val summary = TransferReportSummaryCodec.calculate(sequenceOf(known, legacyUnknown))

        assertEquals(2, summary.filesTransferred)
        assertEquals(1, summary.durationKnownFiles)
        assertEquals(1, summary.bytesKnownFiles)
        assertTrue(
            TransferReportSummaryCodec.render(summary).any {
                it == "# Statistics coverage: duration=1/2 files; data=1/2 files"
            }
        )
    }

    @Test
    fun appendsMetricsWithoutChangingDetailRecordShape() {
        val message = TransferReportSummaryCodec.withMetrics(
            "Approved; transactionId=tx-1",
            videoBytes = 42L,
            videoDurationMillis = 99L
        )
        val line = "2026-08-19T10:00:00.000Z\t/source/a.mp4\t$message"
        assertTrue(TransferReportSummaryCodec.isDetailLine(line))
        val parsed = requireNotNull(TransferReportSummaryCodec.parseDetail(line))
        assertEquals(42L, parsed.explicitBytes)
        assertEquals(99L, parsed.durationMillis)
    }

    private fun detail(transactionId: String, video: String, extra: String): String =
        "2026-08-19T10:00:00.000Z\t/source/$video\tvideo=$video; destination=/out; transactionId=$transactionId; $extra"
}
