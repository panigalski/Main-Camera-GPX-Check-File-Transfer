package com.labpano.gpxextractor

/** Pure preference migration rules for defaults introduced by older releases. */
object PathMigrationPolicy {
    const val CURRENT_STITCHED_PATH = "/sdcard/DCIM/Videos/Stitched"

    private val recordingDefaultsToMigrate = setOf(
        "/storage/emulated/0/videos/stitched",
        "/sdcard/DCIM/Videos/Stichted",
        "/storage/emulated/0/DCIM/Videos/Stichted"
    )

    private val outputDefaultsToMigrate = setOf(
        "/storage/emulated/0/videos/stitched",
        "/sdcard/DCIM/Videos/Stichted",
        "/storage/emulated/0/DCIM/Videos/Stichted"
    )

    fun recordingPath(stored: String?): String = when {
        stored.isNullOrBlank() -> CURRENT_STITCHED_PATH
        normalize(stored) in recordingDefaultsToMigrate.map(::normalize).toSet() -> CURRENT_STITCHED_PATH
        else -> stored
    }

    fun outputPath(stored: String?): String = when {
        stored.isNullOrBlank() -> CURRENT_STITCHED_PATH
        normalize(stored) in outputDefaultsToMigrate.map(::normalize).toSet() -> CURRENT_STITCHED_PATH
        else -> stored
    }

    fun recordingWasMigrated(stored: String?): Boolean = recordingPath(stored) != stored
    fun outputWasMigrated(stored: String?): Boolean = outputPath(stored) != stored

    private fun normalize(value: String): String = value.trim().trimEnd('/').lowercase()
}
