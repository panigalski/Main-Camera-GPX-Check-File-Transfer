package com.labpano.gpxextractor.output

import java.io.File

/** Pure filesystem policy used by the Android OUTPUT housekeeping wrapper and JVM tests. */
object ProcessingFolderCleanupPolicy {
    private val dateRegex = Regex("\\d{2}-\\d{2}-\\d{4}")
    const val PROCESSING_FOLDER_NAME = "PROCESSING"

    fun isRecordingDateFolder(name: String): Boolean = dateRegex.matches(name)

    /** Returns the number of empty legacy PROCESSING directories removed. */
    fun cleanupLocal(root: File): Int {
        if (!root.isDirectory) return 0
        var removed = 0
        root.listFiles()?.forEach { dateFolder ->
            if (!dateFolder.isDirectory || !isRecordingDateFolder(dateFolder.name)) return@forEach
            val processing = dateFolder.listFiles()?.firstOrNull {
                it.isDirectory && it.name.equals(PROCESSING_FOLDER_NAME, ignoreCase = true)
            } ?: return@forEach
            val children = processing.listFiles()
            if (children != null && children.isEmpty() && processing.delete()) removed++
        }
        return removed
    }
}
