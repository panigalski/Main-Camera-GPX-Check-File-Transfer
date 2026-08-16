package com.labpano.gpxextractor.monitor

import android.os.Environment
import android.os.FileObserver
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-app view of Pilot One recording activity.
 *
 * Labpano Camera 5.18.x maintains the actual recording Boolean inside the Camera app. The public
 * Pilot SDK exposes startRecord()/stopRecord(), but not a cross-app recording-state callback. The
 * Camera app does emit two useful gallery broadcasts observed on Pilot One:
 * - com.pi.pilot.gallery.fileChange from record/photo start paths, and
 * - com.pi.pilot.gallery.addFile when completed media is registered.
 *
 * fileChange is therefore only a start hint and must be associated with a newly-created video.
 * Once associated, that exact video remains protected until Camera completion OR a distinct next
 * video appears in the same Camera start generation (Fragment Storage rollover). MP4 CLOSE_WRITE/
 * IMU-close events are deliberately NOT treated as capture stop: a writer/provider
 * can close or reopen file handles during an ongoing recording and those events are not part of the
 * public Pilot recording lifecycle contract.
 *
 * A crucial rule is that completion is per-video. A delayed addFile for the previous recording must
 * never clear the latch for a newer recording that has already started.
 */
object CameraRecordingStatusRegistry {
    data class Snapshot(
        val available: Boolean,
        /** Compatibility/internal lifecycle state; per-file processing safety uses isRecordingFile(). */
        val recording: Boolean,
        /** User-visible capture state returned to the Client. */
        val captureRecording: Boolean,
        val videoName: String,
        val updatedAt: Long,
        val source: String,
        val finalizing: Boolean = recording && !captureRecording,
        /** Monotonic Camera start generation. 0 means filesystem fallback only. */
        val lifecycleGeneration: Long = 0L
    )

    private data class ActiveFile(val file: File, val lastActivityAt: Long)
    private data class ObservedFile(
        val file: File,
        val size: Long,
        val modifiedAt: Long,
        val lastGrowthAt: Long,
        val firstSeenAt: Long
    )

    private val active = ConcurrentHashMap<String, ActiveFile>()
    private val observed = ConcurrentHashMap<String, ObservedFile>()
    private val recentlyClosed = ConcurrentHashMap<String, Long>()
    private val recentlyCompleted = ConcurrentHashMap<String, Long>()

    @Volatile private var lastUpdatedAt: Long = 0L
    @Volatile private var lastVideoName: String = ""

    @Volatile private var pilotStartHintAt: Long = 0L
    @Volatile private var pilotStartGeneration: Long = 0L
    @Volatile private var broadcastLatchedRecording: Boolean = false
    @Volatile private var broadcastLatchedPath: String = ""
    @Volatile private var broadcastLatchedAt: Long = 0L
    @Volatile private var broadcastLatchedGeneration: Long = 0L
    @Volatile private var lastPilotCompletionAt: Long = 0L

    // With Fragment Storage enabled, Camera can finalize one MP4 and immediately continue the
    // same recording into another MP4. addFile for the current fragment makes that file safe for
    // processing, but is not automatically an overall recording-stop. We keep the user-visible
    // Recording state latched for a short continuation window; creation of the next fragment hands
    // active ownership forward without changing the Camera lifecycle generation.
    @Volatile private var fragmentCompletionCandidateAt: Long = 0L
    @Volatile private var fragmentCompletionCandidateKey: String = ""

    /**
     * Called for Labpano Camera's com.pi.pilot.gallery.fileChange broadcast.
     *
     * @return true when this is Camera 5.18.11 Divider's internal fragment restart rather than a
     * brand-new user recording. The receiver uses this to request an accelerated completed scan.
     */
    @Synchronized
    fun onPilotFileChangeBroadcast(): Boolean {
        val now = System.currentTimeMillis()
        // Refresh the read-only Camera option at every record-start callback. Camera 5.18.11 invokes
        // this callback for BOTH the user's first fragment and Divider's internal fragment restarts.
        PilotFragmentStorageRegistry.refreshAsync(force = true)
        pilotStartHintAt = now

        // Camera 5.18.11's actual Divider sequence (verified from the supplied stock APK) is:
        // previous low-level onRecordStop -> restart() -> doStart(next filename) -> onRecordStart ->
        // notifyChangeFile(). Therefore a repeated gallery.fileChange while Fragment Storage is
        // enabled proves that the PREVIOUS latched fragment has already been stopped by Camera.
        // Release that exact predecessor immediately; do not wait for gallery.addFile because the
        // stock app only registers addFile on the final overall stop. The next CREATE/MODIFY scan
        // hands the latch to the successor while the user-visible capture stays Recording.
        val fileChangeKind = PilotDividerLifecyclePolicy.classify(
            hasLatchedVideo = broadcastLatchedRecording,
            fragmentStorageEnabled = fragmentStorageEnabledForLatched()
        )
        val fragmentContinuationSignal =
            fileChangeKind == PilotDividerLifecyclePolicy.FileChangeKind.FRAGMENT_RESTART
        if (fragmentContinuationSignal) {
            releaseLatchedFragmentForDividerRestart(now)
            lastUpdatedAt = now
            return true
        }

        pilotStartGeneration += 1L
        lastUpdatedAt = now

        // fileChange is also emitted for photos. Only associate a video that this process first
        // observed very close to this start hint. An old MP4 receiving finalization writes is not a
        // new recording.
        val candidate = active.values
            .filter { now - it.lastActivityAt <= PILOT_NEW_FILE_ASSOCIATION_MS }
            .mapNotNull { activeFile ->
                val key = canonicalKey(activeFile.file)
                if (isRecentlyCompletedKey(key, now)) return@mapNotNull null
                // A new fileChange must not simply assign the new generation to the video that was
                // already latched by the previous generation. Back-to-back recordings can overlap
                // Camera finalization for several seconds; wait for the genuinely new file instead.
                if (broadcastLatchedRecording && matchesLatchedVideoKey(key)) return@mapNotNull null
                observed[key]?.takeIf { observedFile ->
                    kotlin.math.abs(observedFile.firstSeenAt - now) <= PILOT_NEW_FILE_ASSOCIATION_MS
                }
            }
            .maxByOrNull { candidateActivityAt(it) }
        candidate?.let(::latchCandidate)
        return false
    }

    /** Called for Labpano Camera's com.pi.pilot.gallery.addFile broadcast. */
    @Synchronized
    fun onPilotAddFileBroadcast(path: String?, fileType: Int) {
        val normalized = path.orEmpty().trim()
        if (!isLikelyVideoPath(normalized)) return

        val now = System.currentTimeMillis()
        val completedFile = File(normalized)
        val completedKey = canonicalKey(completedFile)
        recentlyCompleted[completedKey] = now
        recentlyClosed[completedKey] = now
        val matchingObservedKeys = (active.keys + observed.keys)
            .filter { knownKey -> sameVideoKey(knownKey, completedKey) }
            .distinct()
        matchingObservedKeys.forEach { knownKey ->
            active.remove(knownKey)
            recentlyClosed[knownKey] = now
            observed[knownKey]?.let { current ->
                observed[knownKey] = current.copy(lastGrowthAt = 0L)
            }
        }

        // addFile can arrive late. Only release the Camera latch when this completion belongs to the
        // currently latched video. A previous recording finishing after a new recording starts must
        // not make the Client fall back to Ready.
        if (matchesLatchedVideoKey(completedKey)) {
            // A matching addFile proves THIS file is complete and safe to process, but it does not
            // prove the overall Camera capture stopped: Pilot Fragment Storage may immediately roll
            // into another MP4. Do not let an unavailable/partial/misreported getOptions snapshot
            // turn that fragment completion into a false Ready. Hold the capture display for one
            // bounded continuation window; a distinct next video keeps the same generation alive,
            // otherwise expireTransientState() performs the single final Ready transition.
            fragmentCompletionCandidateAt = now
            fragmentCompletionCandidateKey = completedKey
        }

        val completedName = completedFile.name
        if (completedName.isNotBlank() && isVideoCandidate(completedFile)) {
            lastVideoName = completedName
        }
        lastUpdatedAt = now
    }

    /**
     * IMU sidecar lifecycle is intentionally ignored for capture stop. The public Pilot SDK does not
     * define an IMU-close event as record-stop, and some Camera builds can rotate/close sidecar
     * writers independently from the ongoing MP4 capture.
     */
    @Synchronized
    fun onImuFileEvent(event: Int, file: File) {
        // Kept as an API hook for RecordingFileObserver compatibility. Do not change recording state.
    }

    @Synchronized
    fun onFileEvent(event: Int, file: File) {
        if (!isVideoCandidate(file)) return
        val now = System.currentTimeMillis()
        val key = canonicalKey(file)
        val relevantEvent = event and FileObserver.ALL_EVENTS
        when (relevantEvent) {
            FileObserver.CREATE, FileObserver.MODIFY -> {
                val previous = observed[key]
                val completedAlias = isRecentlyCompletedKey(key, now)
                val newerStartPending = pilotStartGeneration > broadcastLatchedGeneration &&
                    now - pilotStartHintAt in 0..PILOT_START_ASSOCIATION_WINDOW_MS

                // Providers may emit final metadata MODIFY events after Camera addFile. Those must
                // not resurrect filesystem fallback Recording. A genuinely new CREATE associated
                // with a newer Camera start is allowed to reuse/replace the identity.
                if (completedAlias && !(relevantEvent == FileObserver.CREATE && newerStartPending)) {
                    active.remove(key)
                    recentlyClosed[key] = recentlyClosed[key] ?: now
                    observed[key] = ObservedFile(
                        file = file,
                        size = file.length().coerceAtLeast(0L),
                        modifiedAt = file.lastModified().coerceAtLeast(0L),
                        lastGrowthAt = 0L,
                        firstSeenAt = previous?.firstSeenAt ?: now
                    )
                    lastVideoName = file.name
                    lastUpdatedAt = now
                    return
                }

                recentlyClosed.remove(key)
                if (relevantEvent == FileObserver.CREATE) clearCompletedAliases(key)
                active[key] = ActiveFile(file, now)
                observed[key] = ObservedFile(
                    file = file,
                    size = file.length().coerceAtLeast(0L),
                    modifiedAt = file.lastModified().coerceAtLeast(0L),
                    lastGrowthAt = now,
                    firstSeenAt = previous?.firstSeenAt ?: now
                )

                if (relevantEvent == FileObserver.CREATE) {
                    maybeHandoffFragment(file, key, now)
                }

                // If fileChange arrived just before the SDK-created MP4 became visible, associate it
                // directly from FileObserver rather than waiting for the next directory scan.
                if (now - pilotStartHintAt in 0..PILOT_START_ASSOCIATION_WINDOW_MS &&
                    (relevantEvent == FileObserver.CREATE ||
                        kotlin.math.abs((previous?.firstSeenAt ?: now) - pilotStartHintAt) <=
                            PILOT_NEW_FILE_ASSOCIATION_MS) &&
                    (!broadcastLatchedRecording ||
                        (broadcastLatchedGeneration < pilotStartGeneration && !matchesLatchedVideoKey(key)))
                ) {
                    // A newly-created video associated with a newer fileChange supersedes an older
                    // recording whose delayed addFile has not arrived yet. Without this handoff,
                    // the old completion could clear the latch for the new recording.
                    broadcastLatchedRecording = true
                    broadcastLatchedPath = key
                    broadcastLatchedAt = pilotStartHintAt.takeIf { it > 0L } ?: now
                    broadcastLatchedGeneration = pilotStartGeneration
                    fragmentCompletionCandidateAt = 0L
                    fragmentCompletionCandidateKey = ""
                    clearCompletedAliases(key)
                    clearClosedAliases(key)
                }

                lastVideoName = file.name
                lastUpdatedAt = maxOf(lastUpdatedAt, now, previous?.lastGrowthAt ?: 0L)
            }

            FileObserver.CLOSE_WRITE, FileObserver.MOVED_TO,
            FileObserver.DELETE, FileObserver.MOVED_FROM -> {
                // CLOSE_WRITE is not an overall Pilot record-stop signal. Fragment rollover is
                // established by the next distinct video in the same Camera start generation.
                active.remove(key)
                recentlyClosed[key] = now
                val current = observed[key]
                observed[key] = if (current != null) {
                    current.copy(
                        size = file.length().coerceAtLeast(0L),
                        modifiedAt = file.lastModified().coerceAtLeast(0L),
                        lastGrowthAt = 0L
                    )
                } else {
                    ObservedFile(
                        file = file,
                        size = file.length().coerceAtLeast(0L),
                        modifiedAt = file.lastModified().coerceAtLeast(0L),
                        lastGrowthAt = 0L,
                        firstSeenAt = now
                    )
                }
                if (relevantEvent == FileObserver.MOVED_TO) {
                    maybeHandoffFragment(file, key, now)
                }
                lastVideoName = file.name
                lastUpdatedAt = now
            }
        }
    }

    @Synchronized
    fun snapshot(configuredRecordingDirectory: File): Snapshot {
        val now = System.currentTimeMillis()
        expireTransientState(now)

        val scanDirectories = recordingDirectories(configuredRecordingDirectory)
        val readableDirectories = scanDirectories.filter { it.exists() && it.isDirectory && it.canRead() }
        val presentKeys = HashSet<String>()
        var scanCandidate: ObservedFile? = null
        var freshBroadcastCandidate: ObservedFile? = null
        var fragmentRotationCandidate: ObservedFile? = null

        for (directory in readableDirectories) {
            val files = directory.listFiles { file -> file.isFile && isVideoCandidate(file) }.orEmpty()
            for (file in files) {
                val key = canonicalKey(file)
                presentKeys += key
                val size = file.length().coerceAtLeast(0L)
                val modifiedAt = file.lastModified().coerceAtLeast(0L)
                val previous = observed[key]
                val firstSeenAt = previous?.firstSeenAt ?: now

                val changed = previous != null && (size != previous.size || modifiedAt != previous.modifiedAt)
                val firstSeenRecent = previous == null && modifiedAt > 0L &&
                    now - modifiedAt in 0..FIRST_SEEN_RECENT_MS

                var growthAt = previous?.lastGrowthAt ?: 0L
                if (changed || firstSeenRecent) {
                    if (changed) {
                        recentlyClosed.remove(key)
                    }
                    if (recentlyClosed[key] == null) {
                        growthAt = now
                        lastVideoName = file.name
                        lastUpdatedAt = now
                    }
                }

                val current = ObservedFile(file, size, modifiedAt, growthAt, firstSeenAt)
                observed[key] = current

                if (broadcastLatchedRecording && !matchesLatchedVideoKey(key) &&
                    isSameDirectoryAsLatched(file) &&
                    (changed || firstSeenRecent) && modifiedAt >= broadcastLatchedAt &&
                    (fragmentRotationCandidate == null ||
                        candidateActivityAt(current) > candidateActivityAt(fragmentRotationCandidate!!))
                ) {
                    fragmentRotationCandidate = current
                }

                if (recentlyClosed[key] == null && growthAt > 0L &&
                    now - growthAt <= SCAN_GROWTH_ACTIVE_TIMEOUT_MS &&
                    (scanCandidate == null || growthAt > scanCandidate!!.lastGrowthAt)
                ) {
                    scanCandidate = current
                }

                // Camera's fileChange is also emitted for photos. Only accept it as a video-start
                // signal when a newly-observed video is close to the broadcast.
                if (recentlyClosed[key] == null &&
                    !isRecentlyCompletedKey(key, now) &&
                    now - pilotStartHintAt in 0..PILOT_START_ASSOCIATION_WINDOW_MS &&
                    isFreshForStartHint(current, pilotStartHintAt, now) &&
                    // During a newer start generation, never re-associate the video already owned
                    // by the previous generation even if it is still receiving finalization writes.
                    (!broadcastLatchedRecording ||
                        (broadcastLatchedGeneration < pilotStartGeneration && !matchesLatchedVideoKey(key))) &&
                    (freshBroadcastCandidate == null ||
                        candidateActivityAt(current) > candidateActivityAt(freshBroadcastCandidate!!))
                ) {
                    freshBroadcastCandidate = current
                }
            }
        }

        clearMissingFiles(presentKeys, readableDirectories.isNotEmpty())
        fragmentRotationCandidate?.let { candidate ->
            maybeHandoffFragment(candidate.file, canonicalKey(candidate.file), now)
        }
        freshBroadcastCandidate?.takeIf { candidate ->
            !broadcastLatchedRecording ||
                (broadcastLatchedGeneration < pilotStartGeneration &&
                    !matchesLatchedVideoKey(canonicalKey(candidate.file)))
        }?.let(::latchCandidate)
        return snapshotFromCurrentState(now, scanCandidate)
    }

    /** Exact currently-latched video path when Camera lifecycle tracking owns one. */
    @Synchronized
    fun activeVideoPath(): String = if (broadcastLatchedRecording) broadcastLatchedPath else ""

    /** Lightweight snapshot for the companion Client's high-frequency live-status endpoint. */
    @Synchronized
    fun fastSnapshot(): Snapshot {
        val now = System.currentTimeMillis()
        expireTransientState(now)
        return snapshotFromCurrentState(now, null)
    }

    /** True for a short window after Camera explicitly registered this exact video as completed. */
    @Synchronized
    fun wasRecentlyCompleted(file: File, now: Long = System.currentTimeMillis()): Boolean {
        expireCompleted(now)
        return isRecentlyCompletedKey(canonicalKey(file), now)
    }

    /**
     * Per-file ownership gate for the processing engine. A recording of video B must never block a
     * completed video A from being processed. Once the Camera lifecycle has been observed, only the
     * exact video latched to the current Camera generation is protected. Filesystem activity remains
     * a fallback only until a real Pilot Camera lifecycle has been seen in this process.
     */
    @Synchronized
    fun isRecordingFile(file: File, now: Long = System.currentTimeMillis()): Boolean {
        expireTransientState(now)
        val key = canonicalKey(file)
        if (broadcastLatchedRecording) {
            if (!matchesLatchedVideoKey(key)) return false
            // Divider's repeated fileChange is emitted only after Camera has stopped the previous
            // low-level recorder. That predecessor can remain the latch path for a few milliseconds
            // until the successor filename becomes visible, but it must already be processable.
            if (isRecentlyCompletedKey(key, now)) return false

            // A matching addFile is also an explicit per-fragment completion signal even while the
            // overall capture continuation latch remains active.
            val completedFragment = fragmentCompletionCandidateAt > 0L &&
                sameVideoKey(fragmentCompletionCandidateKey, key)
            return !completedFragment
        }

        // A fresh Camera start may precede the first observable CREATE by a fraction of a second.
        // Protect only a file whose own identity/timestamps associate it with that unresolved start;
        // do not globally block older completed videos.
        if (pilotStartGeneration > 0L && pilotStartHintAt > lastPilotCompletionAt) {
            observed.entries.firstOrNull { (observedKey, _) -> sameVideoKey(observedKey, key) }?.value?.let { candidate ->
                val firstSeenMatches = kotlin.math.abs(candidate.firstSeenAt - pilotStartHintAt) <=
                    PILOT_NEW_FILE_ASSOCIATION_MS
                val modifiedMatches = candidate.modifiedAt > 0L &&
                    kotlin.math.abs(candidate.modifiedAt - pilotStartHintAt) <= PILOT_FILE_TIME_TOLERANCE_MS
                if (firstSeenMatches || modifiedMatches) return true
            }
        }

        // After the first Camera lifecycle signal, generic filesystem activity must not resurrect
        // Recording after Camera completion or block unrelated completed videos during a new record.
        if (pilotStartGeneration > 0L) return false

        val eventActive = active.entries.any { (activeKey, value) ->
            sameVideoKey(activeKey, key) &&
                CaptureDisplayPolicy.isFallbackActivityFresh(now, value.lastActivityAt)
        }
        if (eventActive) return true

        return observed.entries.any { (observedKey, value) ->
            sameVideoKey(observedKey, key) && recentlyClosed[observedKey] == null &&
                value.lastGrowthAt > 0L &&
                CaptureDisplayPolicy.isFallbackActivityFresh(now, value.lastGrowthAt)
        }
    }

    private fun isRecentlyCompletedKey(videoKey: String, now: Long): Boolean =
        recentlyCompleted.entries.any { (completedKey, completedAt) ->
            now - completedAt <= RECENTLY_COMPLETED_RETENTION_MS && sameVideoKey(completedKey, videoKey)
        }

    private fun latchCandidate(candidate: ObservedFile) {
        val key = canonicalKey(candidate.file)
        broadcastLatchedRecording = true
        broadcastLatchedPath = key
        broadcastLatchedAt = pilotStartHintAt.takeIf { it > 0L } ?: candidateActivityAt(candidate)
        broadcastLatchedGeneration = pilotStartGeneration
        fragmentCompletionCandidateAt = 0L
        fragmentCompletionCandidateKey = ""
        clearCompletedAliases(key)
        clearClosedAliases(key)
        lastVideoName = candidate.file.name
        lastUpdatedAt = maxOf(lastUpdatedAt, pilotStartHintAt, candidateActivityAt(candidate))
    }

    private fun snapshotFromCurrentState(now: Long, scanCandidate: ObservedFile?): Snapshot {
        if (broadcastLatchedRecording && now - broadcastLatchedAt <= MAX_BROADCAST_LATCH_MS) {
            val name = File(broadcastLatchedPath).name.ifBlank { lastVideoName }
            return Snapshot(
                available = true,
                recording = true,
                captureRecording = true,
                videoName = name,
                updatedAt = lastUpdatedAt,
                source = "pilot-camera-broadcast",
                lifecycleGeneration = pilotStartGeneration
            )
        }

        // Once this process has observed Pilot Camera's lifecycle broadcast, filesystem activity is
        // no longer allowed to manufacture a second Recording state. This is essential after Stop:
        // MP4 finalization, copy verification, rename/delete and provider metadata activity can all
        // occur while the Camera is already Ready. A later genuine recording gets a new fileChange
        // generation and will be latched when its newly-created video appears.
        if (pilotStartGeneration > 0L) {
            val awaitingNewVideo = pilotStartHintAt > lastPilotCompletionAt
            return Snapshot(
                available = true,
                recording = false,
                captureRecording = false,
                videoName = lastVideoName,
                updatedAt = lastUpdatedAt,
                source = if (awaitingNewVideo) "pilot-camera-awaiting-video" else "pilot-camera-completed",
                lifecycleGeneration = pilotStartGeneration
            )
        }

        // No Camera lifecycle has been seen (for example the Main App was opened mid-recording).
        // Keep the filesystem fallback so an already-running recording can still be detected.
        val eventCandidate = active.values.maxByOrNull { it.lastActivityAt }
        if (eventCandidate != null && CaptureDisplayPolicy.isFallbackActivityFresh(now, eventCandidate.lastActivityAt)) {
            lastVideoName = eventCandidate.file.name
            lastUpdatedAt = maxOf(lastUpdatedAt, eventCandidate.lastActivityAt)
            return Snapshot(
                available = true,
                recording = true,
                captureRecording = true,
                videoName = eventCandidate.file.name,
                updatedAt = lastUpdatedAt,
                source = "mp4-file-events",
                lifecycleGeneration = 0L
            )
        }

        scanCandidate?.takeIf {
            CaptureDisplayPolicy.isFallbackActivityFresh(now, it.lastGrowthAt)
        }?.let { candidate ->
            lastVideoName = candidate.file.name
            lastUpdatedAt = maxOf(lastUpdatedAt, candidate.lastGrowthAt)
            return Snapshot(
                available = true,
                recording = true,
                captureRecording = true,
                videoName = candidate.file.name,
                updatedAt = lastUpdatedAt,
                source = "mp4-growth-scan",
                lifecycleGeneration = 0L
            )
        }

        return Snapshot(
            available = true,
            recording = false,
            captureRecording = false,
            videoName = lastVideoName,
            updatedAt = lastUpdatedAt,
            source = "mp4-growth-scan",
            lifecycleGeneration = 0L
        )
    }

    /** Clears raw filesystem observations without discarding Camera lifecycle state. */
    @Synchronized
    fun resetFileObservation() {
        active.clear()
        observed.clear()
        recentlyClosed.clear()
    }

    @Synchronized
    fun reset() {
        resetFileObservation()
        recentlyCompleted.clear()
        pilotStartHintAt = 0L
        pilotStartGeneration = 0L
        broadcastLatchedRecording = false
        broadcastLatchedPath = ""
        broadcastLatchedAt = 0L
        broadcastLatchedGeneration = 0L
        lastPilotCompletionAt = 0L
        fragmentCompletionCandidateAt = 0L
        fragmentCompletionCandidateKey = ""
        lastUpdatedAt = 0L
        lastVideoName = ""
    }

    internal fun isLikelyVideoPath(path: String): Boolean {
        if (path.isBlank()) return false
        val lower = path.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".sti") ||
            lower.contains("/dcim/videos/") || lower.contains("/videos/stitched/") ||
            lower.contains("/videos/unstitched/")
    }

    private fun recordingDirectories(configured: File): List<File> {
        val unique = LinkedHashMap<String, File>()
        fun add(file: File) {
            val key = canonicalKey(file)
            unique.putIfAbsent(key, file)
        }

        add(configured)

        val primary = Environment.getExternalStorageDirectory()
        add(File(primary, "DCIM/Videos/Stitched"))
        add(File(primary, "DCIM/Videos/Unstitched"))
        add(File(primary, "videos/stitched"))
        add(File(primary, "Videos/Stitched"))

        add(File("/sdcard/DCIM/Videos/Stitched"))
        add(File("/sdcard/DCIM/Videos/Unstitched"))
        add(File("/storage/emulated/0/DCIM/Videos/Stitched"))
        add(File("/storage/emulated/0/DCIM/Videos/Unstitched"))

        runCatching {
            File("/storage").listFiles().orEmpty().forEach { root ->
                if (root.isDirectory && !root.name.equals("emulated", ignoreCase = true) &&
                    !root.name.equals("self", ignoreCase = true)
                ) {
                    add(File(root, "DCIM/Videos/Stitched"))
                    add(File(root, "DCIM/Videos/Unstitched"))
                    add(File(root, "videos/stitched"))
                }
            }
        }

        return unique.values.toList()
    }

    private fun isFreshForStartHint(file: ObservedFile, startHintAt: Long, now: Long): Boolean {
        if (startHintAt <= 0L) return false
        val newlyObserved = kotlin.math.abs(file.firstSeenAt - startHintAt) <= PILOT_NEW_FILE_ASSOCIATION_MS
        if (!newlyObserved) return false
        val recentGrowth = file.lastGrowthAt > 0L &&
            now - file.lastGrowthAt <= PILOT_START_ASSOCIATION_WINDOW_MS
        val recentModification = file.modifiedAt > 0L &&
            kotlin.math.abs(file.modifiedAt - startHintAt) <= PILOT_FILE_TIME_TOLERANCE_MS
        return recentGrowth || recentModification
    }

    private fun candidateActivityAt(file: ObservedFile): Long =
        maxOf(file.lastGrowthAt, file.modifiedAt, file.firstSeenAt)

    private fun expireTransientState(now: Long) {
        active.entries.toList().forEach { (key, value) ->
            if (!value.file.exists() || now - value.lastActivityAt > FILE_EVENT_ACTIVE_TIMEOUT_MS) {
                active.remove(key, value)
            }
        }
        recentlyClosed.entries.toList().forEach { (key, closedAt) ->
            if (now - closedAt > RECENTLY_CLOSED_RETENTION_MS) recentlyClosed.remove(key, closedAt)
        }
        expireCompleted(now)
        if (broadcastLatchedRecording && fragmentCompletionCandidateAt > 0L &&
            now - fragmentCompletionCandidateAt >= FRAGMENT_CONTINUATION_GRACE_MS
        ) {
            // No next fragment appeared during the continuation window: this addFile was the final
            // fragment and therefore the actual recording stop.
            completeLatchedRecording(fragmentCompletionCandidateAt)
        }
        if (broadcastLatchedRecording && now - broadcastLatchedAt > MAX_BROADCAST_LATCH_MS) {
            completeLatchedRecording(now)
        }
    }


    private fun releaseLatchedFragmentForDividerRestart(now: Long) {
        val previousKey = broadcastLatchedPath
        if (previousKey.isBlank()) return

        recentlyCompleted[previousKey] = now
        recentlyClosed[previousKey] = now
        active.keys.toList().forEach { activeKey ->
            if (sameVideoKey(activeKey, previousKey)) active.remove(activeKey)
        }
        observed.entries.toList().forEach { (observedKey, current) ->
            if (sameVideoKey(observedKey, previousKey)) {
                recentlyCompleted[observedKey] = now
                recentlyClosed[observedKey] = now
                observed[observedKey] = current.copy(lastGrowthAt = 0L)
            }
        }

        // Do NOT set fragmentCompletionCandidateAt here. That timer means "possibly final addFile"
        // and can transition the Client to Ready after its grace window. Divider's onRecordStart is
        // positive proof that capture has already continued, so Recording must remain latched.
        fragmentCompletionCandidateAt = 0L
        fragmentCompletionCandidateKey = ""
    }

    private fun fragmentStorageEnabledForLatched(): Boolean {
        if (!broadcastLatchedRecording || broadcastLatchedPath.isBlank()) return false
        val directory = File(broadcastLatchedPath).parentFile ?: return false
        val snapshot = PilotFragmentStorageRegistry.snapshot()
        return PilotFragmentStorageRegistry.enabledForDirectory(
            directory = directory,
            snapshot = snapshot,
            ambiguousModeHint = PilotCameraModeRegistry.currentFreshMode()
        )
    }

    private fun maybeHandoffFragment(file: File, key: String, now: Long): Boolean {
        if (!broadcastLatchedRecording || broadcastLatchedPath.isBlank()) return false
        if (matchesLatchedVideoKey(key)) return false
        if (!isSameDirectoryAsLatched(file)) return false
        if (!sameVideoFamily(broadcastLatchedPath, key)) return false
        if (!isDistinctNextVideo(file, key)) return false
        // A genuine new Camera recording has its own fileChange generation. Never mistake that new
        // recording for a Fragment Storage rollover from the previous generation.
        if (pilotStartGeneration > broadcastLatchedGeneration) return false

        // The next distinct Camera video in the SAME start generation is the decisive fragment
        // boundary. Do not gate this on camera.getOptions or addFile: both have proven optional or
        // partial on Pilot One firmware. With Fragment Storage Off, Camera does not create another
        // video in the same generation, so there is nothing to hand off. If a new recording starts,
        // fileChange increments the generation and the guard above keeps the sessions separate.
        val previousKey = broadcastLatchedPath
        val previousObservation = observed.entries
            .firstOrNull { (observedKey, _) -> sameVideoKey(observedKey, previousKey) }
            ?.value
        val previousFile = previousObservation?.file ?: File(previousKey)
        PilotFragmentStorageRegistry.observeFragmentRollover(
            completedFile = previousFile,
            completedSizeBytes = previousFile.length().takeIf { it > 0L } ?: previousObservation?.size ?: 0L,
            firstSeenAt = previousObservation?.firstSeenAt ?: broadcastLatchedAt,
            completedAt = now
        )

        // Release the previous exact fragment to the processing engine but keep the same overall
        // Camera lifecycle generation. The next fragment becomes the only protected recording file.
        recentlyCompleted[previousKey] = now
        recentlyClosed[previousKey] = now
        active.keys.toList().forEach { activeKey ->
            if (sameVideoKey(activeKey, previousKey)) active.remove(activeKey)
        }
        observed.entries.toList().forEach { (observedKey, current) ->
            if (sameVideoKey(observedKey, previousKey)) {
                observed[observedKey] = current.copy(lastGrowthAt = 0L)
            }
        }

        broadcastLatchedPath = key
        broadcastLatchedAt = now
        fragmentCompletionCandidateAt = 0L
        fragmentCompletionCandidateKey = ""
        clearCompletedAliases(key)
        clearClosedAliases(key)
        lastVideoName = file.name
        lastUpdatedAt = now
        return true
    }

    private fun isDistinctNextVideo(file: File, key: String): Boolean {
        if (matchesLatchedVideoKey(key)) return false
        if (!file.exists() && file.name.isBlank()) return false
        return recordingStem(File(broadcastLatchedPath).name) != recordingStem(file.name)
    }

    private fun sameVideoFamily(firstKey: String, secondKey: String): Boolean {
        fun family(name: String): String {
            var value = name.lowercase()
            while (value.endsWith(".part") || value.endsWith(".tmp")) value = value.substringBeforeLast('.')
            return when {
                value.endsWith(".mp4") -> "mp4"
                value.endsWith(".sti") -> "sti"
                else -> ""
            }
        }
        val first = family(File(firstKey).name)
        val second = family(File(secondKey).name)
        return first.isNotBlank() && first == second
    }

    private fun isSameDirectoryAsLatched(file: File): Boolean {
        if (broadcastLatchedPath.isBlank()) return false
        val latchedParent = runCatching { File(broadcastLatchedPath).canonicalFile.parentFile }
            .getOrElse { File(broadcastLatchedPath).absoluteFile.parentFile }
        val candidateParent = runCatching { file.canonicalFile.parentFile }
            .getOrElse { file.absoluteFile.parentFile }
        return latchedParent != null && candidateParent != null && latchedParent == candidateParent
    }

    private fun completeLatchedRecording(completedAt: Long) {
        lastPilotCompletionAt = maxOf(lastPilotCompletionAt, completedAt)
        broadcastLatchedRecording = false
        broadcastLatchedPath = ""
        broadcastLatchedAt = 0L
        broadcastLatchedGeneration = 0L
        fragmentCompletionCandidateAt = 0L
        fragmentCompletionCandidateKey = ""
        lastUpdatedAt = maxOf(lastUpdatedAt, completedAt)
    }

    private fun clearCompletedAliases(videoKey: String) {
        recentlyCompleted.keys.toList().forEach { completedKey ->
            if (sameVideoKey(completedKey, videoKey)) recentlyCompleted.remove(completedKey)
        }
    }

    private fun clearClosedAliases(videoKey: String) {
        recentlyClosed.keys.toList().forEach { closedKey ->
            if (sameVideoKey(closedKey, videoKey)) recentlyClosed.remove(closedKey)
        }
    }

    private fun expireCompleted(now: Long) {
        recentlyCompleted.entries.toList().forEach { (key, completedAt) ->
            if (now - completedAt > RECENTLY_COMPLETED_RETENTION_MS) {
                recentlyCompleted.remove(key, completedAt)
            }
        }
    }

    private fun clearMissingFiles(presentKeys: Set<String>, scannedAtLeastOneDirectory: Boolean) {
        if (!scannedAtLeastOneDirectory) return
        observed.keys.removeAll { it !in presentKeys }
        active.keys.removeAll { it !in presentKeys }
        recentlyClosed.keys.removeAll { key -> key !in presentKeys }
        // recentlyCompleted intentionally survives source deletion/move for its bounded retention;
        // processing may ask about completion after Camera has already removed an alias path.
    }

    private fun sameVideoKey(first: String, second: String): Boolean {
        if (first.isBlank() || second.isBlank()) return false
        if (first == second) return true
        val firstName = File(first).name
        val secondName = File(second).name
        if (firstName.equals(secondName, ignoreCase = true)) return true
        // Some camera/storage paths expose an in-progress `.part`/`.tmp` name while the gallery
        // completion broadcast names the finalized `.mp4`/`.sti`. Match their stable recording
        // stem so a harmless rename cannot leave Recording latched forever.
        return recordingStem(firstName).equals(recordingStem(secondName), ignoreCase = true)
    }

    private fun recordingStem(name: String): String {
        var value = name.trim().lowercase()
        while (value.endsWith(".part") || value.endsWith(".tmp")) {
            value = value.substringBeforeLast('.')
        }
        if (value.endsWith(".mp4") || value.endsWith(".sti")) {
            value = value.substringBeforeLast('.')
        }
        return value
    }

    private fun matchesLatchedVideoKey(videoKey: String): Boolean {
        if (!broadcastLatchedRecording || broadcastLatchedPath.isBlank()) return false
        return sameVideoKey(broadcastLatchedPath, videoKey)
    }

    private fun isVideoCandidate(file: File): Boolean {
        val lower = file.name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".sti") ||
            lower.endsWith(".tmp") || lower.endsWith(".part")
    }

    private fun canonicalKey(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    private const val FILE_EVENT_ACTIVE_TIMEOUT_MS = 15_000L
    private const val SCAN_GROWTH_ACTIVE_TIMEOUT_MS = 15_000L
    private const val FIRST_SEEN_RECENT_MS = 8_000L
    private const val RECENTLY_CLOSED_RETENTION_MS = 45_000L
    private const val RECENTLY_COMPLETED_RETENTION_MS = 5L * 60L * 1000L
    private const val PILOT_START_ASSOCIATION_WINDOW_MS = 12_000L
    private const val PILOT_NEW_FILE_ASSOCIATION_MS = 4_000L
    private const val PILOT_FILE_TIME_TOLERANCE_MS = 8_000L
    private const val FRAGMENT_CONTINUATION_GRACE_MS = 5_000L
    private const val MAX_BROADCAST_LATCH_MS = 12L * 60L * 60L * 1000L
}
