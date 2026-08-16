package com.labpano.gpxextractor.monitor

/**
 * User-visible recording state policy.
 *
 * A Camera `fileChange` associated with a newly-created video is the strongest cross-app signal we
 * have that Pilot One started video capture. MP4 writes are not guaranteed to arrive continuously,
 * so write silence must never cancel that Camera lifecycle latch. The latched capture remains active
 * until Camera registers that same completed video with `addFile`, or a newer recording lifecycle
 * supersedes the previous one. File-handle/IMU close events are not public recording-stop signals.
 *
 * Filesystem-only detection is retained as a fallback for Camera builds where the broadcasts differ.
 * That fallback uses a longer freshness window and, importantly, is not converted into a sticky stop.
 */
internal object CaptureDisplayPolicy {
    const val FALLBACK_ACTIVITY_TIMEOUT_MS = 15_000L

    fun isBroadcastCaptureActive(
        startHintAt: Long,
        hardStopAt: Long
    ): Boolean {
        if (startHintAt <= 0L) return false
        return hardStopAt <= startHintAt
    }

    fun isFallbackActivityFresh(
        now: Long,
        lastWriteActivityAt: Long
    ): Boolean {
        if (lastWriteActivityAt <= 0L) return false
        return now - lastWriteActivityAt <= FALLBACK_ACTIVITY_TIMEOUT_MS
    }
}
