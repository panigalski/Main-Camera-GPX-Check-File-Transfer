package com.labpano.gpxextractor.monitor

import android.os.FileObserver
import java.io.File

@Suppress("DEPRECATION")
class RecordingFileObserver(
    private val directory: File,
    private val onCandidate: (Int, File) -> Unit
) : FileObserver(directory.absolutePath, EVENTS) {

    override fun onEvent(event: Int, path: String?) {
        if (path.isNullOrBlank()) return
        val relevantEvent = event and FileObserver.ALL_EVENTS
        val file = File(directory, path)

        // Camera 5.18.x can optionally create <video-name>.imu beside the recording. Keep the
        // event hook for diagnostics, but IMU close is not treated as a Camera record-stop signal.
        if (file.name.endsWith(".imu", ignoreCase = true)) {
            CameraRecordingStatusRegistry.onImuFileEvent(relevantEvent, file)
            return
        }

        val lowerName = file.name.lowercase()
        val appTemporary = lowerName.endsWith(".gpx.part") || lowerName.endsWith(".gpx.tmp")
        val statusVideo = !appTemporary && (lowerName.endsWith(".mp4") || lowerName.endsWith(".sti") ||
            lowerName.endsWith(".mp4.part") || lowerName.endsWith(".mp4.tmp") ||
            lowerName.endsWith(".sti.part") || lowerName.endsWith(".sti.tmp") ||
            lowerName.endsWith(".part") || lowerName.endsWith(".tmp"))
        if (!statusVideo) return

        // Status observation must see temporary Camera writer aliases too. The high-frequency
        // /live-status endpoint does not perform a directory scan, so ignoring `.part`/`.tmp`
        // CREATE events can otherwise make a valid Camera start hint disappear on the next poll.
        CameraRecordingStatusRegistry.onFileEvent(relevantEvent, file)

        // Forward all Camera video-writer events. The processing engine uses CREATE/MODIFY plus
        // periodic stat growth to independently prove fragment rollover, while it still consumes only
        // finalized MP4 files after Mp4ReadinessChecker succeeds.
        onCandidate(relevantEvent, file)
    }

    companion object {
        private const val EVENTS = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
            FileObserver.CREATE or FileObserver.MODIFY or FileObserver.DELETE or FileObserver.MOVED_FROM
    }
}
