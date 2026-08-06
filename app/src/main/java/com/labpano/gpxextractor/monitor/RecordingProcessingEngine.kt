package com.labpano.gpxextractor.monitor

import android.content.Context
import android.net.Uri
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
import com.labpano.gpxextractor.report.DatedOutputReportWriter
import com.labpano.gpxextractor.report.ReportWriter
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

    private val running = AtomicBoolean(false)
    private val candidates = ConcurrentHashMap<String, Candidate>()
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
    private val reportWriter = ReportWriter(recordingDirectory)
    private val datedReportWriter = DatedOutputReportWriter(context)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            if (!recordingDirectory.exists() && !recordingDirectory.mkdirs()) {
                throw IllegalStateException("Cannot create or access recording directory: ${recordingDirectory.absolutePath}")
            }
            reportWriter.ensureReportFiles()
            processedStore.prune()
            recoverMovedTransactions()
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

    /** Fast, non-blocking hint called by FileObserver. */
    fun signal(file: File) {
        if (!running.get() || !file.extension.equals("mp4", true)) return
        runCatching { scheduler.execute { observe(file, SystemClock.elapsedRealtime()) } }
    }

    fun stop() = close()

    override fun close() {
        running.set(false)
        scheduler.shutdownNow()
        consumer.shutdownNow()
        runCatching { scheduler.awaitTermination(2, TimeUnit.SECONDS) }
        runCatching { consumer.awaitTermination(5, TimeUnit.SECONDS) }
        candidates.clear()
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
        recordingDirectory.listFiles { file -> file.isFile && file.extension.equals("mp4", true) }
            ?.forEach { file ->
                present += file.absolutePath
                observe(file, nowElapsed)
            }
        candidates.keys.removeAll { it !in present }
    }

    private fun observe(file: File, nowElapsed: Long) {
        if (!running.get() || !file.isFile) return
        val path = file.absolutePath
        val size = file.length()
        val modifiedAt = file.lastModified()

        if (processedStore.hasFinalResult(path, size, modifiedAt)) {
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

        if (nowElapsed - candidate.unchangedSinceElapsed >= STABLE_PERIOD_MS) {
            candidate.phase = Phase.QUEUED
            consumer.execute { consume(file, size, modifiedAt) }
        }
    }

    private fun consume(file: File, expectedSize: Long, expectedModifiedAt: Long) {
        val candidate = candidates[file.absolutePath] ?: return
        if (!running.get()) return resetWaiting(file)
        candidate.phase = Phase.PROCESSING

        try {
            ensureRunning()
            if (!matchesSnapshot(file, expectedSize, expectedModifiedAt)) return resetWaiting(file)

            when (val readiness = readinessChecker.check(file)) {
                is Mp4ReadinessChecker.Result.Incomplete -> {
                    scheduleRetry(file, expectedSize, expectedModifiedAt, "MP4 is not finalized: ${readiness.reason}")
                    return
                }
                Mp4ReadinessChecker.Result.Ready -> Unit
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
            if (processedStore.hasFinalResult(file.absolutePath, fileSize, modifiedAt)) {
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
                commitMovedTransaction(entry)
                candidates.remove(file.absolutePath)
                if (status == ProcessingStatus.GOOD) {
                    statusPublisher(RecordingMonitorService.STATUS_MOVED_PREFIX + moved.videoName + " → " + moved.destination)
                } else {
                    statusPublisher(RecordingMonitorService.STATUS_FAILED_PREFIX + file.name + " (moved to Failed)")
                }
            } catch (error: Throwable) {
                if (temporaryGpx?.name?.endsWith(".part") == true) temporaryGpx.delete()
                if (isTransientFinalizationFailure(error)) {
                    scheduleRetry(file, fileSize, modifiedAt, error.message ?: "MP4 finalization incomplete")
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
        commitMovedTransaction(entry)
        candidates.remove(file.absolutePath)
        statusPublisher(RecordingMonitorService.STATUS_FAILED_PREFIX + file.name + " (moved to Error)")
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
                commitMovedTransaction(entry)
                AppLog.info("Recovered transfer transaction ${entry.transactionId}")
            } catch (error: Throwable) {
                AppLog.error("Transfer recovery remains pending for ${entry.transactionId}", error)
            }
        }
    }

    private fun commitMovedTransaction(entry: ProcessedRecordingStore.TransferJournalEntry) {
        val layout = DatedOutputLayout(entry.outputDate)
        val directory = entry.outputDirectory?.let(::File)
        val treeUri = entry.outputTreeUri?.let(Uri::parse)

        reportWriter.appendOnce(
            entry.status,
            entry.sourcePath,
            entry.message,
            entry.transactionId
        )
        datedReportWriter.appendOnce(
            entry.status,
            entry.sourcePath,
            entry.message,
            entry.transactionId,
            layout,
            directory,
            treeUri
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

    private fun handleProcessingError(file: File, size: Long, modifiedAt: Long, error: Throwable) {
        if (!file.isFile) {
            candidates.remove(file.absolutePath)
            return
        }
        val message = error.message ?: error.javaClass.simpleName
        scheduleRetry(file, size, modifiedAt, message)
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
        private const val RECOVERY_INTERVAL_MS = 30_000L
    }
}
