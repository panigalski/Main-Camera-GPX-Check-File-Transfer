package com.labpano.gpxextractor.monitor

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * Best cross-process recording-family signal available from the stock Camera 5.18.11 app.
 *
 * Camera keeps its currently highlighted PreviewViewModel.mCameraMode inside the Camera process,
 * so a third-party Main App cannot read an idle mode switch directly. Fragment Storage is different:
 * each recording family writes a distinct /efs/video.properties prefix. When one prefix changes we
 * therefore know exactly which Camera settings screen the user just edited.
 *
 * A Fragment Storage edit is a short-lived mode hint, not durable proof of the Camera's current
 * recording family. Persisting that hint across Main-App restarts previously caused stale modes to
 * be reported indefinitely. Keep it process-local and expire it after a short interval. An active
 * unambiguous recording path always wins while it exists.
 */
object PilotCameraModeRegistry {
    data class Snapshot(
        val mode: String,
        val source: String,
        val updatedAt: Long
    )

    @Volatile private var runtime = Snapshot("", "unknown", 0L)
    @Volatile private var runtimeObservedElapsedRealtime = 0L
    @Volatile private var activeRuntime = Snapshot("", "unknown", 0L)
    @Volatile private var activePathKey = ""

    /** Pure mapping kept internal for regression tests. */
    internal fun modeForPropertyPrefix(propertyPrefix: String): String = when (propertyPrefix) {
        "video" -> "stitched"
        "video_fishEye" -> "unstitched"
        "video_streetView" -> "streetView"
        "video_timeLapse" -> "timeLapse"
        else -> ""
    }

    @Synchronized
    fun observeFragmentStorageChange(context: Context, propertyPrefix: String, source: String) {
        // Retain Context in the call contract used by both local readers; no Camera state is stored
        // in Main-App preferences because an old edit must never become a future "current mode".
        context.applicationContext
        val mode = modeForPropertyPrefix(propertyPrefix)
        if (mode.isBlank()) return
        runtime = Snapshot(mode, "fragment-storage-change:$source", System.currentTimeMillis())
        runtimeObservedElapsedRealtime = elapsedRealtime()
    }

    /**
     * A live /Unstitched/ MP4 is an unambiguous stronger signal. The generic Stitched directory is
     * deliberately not mapped because Camera writes both normal stitched and Street View there.
     */
    @Synchronized
    fun snapshot(context: Context, activeVideoPath: String = ""): Snapshot {
        context.applicationContext
        modeForActiveVideoPath(activeVideoPath)?.let { activeMode ->
            val key = activeVideoPath.trim().lowercase()
            if (activeRuntime.mode != activeMode || activeRuntime.source != "active-recording-path" || activePathKey != key) {
                activeRuntime = Snapshot(activeMode, "active-recording-path", System.currentTimeMillis())
                activePathKey = key
            }
            return activeRuntime
        }
        activePathKey = ""
        activeRuntime = Snapshot("", "unknown", 0L)
        return if (isModeHintFresh(runtimeObservedElapsedRealtime, elapsedRealtime())) {
            runtime
        } else {
            Snapshot("", "unknown", runtime.updatedAt)
        }
    }

    /** Current short-lived Camera-property hint for internal ambiguity handling. */
    @Synchronized
    fun currentFreshMode(): String = if (
        isModeHintFresh(runtimeObservedElapsedRealtime, elapsedRealtime())
    ) runtime.mode else ""

    internal fun isModeHintFresh(observedElapsedRealtime: Long, nowElapsedRealtime: Long): Boolean {
        if (observedElapsedRealtime <= 0L || nowElapsedRealtime < observedElapsedRealtime) return false
        return nowElapsedRealtime - observedElapsedRealtime <= MODE_HINT_TTL_MS
    }

    internal fun modeForActiveVideoPath(path: String): String? {
        if (path.isBlank()) return null
        val normalized = runCatching { File(path).absolutePath.lowercase() }.getOrDefault(path.lowercase())
        return when {
            normalized.contains("/unstitched/") || normalized.contains("/fisheye/") -> "unstitched"
            normalized.contains("/streetview/") || normalized.contains("/street_view/") -> "streetView"
            normalized.contains("/timelapse/") || normalized.contains("/time_lapse/") -> "timeLapse"
            // Intentionally no /Stitched/ -> stitched mapping: Street View uses that directory too.
            else -> null
        }
    }

    private fun elapsedRealtime(): Long = runCatching { SystemClock.elapsedRealtime() }
        .getOrElse { System.nanoTime() / 1_000_000L }

    internal const val MODE_HINT_TTL_MS = 30_000L
}
