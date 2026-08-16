package com.labpano.gpxextractor.output

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutputLayoutPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun suppressesDailyReportsWhenMonitoringAndOutputAreSameFolder() {
        val shared = temporaryFolder.newFolder("stitched")
        assertFalse(OutputLayoutPolicy.shouldWriteDailyMonitoringReports(shared, shared))
    }

    @Test
    fun keepsDailyReportsWhenMonitoringAndOutputAreSeparate() {
        val monitoring = temporaryFolder.newFolder("monitoring")
        val output = temporaryFolder.newFolder("output")
        assertTrue(OutputLayoutPolicy.shouldWriteDailyMonitoringReports(monitoring, output))
    }
}
