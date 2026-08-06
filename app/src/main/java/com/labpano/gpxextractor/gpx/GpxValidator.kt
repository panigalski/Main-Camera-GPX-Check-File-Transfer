package com.labpano.gpxextractor.gpx

import com.labpano.gpxextractor.AppConfig
import com.labpano.gpxextractor.mp4.GpsPoint

data class GpsGap(
    val afterPointIndex: Int,
    val startTimestampMillis: Long,
    val endTimestampMillis: Long,
    val durationMillis: Long
)

data class ValidationResult(
    val valid: Boolean,
    val errors: List<String>,
    val gaps: List<GpsGap>
) {
    val gapCount: Int get() = gaps.size
}

class GpxValidator(private val gapThresholdSeconds: Long = AppConfig.DEFAULT_GAP_THRESHOLD_SECONDS) {
    fun validate(points: List<GpsPoint>): ValidationResult {
        val errors = mutableListOf<String>()
        if (points.isEmpty()) errors += "No GPS points"
        if (points.size == 1) errors += "Only one GPS point"

        points.forEachIndexed { index, point ->
            if (!point.latitude.isFinite() || !point.longitude.isFinite()) {
                errors += "Point $index is not finite"
            } else {
                if (point.latitude !in -90.0..90.0) errors += "Point $index has invalid latitude"
                if (point.longitude !in -180.0..180.0) errors += "Point $index has invalid longitude"
            }
        }

        val gaps = mutableListOf<GpsGap>()
        points.zipWithNext().forEachIndexed { index, (first, second) ->
            val duration = second.timestampMillis - first.timestampMillis
            when {
                duration < 0L -> errors += "Timestamp order error after point $index"
                duration == 0L -> errors += "Duplicate timestamp after point $index"
                duration > gapThresholdSeconds * 1000L -> gaps += GpsGap(
                    afterPointIndex = index,
                    startTimestampMillis = first.timestampMillis,
                    endTimestampMillis = second.timestampMillis,
                    durationMillis = duration
                )
            }
        }
        return ValidationResult(errors.isEmpty(), errors.distinct(), gaps)
    }
}
