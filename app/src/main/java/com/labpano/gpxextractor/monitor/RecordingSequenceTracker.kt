package com.labpano.gpxextractor.monitor

/**
 * Tracks the Camera recording as an ordered sequence of newly-created MP4 files.
 *
 * The rule intentionally does not depend on Fragment Storage settings or Camera divider broadcasts:
 * - capture a baseline of MP4 files before the next recording starts;
 * - the first MP4 not in that baseline is the active file A;
 * - when a distinct new MP4 B appears, A is complete enough to enter the normal settle/check queue;
 * - B becomes active; C releases B; and so on;
 * - the final active file is released only when the overall recording-stop signal is received.
 *
 * This mirrors the operator-visible filesystem behavior and keeps the current writer protected while
 * allowing every predecessor to be processed during the same ongoing recording.
 */
class RecordingSequenceTracker(initialBaseline: Collection<String> = emptyList()) {
    data class SnapshotResult(
        val activePath: String?,
        val newlyDiscovered: List<String>,
        val newlyReleased: List<String>
    )

    private val baseline = LinkedHashSet<String>()
    private val discovered = LinkedHashSet<String>()
    private var activePath: String? = null

    init {
        baseline += initialBaseline
    }

    @Synchronized
    fun resetBaseline(paths: Collection<String>) {
        baseline.clear()
        baseline += paths
        discovered.clear()
        activePath = null
    }

    /** FileObserver fast path. Event order is the best available creation order. */
    @Synchronized
    fun observeNewPath(path: String): SnapshotResult {
        if (path in baseline || path in discovered) {
            return SnapshotResult(activePath, emptyList(), emptyList())
        }
        val released = activePath?.takeIf { it != path }?.let(::listOf).orEmpty()
        discovered += path
        activePath = path
        return SnapshotResult(activePath, listOf(path), released)
    }

    /**
     * Periodic fallback for FileObserver events that Android/Pilot storage may drop. Paths must be
     * provided in the best available creation order; already-observed paths are ignored.
     */
    @Synchronized
    fun observeSnapshot(orderedCurrentPaths: List<String>): SnapshotResult {
        val newlyDiscovered = ArrayList<String>()
        val newlyReleased = ArrayList<String>()

        for (path in orderedCurrentPaths) {
            if (path in baseline || path in discovered) continue
            val result = observeNewPath(path)
            newlyDiscovered += result.newlyDiscovered
            newlyReleased += result.newlyReleased
        }

        return SnapshotResult(activePath, newlyDiscovered, newlyReleased)
    }

    /**
     * Release the last file after Camera recording has stopped, then take a fresh baseline so the
     * next new MP4 belongs to a new sequence even if the previous final file has not moved yet.
     */
    @Synchronized
    fun finishRecording(orderedCurrentPaths: List<String>): SnapshotResult {
        val beforeStop = observeSnapshot(orderedCurrentPaths)
        val released = ArrayList<String>(beforeStop.newlyReleased)
        activePath?.let { current ->
            if (current !in released) released += current
        }

        val discoveredNow = beforeStop.newlyDiscovered
        baseline.clear()
        baseline += orderedCurrentPaths
        discovered.clear()
        activePath = null

        return SnapshotResult(
            activePath = null,
            newlyDiscovered = discoveredNow,
            newlyReleased = released
        )
    }

    @Synchronized
    fun isActive(path: String): Boolean = activePath == path

    @Synchronized
    fun activePath(): String? = activePath

    @Synchronized
    fun baselineSnapshot(): Set<String> = LinkedHashSet(baseline)
}
