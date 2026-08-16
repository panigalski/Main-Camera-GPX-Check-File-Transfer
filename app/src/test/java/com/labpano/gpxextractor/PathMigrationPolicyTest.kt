package com.labpano.gpxextractor

import org.junit.Assert.assertEquals
import org.junit.Test

class PathMigrationPolicyTest {
    @Test fun migratesMisspelled0519RecordingDefault() {
        assertEquals(PathMigrationPolicy.CURRENT_STITCHED_PATH, PathMigrationPolicy.recordingPath("/sdcard/DCIM/Videos/Stichted"))
        assertEquals(PathMigrationPolicy.CURRENT_STITCHED_PATH, PathMigrationPolicy.recordingPath("/storage/emulated/0/DCIM/Videos/Stichted"))
    }

    @Test fun migratesOldVideosDefault() {
        assertEquals(PathMigrationPolicy.CURRENT_STITCHED_PATH, PathMigrationPolicy.recordingPath("/storage/emulated/0/videos/stitched"))
        assertEquals(PathMigrationPolicy.CURRENT_STITCHED_PATH, PathMigrationPolicy.outputPath("/storage/emulated/0/videos/stitched"))
    }

    @Test fun preservesExplicitCustomPaths() {
        assertEquals("/sdcard/MyRecordings", PathMigrationPolicy.recordingPath("/sdcard/MyRecordings"))
        assertEquals("/sdcard/MyOutput", PathMigrationPolicy.outputPath("/sdcard/MyOutput"))
    }
}
