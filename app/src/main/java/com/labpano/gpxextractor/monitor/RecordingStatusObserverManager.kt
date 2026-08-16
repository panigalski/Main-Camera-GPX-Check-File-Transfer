package com.labpano.gpxextractor.monitor

import java.io.File

/**
 * Keeps a lightweight FileObserver alive while the Wi-Fi dashboard is being queried.
 *
 * Recording status must not depend on the processing monitor being enabled: the companion Client
 * can be connected while Monitoring is OFF. The processing service keeps its own observer; this
 * observer is status-only and never queues a file for processing.
 */
object RecordingStatusObserverManager {
    private var observer: RecordingFileObserver? = null
    private var watchedPath: String = ""

    @Synchronized
    fun ensureWatching(directory: File) {
        val canonical = runCatching { directory.canonicalFile }.getOrElse { directory.absoluteFile }
        val path = canonical.absolutePath
        if (observer != null && watchedPath == path) return

        stopLocked()
        if (!canonical.exists() || !canonical.isDirectory) return

        observer = RecordingFileObserver(canonical) { _, _ -> /* status-only observer */ }.also {
            it.startWatching()
        }
        watchedPath = path
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    private fun stopLocked() {
        observer?.stopWatching()
        observer = null
        watchedPath = ""
    }
}
