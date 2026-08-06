package com.labpano.gpxextractor.monitor

import android.os.FileObserver
import java.io.File

@Suppress("DEPRECATION")
class RecordingFileObserver(
    private val directory: File,
    private val onCandidate: (File) -> Unit
) : FileObserver(directory.absolutePath, EVENTS) {

    override fun onEvent(event: Int, path: String?) {
        if (path.isNullOrBlank()) return
        val relevantEvent = event and FileObserver.ALL_EVENTS
        if (relevantEvent == FileObserver.CLOSE_WRITE ||
            relevantEvent == FileObserver.MOVED_TO ||
            relevantEvent == FileObserver.CREATE
        ) {
            val file = File(directory, path)
            if (file.extension.equals("mp4", ignoreCase = true)) onCandidate(file)
        }
    }

    companion object {
        private const val EVENTS = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE
    }
}
