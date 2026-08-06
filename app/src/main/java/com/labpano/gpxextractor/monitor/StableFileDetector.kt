package com.labpano.gpxextractor.monitor

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks both file size and modification time and reports how long the same snapshot has remained
 * unchanged. A brief pause in size growth is not enough to declare a Pilot One MP4 complete.
 */
class StableFileDetector {
    data class Observation(
        val changed: Boolean,
        val unchangedForMillis: Long,
        val size: Long,
        val modifiedAt: Long
    )

    private data class Snapshot(
        val size: Long,
        val modifiedAt: Long,
        val unchangedSince: Long
    )

    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    fun observe(file: File, nowMillis: Long = System.currentTimeMillis()): Observation {
        if (!file.isFile) return Observation(true, 0L, 0L, 0L)

        val path = file.absolutePath
        val size = file.length()
        val modifiedAt = file.lastModified()
        val previous = snapshots[path]

        if (previous == null || previous.size != size || previous.modifiedAt != modifiedAt) {
            snapshots[path] = Snapshot(size, modifiedAt, nowMillis)
            return Observation(true, 0L, size, modifiedAt)
        }

        return Observation(
            changed = false,
            unchangedForMillis = (nowMillis - previous.unchangedSince).coerceAtLeast(0L),
            size = size,
            modifiedAt = modifiedAt
        )
    }

    fun forget(file: File) {
        snapshots.remove(file.absolutePath)
    }
}
