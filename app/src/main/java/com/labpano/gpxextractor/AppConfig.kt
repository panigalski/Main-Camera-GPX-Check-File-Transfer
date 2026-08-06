package com.labpano.gpxextractor

import android.os.Environment
import java.io.File

object AppConfig {
    const val NOTIFICATION_CHANNEL_ID = "recording_monitor"
    const val NOTIFICATION_ID = 1001
    const val DATABASE_NAME = "processed_recordings.db"
    const val DATABASE_VERSION = 6
    const val PROCESSOR_VERSION = 11
    const val DEFAULT_GAP_THRESHOLD_SECONDS = 5L

    const val MAX_PROCESSING_ATTEMPTS = 7
    const val MAX_RETRY_AGE_MS = 30L * 60L * 1000L
    const val RETRY_BASE_DELAY_MS = 30_000L
    const val RETRY_MAX_DELAY_MS = 15L * 60L * 1000L

    const val MAX_PENDING_QUEUE_ROWS = 5_000
    const val PENDING_QUEUE_RETENTION_MS = 180L * 24L * 60L * 60L * 1000L
    const val MAX_PENDING_API_PAGE_SIZE = 5_000

    const val MAX_ROLLING_REPORT_BYTES = 8L * 1024L * 1024L
    const val MAX_ROLLING_REPORT_LINES = 5_000

    val defaultOutputDirectory: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "Videos/GPX-Ready"
        )

    val defaultRecordingDirectory: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "Videos/Stitched"
        )
}
