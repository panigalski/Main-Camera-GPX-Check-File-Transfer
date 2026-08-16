package com.labpano.gpxextractor.gpx

import com.labpano.gpxextractor.mp4.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GpxWriterTest {
    @Test
    fun writesUtcTrackTimesWithoutChangingTheTimeline() {
        val output = File.createTempFile("gpx-writer-", ".gpx")
        try {
            GpxWriter().write(
                listOf(
                    GpsPoint(1_700_000_000_000L, 52.0, 21.0, 100.0),
                    GpsPoint(1_700_000_001_250L, 52.1, 21.1, 101.0)
                ),
                output,
                "sample & track"
            )
            val xml = output.readText()
            assertTrue(xml.contains("<metadata><time>2023-11-14T22:13:20.000Z</time></metadata>"))
            assertTrue(xml.contains("<time>2023-11-14T22:13:20.000Z</time>"))
            assertTrue(xml.contains("<time>2023-11-14T22:13:21.250Z</time>"))
            assertTrue(xml.contains("<name>sample &amp; track</name>"))
            assertEquals(2, Regex("<trkpt\\b").findAll(xml).count())
        } finally {
            output.delete()
        }
    }
}
