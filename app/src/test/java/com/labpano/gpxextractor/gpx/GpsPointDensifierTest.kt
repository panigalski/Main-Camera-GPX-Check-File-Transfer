package com.labpano.gpxextractor.gpx

import com.labpano.gpxextractor.mp4.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsPointDensifierTest {
    @Test
    fun interpolatesAcrossAntimeridianUsingShortestPath() {
        val result = GpsPointDensifier(requestedIntervalMillis = 250L).densify(
            listOf(
                GpsPoint(0L, 0.0, 179.0, null),
                GpsPoint(1_000L, 0.0, -179.0, null)
            )
        )
        assertEquals(5, result.points.size)
        assertTrue(result.points[2].longitude == 180.0 || result.points[2].longitude == -180.0)
    }

    @Test
    fun neverDropsGenuinePointsWhenInterpolationIsLimited() {
        val genuine = (0 until 10).map { index ->
            GpsPoint(index * 1_000L, 1.0 + index, 2.0 + index, null)
        }
        val result = GpsPointDensifier(maximumOutputPoints = 12).densify(genuine)
        assertTrue(result.interpolationLimited)
        genuine.forEach { point -> assertTrue(result.points.contains(point)) }
        assertTrue(result.points.size <= 12)
    }
}
