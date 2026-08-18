package com.labpano.gpxextractor.mp4

data class CammSample(
    /** Presentation time mapped onto the MP4 movie timeline, not only the CAMM media timeline. */
    val presentationTimeUs: Long,
    val durationUs: Long,
    val fileOffset: Long,
    val size: Int
)

data class CammTrack(
    val trackId: Long,
    val timescale: Long,
    val creationTimeUnixMillis: Long?,
    val durationUs: Long,
    val samples: List<CammSample>
)

data class GpsPoint(
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val horizontalAccuracyMeters: Double? = null,
    val verticalAccuracyMeters: Double? = null
)

data class GpsTimingDiagnostics(
    val anchorSource: String,
    val videoStartMillis: Long?,
    val videoEndMillis: Long?,
    val gpxStartMillis: Long,
    val gpxEndMillis: Long,
    val appliedTimestampShiftMillis: Long,
    val overlapMillis: Long?,
    /** Canonical timestamp strategy used for every decoded CAMM sample. */
    val timelineStrategy: String = "legacy",
    /** Type-6 packet clocks that disagreed materially with their MP4 presentation time. */
    val rawGpsClockDiscontinuityCount: Int = 0,
    /** Valid decoded CAMM GPS packets before near-duplicate collapse. */
    val decodedGpsSampleCount: Int = 0,
    /** Genuine CAMM GPS samples after near-duplicate collapse and before GPX densification. */
    val canonicalGpsSampleCount: Int = 0
)

data class VideoTimeline(
    val startMillis: Long,
    val endMillis: Long,
    val source: String
)

data class CammParseResult(
    val points: List<GpsPoint>,
    val timing: GpsTimingDiagnostics
)
