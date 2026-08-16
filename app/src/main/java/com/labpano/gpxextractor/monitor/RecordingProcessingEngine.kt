package com.labpano.gpxextractor.monitor

import android.content.Context
import android.net.Uri
import android.os.FileObserver
import android.os.SystemClock
import com.labpano.gpxextractor.AppConfig
import com.labpano.gpxextractor.data.ProcessedRecordingStore
import com.labpano.gpxextractor.data.ProcessingStatus
import com.labpano.gpxextractor.gpx.GpsPointDensifier
import com.labpano.gpxextractor.gpx.GpxValidator
import com.labpano.gpxextractor.gpx.GpxWriter
import com.labpano.gpxextractor.mp4.CammParser
import com.labpano.gpxextractor.mp4.Mp4Exception
import com.labpano.gpxextractor.mp4.Mp4ReadinessChecker
import com.labpano.gpxextractor.output.DatedOutputLayout
import com.labpano.gpxextractor.output.OutputMover
import com.labpano.gpxextractor.report.GlobalOutputReportStore
import com.labpano.gpxextractor.ui.MainActivity
import com.labpano.gpxextractor.util.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-consumer recording engine with bounded retry, permanent-error quarantine and a durable
 * post-transfer journal. Moved files are never recopied merely because report writing failed.
 */
class RecordingProcessingEngine(
    private val context: Context,
    private val recordingDirectory: File,
    private val statusPublisher: (String) -> Unit
) : AutoCloseable {
    private enum class Phase { WAITING, QUEUED, PROCESSING }

    private data class Candidate(
        var size: Long,
        var modifiedAt: Long,
        var unchangedSinceElapsed: Long,
        var phase: Phase = Phase.WAITING
    )

    private data class SequenceRelease(val releasedElapsed: Long)

    private val running = AtomicBoolean(false)
    private val candidates = ConcurrentHashMap<String, Candidate>()
    private val recordingSequence = RecordingSequenceTracker()
    private val sequenceReleasedFiles = ConcurrentHashMap<String, SequenceRelease>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "LabpanoFileScanner").apply { isDaemon = true }
    }
    private val consumer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LabpanoRecordingConsumer").apply { isDaemon = true }
    }

    private val processedStore = ProcessedRecordingStore(context)
    private val readinessChecker = Mp4ReadinessChecker()
    private val cammParser = CammParser()
    private val gpxValidator = GpxValidator()
    private val pointDensifier = GpsPointDensifier()
    private val gpxWriter = GpxWriter()
    private val outputMover = OutputMover(context)
    private val reportStore = GlobalOutputReportStore(context)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            if (!recordingDirectory.exists() && !recordingDirectory.mkdirs()) {
                throw IllegalStateException("Cannot create or access recording directory: ${recordingDirectory.absolutePath}")
            }
            // The OUTPUT root owns exactly the three cumulative report files. Creating them here
            // makes report availability independent of whether a recording has completed yet.
            reportStore.ensureReportFiles()
            processedStore.prune()
            recoverMovedTransactions()
            val initialMp4s = currentMp4Files()
            recordingSequence.resetBaseline(initialMp4s.map { it.absolutePath })
            AppLog.info("Recording sequence baseline captured: ${initialMp4s.size} MP4 file(s)")
            scanDirectory()
            scheduler.scheduleWithFixedDelay(
                { safeScan() },
                SCAN_INTERVAL_MS,
                SCAN_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
            scheduler.scheduleWithFixedDelay(
                { safeRecoverMovedTransactions() },
                RECOVERY_INTERVAL_MS,
                RECOVERY_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        } catch (error: Throwable) {
            running.set(false)
            close()
            throw error
        }
    }

    /** Immediate safe rescan requested by the Pilot Camera broadcast bridge or UI/service restore. */
    fun requestScan() {
        if (!running.get()) return
        runCatching { scheduler.execute { safeScan() } }
    }

    /** Camera addFile is a stronger completion hint than passive filesystem stability. */
    fun requestCompletedScan() {
        if (!running.get()) return
        runCatching { scheduler.execute { safeScan() } }
        runCatching {
            scheduler.schedule(
                { safeScan() },
                CAMERA_COMPLETED_RESCAN_DELAY_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    /**
     * Final overall Camera stop. Release the last MP4 in the current filesystem sequence because no
     * successor fragment will be created for it, then take a fresh baseline for the next recording.
     */
    fun finishCurrentRecordingSequence() {
        if (!running.get()) return
        runCatching {
            scheduler.execute {
                val nowElapsed = SystemClock.elapsedRealtime()
                val files = currentMp4Files()
                val result = recordingSequence.finishRecording(files.map { it.absolutePath })
                applySequenceResult(result, nowElapsed, "recording stopped")
                safeScan()
                runCatching {
                    scheduler.schedule(
                        { safeScan() },
                        CAMERA_COMPLETED_RESCAN_DELAY_MS,
                        TimeUnit.MILLISECONDS
                    )
                }
            }
        }
    }

    /**
     * Fast, non-blocking FileObserver/Camera hint. Only a finalized `.mp4` CREATE/MOVED_TO enters
     * the A -> B -> C sequence; temporary aliases merely trigger a directory refresh.
     */
    fun signal(file: File, event: Int? = null) {
        if (!running.get()) return
        val stem = writerStem(file.name)
        val finalizedMp4 = file.extension.equals("mp4", true)
        if (stem == null && !finalizedMp4) return
        runCatching {
            scheduler.execute {
                val nowElapsed = SystemClock.elapsedRealtime()
                // Prefer actual MP4 CREATE/MOVED_TO event order. The periodic directory snapshot
                // below is only a fallback for storage paths where FileObserver drops an event.
                if (finalizedMp4) {
                    val relevantEvent = event?.and(FileObserver.ALL_EVENTS)
                    if (relevantEvent == FileObserver.CREATE || relevantEvent == FileObserver.MOVED_TO) {
                        applySequenceResult(
                            recordingSequence.observeNewPath(file.absolutePath),
                            nowElapsed,
                            "successor MP4 created"
                        )
                    }
                }
                // The moving policy follows the visible MP4 sequence only. A becomes releasable when
                // a distinct finalized MP4 B appears in the Recording folder; Fragment Storage API
                // values and Camera divider broadcasts are not required for this decision.
                updateRecordingSequence(currentMp4Files(), nowElapsed)
                if (finalizedMp4) observe(file, nowElapsed)
            }
        }
    }

    fun stop() = close()

    override fun close() {
        running.set(false)
        scheduler.shutdownNow()
        consumer.shutdownNow()
        runCatching { scheduler.awaitTermination(2, TimeUnit.SECONDS) }
        runCatching { consumer.awaitTermination(5, TimeUnit.SECONDS) }
        candidates.clear()
        sequenceReleasedFiles.clear()
        recordingSequence.resetBaseline(emptyList())
        runCatching { processedStore.close() }
    }

    private fun safeScan() {
        if (!running.get()) return
        try {
            scanDirectory()
        } catch (error: Throwable) {
            AppLog.error("Background recording scan failed", error)
        }
    }


    private fun safeRecoverMovedTransactions() {
        if (!running.get()) return
        try {
            recoverMovedTransactions()
        } catch (error: Throwable) {
            AppLog.error("Background transfer recovery failed", error)
        }
    }

    private fun scanDirectory() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val present = HashSet<String>()

        val mp4Files = currentMp4Files()
        updateRecordingSequence(mp4Files, nowElapsed)

        // Refresh Camera status for the Client/status path, but processing ownership is now decided
        // primarily by the explicit MP4 sequence: current file is protected; every predecessor that
        // gained a successor is released even while the overall Camera recording stays active.
        CameraRecordingStatusRegistry.snapshot(recordingDirectory)
        mp4Files.forEach { file ->
            present += file.absolutePath
            observe(file, nowElapsed)
        }
        candidates.keys.removeAll { it !in present }
        sequenceReleasedFiles.keys.removeAll { it !in present }
    }

    private fun observe(
        file: File,
        nowElapsed: Long
    ) {
        if (!running.get() || !file.isFile) return
        val path = file.absolutePath
        val size = file.length()
        val modifiedAt = file.lastModified()

        if (processedStore.hasFinalResult(path, size, modifiedAt)) {
            candidates.remove(path)
            return
        }
        // If a verified move/adoption has already been journaled, recovery owns this source.
        if (processedStore.hasPendingTransaction(path, size, modifiedAt)) {
            candidates.remove(path)
            return
        }
        if (processedStore.nextRetryAt(path, size, modifiedAt) > System.currentTimeMillis()) return

        val candidate = candidates[path]
        if (candidate == null) {
            candidates[path] = Candidate(size, modifiedAt, nowElapsed)
            return
        }
        if (candidate.phase != Phase.WAITING) return

        if (candidate.size != size || candidate.modifiedAt != modifiedAt) {
            candidate.size = size
            candidate.modifiedAt = modifiedAt
            candidate.unchangedSinceElapsed = nowElapsed
            return
        }

        if (recordingSequence.isActive(path)) {
            // A/B/C sequence rule: the newest MP4 is the current writer and is never processed.
            candidate.unchangedSinceElapsed = nowElapsed
            return
        }
        if (CameraRecordingStatusRegistry.isRecordingFile(file) && !wasSequenceReleased(file)) {
            // Keep Camera's per-file ownership as a secondary safety guard. A predecessor explicitly
            // released by the appearance of its successor is allowed through even if Camera's
            // broadcast registry still points at the older file.
            candidate.unchangedSinceElapsed = nowElapsed
            return
        }

        if (size <= 0L) {
            if (nowElapsed - candidate.unchangedSinceElapsed >= ZERO_LENGTH_STALE_MS) {
                candidate.phase = Phase.QUEUED
                consumer.execute { cleanupStaleZeroLengthFile(file, modifiedAt) }
            }
            return
        }

        val sequenceRelease = sequenceReleasedFiles[path]
        val stablePeriod = if (CameraRecordingStatusRegistry.wasRecentlyCompleted(file) ||
            sequenceRelease != null
        ) {
            CAMERA_COMPLETED_STABLE_PERIOD_MS
        } else {
            STABLE_PERIOD_MS
        }
        val stableSince = maxOf(candidate.unchangedSinceElapsed, sequenceRelease?.releasedElapsed ?: 0L)
        if (nowElapsed - stableSince >= stablePeriod) {
            candidate.phase = Phase.QUEUED
            consumer.execute { consume(file, size, modifiedAt) }
        }
    }

    private fun currentMp4Files(): List<File> = recordingDirectory
        .listFiles { file -> file.isFile && file.extension.equals("mp4", true) }
        .orEmpty()
        .sortedWith(compareBy<File>({ it.lastModified() }, { it.name.lowercase() }))

    private fun updateRecordingSequence(files: List<File>, nowElapsed: Long) {
        val result = recordingSequence.observeSnapshot(files.map { it.absolutePath })
        applySequenceResult(result, nowElapsed, "successor MP4 created")
    }

    private fun applySequenceResult(
        result: RecordingSequenceTracker.SnapshotResult,
        nowElapsed: Long,
        reason: String
    ) {
        result.newlyDiscovered.forEach { path ->
            AppLog.info("Recording sequence discovered MP4: ${File(path).name}")
        }
        result.newlyReleased.forEach { path ->
            val file = File(path)
            sequenceReleasedFiles[path] = SequenceRelease(nowElapsed)
            val size = file.takeIf { it.isFile }?.length() ?: 0L
            val modifiedAt = file.takeIf { it.isFile }?.lastModified() ?: 0L
            if (file.isFile) {
                val candidate = candidates[path]
                if (candidate == null) {
                    candidates[path] = Candidate(size, modifiedAt, nowElapsed)
                } else if (candidate.phase == Phase.WAITING) {
                    candidate.size = size
                    candidate.modifiedAt = modifiedAt
                    candidate.unchangedSinceElapsed = nowElapsed
                }
            }
            AppLog.info("Recording sequence released MP4: ${file.name}; reason=$reason")
        }
        if (result.newlyReleased.isNotEmpty() && running.get()) {
            runCatching {
                scheduler.schedule(
                    { safeScan() },
                    CAMERA_COMPLETED_RESCAN_DELAY_MS,
                    TimeUnit.MILLISECONDS
                )
            }
        }
    }

    private fun wasSequenceReleased(file: File): Boolean = sequenceReleasedFiles.containsKey(file.absolutePath)

    /** Stable identity across `video.mp4`, `video.mp4.part` and `video.mp4.tmp`. */
    private fun writerStem(name: String): String? {
        var value = name.trim().lowercase()
        if (value.isBlank()) return null
        while (value.endsWith(".part") || value.endsWith(".tmp")) {
            value = value.substringBeforeLast('.')
        }
        if (!value.endsWith(".mp4")) return null
        value = value.substringBeforeLast('.')
        if (value.isBlank() || value.startsWith(".") || value.contains('/')) return null
        return value
    }

    private fun consume(file: File, expectedSize: Long, expectedModifiedAt: Long) {
        val candidate = candidates[file.absolutePath] ?: return
        if (!running.get()) return resetWaiting(file)
        candidate.phase = Phase.PROCESSING

        try {
            ensureRunning()
            if (!matchesSnapshot(file, expectedSize, expectedModifiedAt)) return resetWaiting(file)
            CameraRecordingStatusRegistry.snapshot(recordingDirectory)
            if (recordingSequence.isActive(file.absolutePath)) return resetWaiting(file)
            if (CameraRecordingStatusRegistry.isRecordingFile(file) && !wasSequenceReleased(file)) {
                return resetWaiting(file)
            }

            // A sequence predecessor is already proven complete by the creation of its successor and
            // the mandatory settle/snapshot checks. Keep the generic MP4 structural preflight for
            // unrelated files, but do not let that heuristic re-block an A/B/C predecessor forever.
            if (!wasSequenceReleased(file)) {
                when (val readiness = readinessChecker.check(file)) {
                    is Mp4ReadinessChecker.Result.Incomplete -> {
                        scheduleRetry(file, expectedSize, expectedModifiedAt, "MP4 is not finalized: ${readiness.reason}")
                        return
                    }
                    Mp4ReadinessChecker.Result.Ready -> Unit
                }
            }
            if (!matchesSnapshot(file, expectedSize, expectedModifiedAt)) return resetWaiting(file)
            processReadyRecording(file, expectedSize, expectedModifiedAt)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            resetWaiting(file)
        } catch (error: Throwable) {
            AppLog.error("Unexpected consumer failure for ${file.absolutePath}", error)
            handleProcessingError(file, expectedSize, expectedModifiedAt, error)
        }
    }

    private fun processReadyRecording(file: File, fileSize: Long, modifiedAt: Long) {
            if (processedStore.hasFinalResult(file.absolutePath, fileSize, modifiedAt) ||
                processedStore.hasPendingTransaction(file.absolutePath, fileSize, modifiedAt)
            ) {
                candidates.remove(file.absolutePath)
                return
            }

            statusPublisher(RecordingMonitorService.STATUS_PROCESSING_PREFIX + file.name)
            processedStore.beginProcessing(
                file.absolutePath,
                fileSize,
                modifiedAt,
                "Processing started"
            )

            var temporaryGpx: File? = null
            try {
                ensureRunning()
                val parseResult = cammParser.parseDetailed(file)
                val extractedPoints = parseResult.points
                if (!matchesSnapshot(file, fileSize, modifiedAt)) {
                    processedStore.deleteTransientResult(file.absolutePath, fileSize, modifiedAt)
                    resetWaiting(file)
                    return
                }
                if (parseResult.timing.overlapMillis != null && parseResult.timing.overlapMillis <= 0L) {
                    quarantinePermanentError(
                        file,
                        fileSize,
                        modifiedAt,
                        "Generated GPX does not overlap the MP4 video timeline"
                    )
                    return
                }

                val validation = gpxValidator.validate(extractedPoints)
                if (!validation.valid) {
                    quarantinePermanentError(
                        file,
                        fileSize,
                        modifiedAt,
                        "Invalid GPS data: ${validation.errors.joinToString("; ")}"
                    )
                    return
                }

                val gapWarning = if (validation.gapCount > 0) {
                    val details = validation.gaps.joinToString(", ") {
                        "afterPoint=${it.afterPointIndex},durationMs=${it.durationMillis}"
                    }
                    "GPS gap warning; gaps=${validation.gapCount}; gapDetails=[$details]"
                } else null

                val dense = pointDensifier.densify(extractedPoints)
                temporaryGpx = File(file.parentFile, ".${file.nameWithoutExtension}.gpx.part")
                if (temporaryGpx.exists() && !temporaryGpx.delete()) {
                    throw IllegalStateException("Cannot remove stale temporary GPX ${temporaryGpx.name}")
                }
                gpxWriter.write(dense.points, temporaryGpx, file.nameWithoutExtension)
                if (!temporaryGpx.isFile || temporaryGpx.length() == 0L) {
                    throw IllegalStateException("GPX writer produced no output")
                }
                if (!matchesSnapshot(file, fileSize, modifiedAt)) {
                    temporaryGpx.delete()
                    processedStore.deleteTransientResult(file.absolutePath, fileSize, modifiedAt)
                    resetWaiting(file)
                    return
                }

                val finalLocalGpx = finalizeLocalGpx(file, requireNotNull(temporaryGpx))
                temporaryGpx = finalLocalGpx
                val status = if (gapWarning == null) ProcessingStatus.GOOD else ProcessingStatus.FAILED
                val layout = DatedOutputLayout.now()
                val treeUri = resolveOutputTreeUri()
                val directory = if (treeUri == null) resolveOutputDirectory() else null
                val gpxSizeBeforeMove = finalLocalGpx.length()
                val transactionId = UUID.randomUUID().toString()
                fun createEntry(result: OutputMover.Result): ProcessedRecordingStore.TransferJournalEntry {
                    val message = buildString {
                        if (gapWarning != null) append(gapWarning).append("; ") else append("Approved and moved; ")
                        append("extractedPoints=").append(extractedPoints.size)
                        append("; interpolatedPoints=").append(dense.interpolatedPointCount)
                        append("; outputPoints=").append(dense.points.size)
                        append("; interpolationIntervalMs=").append(dense.effectiveIntervalMillis)
                        append("; interpolationLimited=").append(dense.interpolationLimited)
                        append("; timestampAnchor=").append(parseResult.timing.anchorSource)
                        append("; timestampShiftMs=").append(parseResult.timing.appliedTimestampShiftMillis)
                        append("; videoStartUtc=").append(parseResult.timing.videoStartMillis?.let(::utcTimestamp) ?: "unknown")
                        append("; videoEndUtc=").append(parseResult.timing.videoEndMillis?.let(::utcTimestamp) ?: "unknown")
                        append("; gpxStartUtc=").append(utcTimestamp(parseResult.timing.gpxStartMillis))
                        append("; gpxEndUtc=").append(utcTimestamp(parseResult.timing.gpxEndMillis))
                        append("; overlapMs=").append(parseResult.timing.overlapMillis ?: -1L)
                        append("; video=").append(result.videoName)
                        append("; gpx=").append(result.gpxName)
                        append("; destination=").append(result.destination)
                        append("; cleanupPending=").append(result.sourceCleanupPending)
                        append("; transactionId=").append(transactionId)
                    }
                    return journalEntry(
                        transactionId, file, fileSize, modifiedAt, status, message,
                        layout, directory, treeUri, result, gpxSizeBeforeMove
                    )
                }
                val moved = outputMover.movePair(
                    video = file,
                    gpx = finalLocalGpx,
                    outputDirectory = directory,
                    outputTreeUri = treeUri,
                    subfolderName = layout.mediaSubfolder(status),
                    beforeSourceCleanup = { prepared ->
                        // Persist verified destination details before either source file is deleted.
                        processedStore.recordMovedTransaction(createEntry(prepared))
                    }
                )
                val entry = createEntry(moved)
                processedStore.recordMovedTransaction(entry)
                commitMovedTransaction(entry, recovering = false)
                candidates.remove(file.absolutePath)
                sequenceReleasedFiles.remove(file.absolutePath)
                if (status == ProcessingStatus.GOOD) {
                    statusPublisher(RecordingMonitorService.STATUS_MOVED_PREFIX + moved.videoName + " → " + moved.destination)
                } else {
                    statusPublisher(RecordingMonitorService.STATUS_FAILED_PREFIX + file.name + " (moved to output; logged as FAILED)")
                }
            } catch (error: Throwable) {
                if (temporaryGpx?.name?.endsWith(".part") == true) temporaryGpx.delete()
                if (isTransientFinalizationFailure(error)) {
                    if (wasSequenceReleased(file)) {
                        scheduleSequenceFinalizationRetry(
                            file, fileSize, modifiedAt,
                            error.message ?: "MP4 finalization incomplete"
                        )
                    } else {
                        scheduleRetry(file, fileSize, modifiedAt, error.message ?: "MP4 finalization incomplete")
                    }
                } else {
                    handleProcessingError(file, fileSize, modifiedAt, error)
                }
            }
    }

    private fun finalizeLocalGpx(video: File, temporary: File): File {
        val finalFile = File(video.parentFile, "${video.nameWithoutExtension}.gpx")
        val backup = File(video.parentFile, ".${video.nameWithoutExtension}.gpx.backup")
        if (backup.exists()) backup.delete()
        if (finalFile.exists() && !finalFile.renameTo(backup)) {
            throw IllegalStateException("Cannot preserve existing ${finalFile.name}")
        }
        if (!temporary.renameTo(finalFile)) {
            if (backup.exists()) backup.renameTo(finalFile)
            throw IllegalStateException("Cannot finalize local GPX ${finalFile.name}")
        }
        backup.delete()
        return finalFile
    }

    private fun quarantinePermanentError(
        file: File,
        fileSize: Long,
        modifiedAt: Long,
        reason: String
    ) {
        val layout = DatedOutputLayout.now()
        val treeUri = resolveOutputTreeUri()
        val directory = if (treeUri == null) resolveOutputDirectory() else null
        val localGpx = File(file.parentFile, "${file.nameWithoutExtension}.gpx")
        val gpxSizeBeforeMove = localGpx.takeIf { it.isFile }?.length() ?: 0L
        val transactionId = UUID.randomUUID().toString()
        fun createEntry(result: OutputMover.Result): ProcessedRecordingStore.TransferJournalEntry {
            val message = "$reason; video=${result.videoName}; gpx=${result.gpxName.orEmpty()}; " +
                "destination=${result.destination}; cleanupPending=${result.sourceCleanupPending}; transactionId=$transactionId"
            return journalEntry(
                transactionId, file, fileSize, modifiedAt, ProcessingStatus.ERROR, message,
                layout, directory, treeUri, result, gpxSizeBeforeMove
            )
        }
        val persistBeforeCleanup: (OutputMover.Result) -> Unit = { prepared ->
            processedStore.recordMovedTransaction(createEntry(prepared))
        }
        val moved = if (localGpx.isFile && localGpx.length() > 0L) {
            outputMover.movePair(
                file, localGpx, directory, treeUri, layout.mediaSubfolder(ProcessingStatus.ERROR),
                persistBeforeCleanup
            )
        } else {
            outputMover.moveSingle(
                file, directory, treeUri, layout.mediaSubfolder(ProcessingStatus.ERROR),
                persistBeforeCleanup
            )
        }
        val entry = createEntry(moved)
        processedStore.recordMovedTransaction(entry)
        commitMovedTransaction(entry, recovering = false)
        candidates.remove(file.absolutePath)
        sequenceReleasedFiles.remove(file.absolutePath)
        statusPublisher(RecordingMonitorService.STATUS_FAILED_PREFIX + file.name + " (moved to output; logged as ERROR)")
    }

    private fun journalEntry(
        transactionId: String,
        source: File,
        sourceSize: Long,
        sourceModifiedAt: Long,
        status: ProcessingStatus,
        message: String,
        layout: DatedOutputLayout,
        outputDirectory: File?,
        outputTreeUri: Uri?,
        moved: OutputMover.Result,
        gpxSizeBytes: Long
    ): ProcessedRecordingStore.TransferJournalEntry = ProcessedRecordingStore.TransferJournalEntry(
        transactionId = transactionId,
        sourcePath = source.absolutePath,
        sourceSize = sourceSize,
        sourceModifiedAt = sourceModifiedAt,
        status = status,
        message = message,
        outputDate = layout.date,
        outputDirectory = outputDirectory?.absolutePath,
        outputTreeUri = outputTreeUri?.toString(),
        destination = moved.destination,
        videoName = moved.videoName,
        videoPath = moved.videoPath,
        gpxName = moved.gpxName,
        gpxPath = moved.gpxPath,
        gpxSizeBytes = gpxSizeBytes,
        cleanupPending = moved.sourceCleanupPending,
        state = ProcessedRecordingStore.STATE_MOVED,
        createdAt = System.currentTimeMillis()
    )

    private fun recoverMovedTransactions() {
        processedStore.pendingTransactions().forEach { entry ->
            try {
                commitMovedTransaction(entry, recovering = true)
                AppLog.info("Recovered transfer transaction ${entry.transactionId}")
            } catch (error: Throwable) {
                AppLog.error("Transfer recovery remains pending for ${entry.transactionId}", error)
            }
        }
    }

    private fun commitMovedTransaction(
        entry: ProcessedRecordingStore.TransferJournalEntry,
        recovering: Boolean
    ) {
        val duplicateMarker = entry.transactionId.takeIf { recovering }
        val storedReportDestination = GlobalOutputReportStore.Destination(
            directory = entry.outputDirectory?.takeIf { it.isNotBlank() }?.let(::File),
            treeUri = entry.outputTreeUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        )
        val reportDestination = if (storedReportDestination.directory == null && storedReportDestination.treeUri == null) {
            reportStore.currentDestination()
        } else {
            storedReportDestination
        }

        // Reports live only at the OUTPUT root. Date subfolders contain media/GPX only.
        // Ensure all cumulative TXT files exist at the *actual* destination before appending. This
        // also covers changing OUTPUT while monitoring is already running.
        reportStore.ensureReportFiles(reportDestination)
        reportStore.appendOnce(
            status = entry.status,
            sourcePath = entry.sourcePath,
            message = entry.message,
            transactionId = duplicateMarker,
            destination = reportDestination
        )

        if (entry.status == ProcessingStatus.GOOD || entry.status == ProcessingStatus.FAILED) {
            val gpxName = requireNotNull(entry.gpxName)
            val videoPath = entry.videoPath?.takeIf { it.isNotBlank() }
                ?: File(entry.destination, entry.videoName).absolutePath
            val gpxPath = entry.gpxPath?.takeIf { it.isNotBlank() }
                ?: File(entry.destination, gpxName).absolutePath
            val gpxSize = if (gpxPath.startsWith("content://", ignoreCase = true)) {
                entry.gpxSizeBytes
            } else {
                File(gpxPath).takeIf { it.isFile }?.length() ?: entry.gpxSizeBytes
            }
            if (gpxSize <= 0L) throw IllegalStateException("Moved GPX is unavailable for queue: $gpxPath")
            processedStore.enqueuePendingGpx(
                ProcessedRecordingStore.PendingGpxEntry(
                    id = entry.transactionId,
                    status = entry.status.name,
                    completedAt = utcTimestamp(entry.createdAt),
                    videoName = entry.videoName,
                    videoPath = videoPath,
                    gpxName = gpxName,
                    gpxPath = gpxPath,
                    gpxSizeBytes = gpxSize
                )
            )
        }

        processedStore.saveFinal(
            entry.sourcePath,
            entry.sourceSize,
            entry.sourceModifiedAt,
            entry.status,
            entry.message
        )

        val sourceGpx = entry.gpxName?.let {
            File(File(entry.sourcePath).parentFile, "${File(entry.sourcePath).nameWithoutExtension}.gpx").absolutePath
        }
        val cleanupComplete = !entry.cleanupPending || outputMover.retrySourceCleanup(entry.sourcePath, sourceGpx)
        if (cleanupComplete) {
            processedStore.markTransactionCommitted(entry.transactionId)
        } else {
            AppLog.warn("Source cleanup remains pending for transaction ${entry.transactionId}")
        }
    }

    private fun cleanupStaleZeroLengthFile(file: File, modifiedAt: Long) {
        if (!running.get() || !file.isFile || file.length() != 0L || file.lastModified() != modifiedAt) {
            resetWaiting(file)
            return
        }
        CameraRecordingStatusRegistry.snapshot(recordingDirectory)
        if (recordingSequence.isActive(file.absolutePath) ||
            (CameraRecordingStatusRegistry.isRecordingFile(file) && !wasSequenceReleased(file))
        ) {
            resetWaiting(file)
            return
        }

        val path = file.absolutePath
        try {
            if (!file.delete()) throw IllegalStateException("Cannot remove stale zero-byte MP4 placeholder")
            val transactionId = UUID.randomUUID().toString()
            val message = "Removed stale zero-byte MP4 placeholder; no video data was available; transactionId=$transactionId"
            val destination = reportStore.currentDestination()
            reportStore.ensureReportFiles(destination)
            reportStore.appendOnce(
                ProcessingStatus.ERROR,
                path,
                message,
                transactionId = null,
                destination = destination
            )
            processedStore.saveFinal(path, 0L, modifiedAt, ProcessingStatus.ERROR, message)
            candidates.remove(path)
            statusPublisher(RecordingMonitorService.STATUS_FAILED_PREFIX + file.name + " (removed stale 0-byte file; logged as ERROR)")
        } catch (error: Throwable) {
            AppLog.error("Cannot clean stale zero-byte MP4 ${file.absolutePath}", error)
            resetWaiting(file)
        }
    }

    private fun handleProcessingError(file: File, size: Long, modifiedAt: Long, error: Throwable) {
        if (!file.isFile) {
            candidates.remove(file.absolutePath)
            return
        }
        val message = error.message ?: error.javaClass.simpleName
        scheduleRetry(file, size, modifiedAt, message)
    }

    private fun scheduleSequenceFinalizationRetry(
        file: File,
        size: Long,
        modifiedAt: Long,
        message: String
    ) {
        if (!file.isFile) {
            candidates.remove(file.absolutePath)
            return
        }
        AppLog.info("Released fragment still finalizing; short retry for ${file.name}: $message")
        processedStore.deleteTransientResult(file.absolutePath, size, modifiedAt)
        resetWaiting(file)
        if (running.get()) {
            runCatching {
                scheduler.schedule(
                    { safeScan() },
                    CAMERA_COMPLETED_RESCAN_DELAY_MS,
                    TimeUnit.MILLISECONDS
                )
            }
        }
    }

    private fun scheduleRetry(file: File, size: Long, modifiedAt: Long, message: String) {
        if (!file.isFile) {
            candidates.remove(file.absolutePath)
            return
        }
        val decision = processedStore.recordRetry(file.absolutePath, size, modifiedAt, message)
        if (decision.quarantine) {
            try {
                quarantinePermanentError(
                    file,
                    size,
                    modifiedAt,
                    "Permanent processing error after ${decision.attemptCount} attempts: $message"
                )
                return
            } catch (quarantineError: Throwable) {
                AppLog.error("Cannot move permanent error to quarantine", quarantineError)
            }
        }
        val delaySeconds = ((decision.nextRetryAt - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L)
        statusPublisher(
            RecordingMonitorService.STATUS_FAILED_PREFIX + file.name +
                " (attempt ${decision.attemptCount}; retry in ${delaySeconds}s)"
        )
        resetWaiting(file)
    }

    private fun resetWaiting(file: File) {
        if (!running.get() || !file.isFile) {
            candidates.remove(file.absolutePath)
            return
        }
        candidates[file.absolutePath] = Candidate(
            size = file.length(),
            modifiedAt = file.lastModified(),
            unchangedSinceElapsed = SystemClock.elapsedRealtime(),
            phase = Phase.WAITING
        )
    }

    private fun matchesSnapshot(file: File, size: Long, modifiedAt: Long): Boolean =
        file.isFile && file.length() == size && file.lastModified() == modifiedAt

    private fun isTransientFinalizationFailure(error: Throwable): Boolean {
        if (error !is Mp4Exception && error !is IllegalStateException) return false
        val value = (error.message ?: "").lowercase()
        return value.contains("moov") || value.contains("mdat") ||
            (value.contains("invalid") && value.contains("box")) ||
            value.contains("truncated") || value.contains("outside file") ||
            value.contains("changed while") || value.contains("changed during")
    }

    private fun ensureRunning() {
        if (!running.get() || Thread.currentThread().isInterrupted) throw InterruptedException("Processing cancelled")
    }

    private fun resolveOutputDirectory(): File {
        val preferences = context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val configured = preferences.getString(MainActivity.KEY_OUTPUT_DIRECTORY, null)
        return configured?.takeIf { it.isNotBlank() && !it.startsWith("content://") }?.let(::File)
            ?: AppConfig.defaultOutputDirectory
    }

    private fun resolveOutputTreeUri(): Uri? {
        val preferences = context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
        return preferences.getString(MainActivity.KEY_OUTPUT_TREE_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
    }

    private fun utcTimestamp(epochMillis: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMillis))

    companion object {
        private const val SCAN_INTERVAL_MS = 5_000L
        private const val STABLE_PERIOD_MS = 30_000L
        // Camera's addFile broadcast means the SDK has registered the completed media. Keep a short
        // settling guard for final metadata writes, but do not impose the generic 30-second delay.
        private const val CAMERA_COMPLETED_STABLE_PERIOD_MS = 2_000L
        private const val CAMERA_COMPLETED_RESCAN_DELAY_MS = 2_100L
        private const val ZERO_LENGTH_STALE_MS = 120_000L
        private const val RECOVERY_INTERVAL_MS = 30_000L
    }
}
