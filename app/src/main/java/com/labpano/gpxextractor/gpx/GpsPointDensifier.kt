package com.labpano.gpxextractor.gpx

import com.labpano.gpxextractor.mp4.GpsPoint
import kotlin.math.ceil

/** Adds regularly spaced synthetic points while preserving every genuine CAMM fix. */
class GpsPointDensifier(
    private val requestedIntervalMillis: Long = 250L,
    private val maxInterpolationGapMillis: Long = 5_000L,
    private val maximumOutputPoints: Int = 2_000_000
) {
    data class Result(
        val points: List<GpsPoint>,
        val interpolatedPointCount: Int,
        val effectiveIntervalMillis: Long,
        val interpolationLimited: Boolean
    )

    fun densify(points: List<GpsPoint>): Result {
        if (points.size < 2 || requestedIntervalMillis <= 0L) {
            return Result(points, 0, requestedIntervalMillis.coerceAtLeast(0L), false)
        }

        // Genuine points are never discarded. If the cap is too low even for genuine data, disable
        // interpolation rather than truncating the recording.
        if (points.size >= maximumOutputPoints) {
            return Result(points, 0, Long.MAX_VALUE, true)
        }

        val desiredInterpolated = countInterpolated(points, requestedIntervalMillis)
        val availableSlots = maximumOutputPoints - points.size
        val limited = desiredInterpolated > availableSlots
        val effectiveInterval = if (!limited || desiredInterpolated == 0L) {
            requestedIntervalMillis
        } else {
            chooseIntervalForCapacity(points, availableSlots)
        }

        val estimated = (points.size.toLong() + countInterpolated(points, effectiveInterval))
            .coerceAtMost(maximumOutputPoints.toLong())
            .toInt()
        val result = ArrayList<GpsPoint>(estimated)
        var interpolated = 0
        result += points.first()

        points.zipWithNext().forEach { (first, second) ->
            val duration = second.timestampMillis - first.timestampMillis
            if (duration > effectiveInterval && duration <= maxInterpolationGapMillis) {
                var timestamp = first.timestampMillis + effectiveInterval
                while (timestamp < second.timestampMillis && result.size < maximumOutputPoints - 1) {
                    val fraction = (timestamp - first.timestampMillis).toDouble() / duration.toDouble()
                    result += interpolate(first, second, timestamp, fraction)
                    interpolated++
                    timestamp += effectiveInterval
                }
            }
            // Always retain the genuine point, even when interpolation has reached its capacity.
            result += second
        }

        return Result(result, interpolated, effectiveInterval, limited)
    }

    private fun chooseIntervalForCapacity(points: List<GpsPoint>, availableSlots: Int): Long {
        if (availableSlots <= 0) return Long.MAX_VALUE
        var low = requestedIntervalMillis
        var high = maxInterpolationGapMillis.coerceAtLeast(low)
        while (countInterpolated(points, high) > availableSlots && high < Long.MAX_VALUE / 2L) {
            high = (high * 2L).coerceAtMost(Long.MAX_VALUE / 2L)
        }
        while (low < high) {
            val mid = low + (high - low) / 2L
            if (countInterpolated(points, mid) <= availableSlots) high = mid else low = mid + 1L
        }
        return low
    }

    private fun countInterpolated(points: List<GpsPoint>, intervalMillis: Long): Long {
        if (intervalMillis <= 0L || intervalMillis == Long.MAX_VALUE) return 0L
        var count = 0L
        points.zipWithNext().forEach { (first, second) ->
            val duration = second.timestampMillis - first.timestampMillis
            if (duration > intervalMillis && duration <= maxInterpolationGapMillis) {
                count += ceil(duration.toDouble() / intervalMillis.toDouble()).toLong() - 1L
            }
        }
        return count
    }

    private fun interpolate(
        first: GpsPoint,
        second: GpsPoint,
        timestamp: Long,
        fraction: Double
    ): GpsPoint = GpsPoint(
        timestampMillis = timestamp,
        latitude = lerp(first.latitude, second.latitude, fraction),
        longitude = interpolateLongitude(first.longitude, second.longitude, fraction),
        altitudeMeters = lerpNullable(first.altitudeMeters, second.altitudeMeters, fraction),
        horizontalAccuracyMeters = lerpNullable(first.horizontalAccuracyMeters, second.horizontalAccuracyMeters, fraction),
        verticalAccuracyMeters = lerpNullable(first.verticalAccuracyMeters, second.verticalAccuracyMeters, fraction)
    )

    private fun interpolateLongitude(start: Double, end: Double, fraction: Double): Double {
        var delta = end - start
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        var value = start + delta * fraction
        while (value > 180.0) value -= 360.0
        while (value < -180.0) value += 360.0
        return value
    }

    private fun lerp(start: Double, end: Double, fraction: Double): Double =
        start + (end - start) * fraction

    private fun lerpNullable(start: Double?, end: Double?, fraction: Double): Double? = when {
        start != null && end != null -> lerp(start, end, fraction)
        start != null -> start
        else -> end
    }
}
