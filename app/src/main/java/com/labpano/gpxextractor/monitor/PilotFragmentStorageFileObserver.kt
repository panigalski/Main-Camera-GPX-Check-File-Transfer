package com.labpano.gpxextractor.monitor

import android.content.Context
import android.os.FileObserver
import android.os.SystemClock
import com.labpano.gpxextractor.util.AppLog
import java.io.File

/**
 * Watches the exact Camera 5.18.11 Fragment Storage backing file.
 *
 * The supplied stock Camera APK writes /efs/video.properties synchronously with FileOutputStream
 * whenever StoragePartModel changes storagePart.able or storagePart.value. Its UI notification is an
 * in-process EventBus event, so another app cannot receive it. Watching the backing file is therefore
 * the reliable cross-process update signal; Dashboard polling remains a fallback if inotify is not
 * available on a particular Pilot OS build.
 *
 * /live-status can be polled several times per second. If /efs is permission-restricted, repeatedly
 * forcing the same failed FileInputStream/open-watch attempt would waste CPU and flood logs, so watch
 * installation retries are back-listed for a few seconds. The normal Local/Settings readers keep
 * their own independent polling cadence during that interval.
 */
@Suppress("DEPRECATION")
object PilotFragmentStorageFileObserver {
    @Volatile private var observer: FileObserver? = null
    @Volatile private var observedPath: String = ""
    @Volatile private var nextWatchAttemptElapsedRealtime: Long = 0L

    @Synchronized
    fun ensureWatching(context: Context) {
        val appContext = context.applicationContext
        val target = File(PilotFragmentStorageLocalReader.VIDEO_PROPERTIES_PATH)
        val path = target.absolutePath
        if (observer != null && observedPath == path) return

        val now = elapsedRealtime()
        if (now < nextWatchAttemptElapsedRealtime) return

        stopWatching(resetRetry = false)
        // Take one fresh snapshot before installing the watcher. Connection handshake separately
        // forces its own read, so this path only needs to execute when a watch attempt is due.
        PilotFragmentStorageLocalReader.refresh(appContext, force = true)

        if (!target.exists() || !target.canRead()) {
            nextWatchAttemptElapsedRealtime = now + WATCH_RETRY_MS
            AppLog.warn("Fragment Storage file observer unavailable for $path; polling remains active")
            return
        }

        try {
            observer = object : FileObserver(path, EVENTS) {
                override fun onEvent(event: Int, path: String?) {
                    val relevant = event and FileObserver.ALL_EVENTS
                    if (relevant == 0) return
                    PilotFragmentStorageLocalReader.refresh(appContext, force = true)
                    AppLog.info("Pilot Fragment Storage properties changed; refreshed from /efs")
                    if (relevant == FileObserver.DELETE_SELF || relevant == FileObserver.MOVE_SELF) {
                        synchronized(this@PilotFragmentStorageFileObserver) {
                            observer?.stopWatching()
                            observer = null
                            observedPath = ""
                            nextWatchAttemptElapsedRealtime = 0L
                        }
                    }
                }
            }.also { it.startWatching() }
            observedPath = path
            nextWatchAttemptElapsedRealtime = 0L
            AppLog.info("Watching Pilot Fragment Storage properties: $path")
        } catch (error: Throwable) {
            observer = null
            observedPath = ""
            nextWatchAttemptElapsedRealtime = now + WATCH_RETRY_MS
            AppLog.warn("Cannot watch Pilot Fragment Storage properties: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun stopWatching() = stopWatching(resetRetry = true)

    private fun stopWatching(resetRetry: Boolean) {
        observer?.stopWatching()
        observer = null
        observedPath = ""
        if (resetRetry) nextWatchAttemptElapsedRealtime = 0L
    }

    private fun elapsedRealtime(): Long = runCatching { SystemClock.elapsedRealtime() }
        .getOrElse { System.nanoTime() / 1_000_000L }

    private const val WATCH_RETRY_MS = 5_000L
    private const val EVENTS = FileObserver.MODIFY or FileObserver.CLOSE_WRITE or
        FileObserver.ATTRIB or FileObserver.MOVE_SELF or FileObserver.DELETE_SELF
}
