package com.labpano.gpxextractor.gpx

import com.labpano.gpxextractor.mp4.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxValidatorTest {
    @Test
    fun detectsGapLongerThanFiveSeconds() {
        val points = listOf(
            GpsPoint(0L, 52.0, 21.0, 100.0),
            GpsPoint(6_001L, 52.1, 21.1, 101.0)
        )
        val result = GpxValidator().validate(points)
        assertTrue(result.valid)
        assertEquals(1, result.gapCount)
    }
    @Test
    fun fiveSecondsPassesButFiveSecondsAndOneMillisecondFailsGapCheck() {
        val exactlyFive = GpxValidator().validate(listOf(
            GpsPoint(0L, 52.0, 21.0, 100.0),
            GpsPoint(5_000L, 52.1, 21.1, 101.0)
        ))
        val overFive = GpxValidator().validate(listOf(
            GpsPoint(0L, 52.0, 21.0, 100.0),
            GpsPoint(5_001L, 52.1, 21.1, 101.0)
        ))
        assertEquals(0, exactlyFive.gapCount)
        assertEquals(1, overFive.gapCount)
    }

}
