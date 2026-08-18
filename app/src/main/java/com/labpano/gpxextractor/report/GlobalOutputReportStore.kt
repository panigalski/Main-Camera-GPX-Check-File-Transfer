package com.labpano.gpxextractor.report

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.labpano.gpxextractor.AppConfig
import com.labpano.gpxextractor.data.ProcessingStatus
import com.labpano.gpxextractor.output.DatedOutputLayout
import com.labpano.gpxextractor.ui.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale

/**
 * Owns both cumulative root reports and the daily reports stored beside each day's classified media.
 *
 * OUTPUT/
 *   GOOD.TXT
 *   FAILED.TXT
 *   ERROR.TXT
 *   dd-MM-yyyy/
 *     GOOD/dd-MM-yyyy_GOOD.txt
 *     FAILED/dd-MM-yyyy_FAILED.txt
 *     ERROR/dd-MM-yyyy_ERROR.txt
 *
 * Every committed recording is appended to the matching root cumulative report and the matching
 * daily report. There are no per-video TXT reports.
 */
class GlobalOutputReportStore(private val context: Context) {
    data class Destination(val directory: File?, val treeUri: Uri?)
    data class DeleteResult(val deleted: Boolean, val statusCode: Int, val message: String)
    data class ReportFileState(
        val name: String,
        val exists: Boolean,
        val readable: Boolean,
        val writable: Boolean,
        val sizeBytes: Long
    )
    data class HealthSnapshot(
        val destination: String,
        val destinationType: String,
        val available: Boolean,
        val writable: Boolean,
        val files: List<ReportFileState>,
        val lastSuccessAt: Long,
        val lastFailureAt: Long,
        val lastOperation: String,
        val lastError: String
    )

    fun currentDestination(): Destination {
        val preferences = context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val tree = preferences.getString(MainActivity.KEY_OUTPUT_TREE_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
        val directory = preferences.getString(MainActivity.KEY_OUTPUT_DIRECTORY, null)
            ?.takeIf { it.isNotBlank() && !it.startsWith("content://") }
            ?.let(::File)
            ?: AppConfig.defaultOutputDirectory
        return Destination(directory = if (tree == null) directory else null, treeUri = tree)
    }

    /**
     * Verifies the selected OUTPUT root and ensures GOOD.TXT / FAILED.TXT / ERROR.TXT exist.
     * When upgrading from 0.5.45, any newly-created root report is backfilled from the existing
     * daily reports so the cumulative history is preserved. Daily reports are still kept in their
     * date/status folders.
     */
    fun ensureReportFiles(destination: Destination = currentDestination()) = synchronized(ReportFileAccess.lock) {
        try {
            val migrationKey = destinationMigrationKey(destination)
            val preferences = context.getSharedPreferences(REPORT_PREFERENCES, Context.MODE_PRIVATE)
            val needsDualLayoutMigration = preferences.getString(KEY_DUAL_LAYOUT_MIGRATED_DESTINATION, null) != migrationKey
            when {
                destination.directory != null -> {
                    ensureLocalDirectory(destination.directory)
                    val created = ensureLocalRootReports(destination.directory)
                    migrateLegacyLocalStatusFolderReports(destination.directory)
                    created.forEach { status -> backfillLocalRootFromDaily(destination.directory, status) }
                    if (needsDualLayoutMigration) reconcileLocalDailyFromRoot(destination.directory)
                }
                destination.treeUri != null -> {
                    verifyTreeRoot(destination.treeUri)
                    val created = ensureTreeRootReports(destination.treeUri)
                    migrateLegacyTreeStatusFolderReports(destination.treeUri)
                    created.forEach { status -> backfillTreeRootFromDaily(destination.treeUri, status) }
                    if (needsDualLayoutMigration) reconcileTreeDailyFromRoot(destination.treeUri)
                }
                else -> throw IOException("Output report destination is not configured")
            }
            if (needsDualLayoutMigration) {
                preferences.edit().putString(KEY_DUAL_LAYOUT_MIGRATED_DESTINATION, migrationKey).apply()
            }
            ReportHealthRegistry.success(context, "ensure-report-root")
        } catch (error: Throwable) {
            ReportHealthRegistry.failure(context, "ensure-report-root", error)
            throw error
        }
    }

    fun ensureDailyReportFiles(
        outputDate: String,
        destination: Destination = currentDestination()
    ) = synchronized(ReportFileAccess.lock) {
        requireValidDate(outputDate)
        try {
            when {
                destination.directory != null -> ensureLocalDailyReports(destination.directory, outputDate)
                destination.treeUri != null -> ensureTreeDailyReports(destination.treeUri, outputDate)
                else -> throw IOException("Output report destination is not configured")
            }
            ReportHealthRegistry.success(context, "ensure-daily-reports-$outputDate")
        } catch (error: Throwable) {
            ReportHealthRegistry.failure(context, "ensure-daily-reports-$outputDate", error)
            throw error
        }
    }

    fun appendOnce(
        status: ProcessingStatus,
        sourcePath: String,
        message: String,
        transactionId: String?,
        destination: Destination,
        outputDate: String = DatedOutputLayout.forRecording(File(sourcePath).name).date
    ) = synchronized(ReportFileAccess.lock) {
        requireReportStatus(status)
        requireValidDate(outputDate)
        try {
            val marker = transactionId?.let { "transactionId=$it" }
            val line = "${utcTimestamp()}\t${sanitize(sourcePath)}\t${sanitize(message)}\n"
            when {
                destination.directory != null -> appendLocal(destination.directory, outputDate, status, line, marker)
                destination.treeUri != null -> appendTree(destination.treeUri, outputDate, status, line, marker)
                else -> throw IOException("Output report destination is not configured")
            }
            ReportHealthRegistry.success(context, "append-${status.name.lowercase(Locale.US)}-$outputDate")
        } catch (error: Throwable) {
            ReportHealthRegistry.failure(context, "append-${status.name.lowercase(Locale.US)}-$outputDate", error)
            throw error
        }
    }

    fun readTail(status: ProcessingStatus, maxLines: Int, destination: Destination = currentDestination()): List<String> =
        synchronized(ReportFileAccess.lock) {
            if (maxLines <= 0) return@synchronized emptyList()
            requireReportStatus(status)
            try {
                val lines = when {
                    destination.directory != null -> readTailLocal(destination.directory, status, maxLines)
                    destination.treeUri != null -> readTailTree(destination.treeUri, status, maxLines)
                    else -> emptyList()
                }
                ReportHealthRegistry.recoveredSuccess(context, "read-${status.name.lowercase(Locale.US)}")
                lines
            } catch (error: Throwable) {
                ReportHealthRegistry.failure(context, "read-${status.name.lowercase(Locale.US)}", error)
                throw error
            }
        }

    fun healthSnapshot(destination: Destination = currentDestination()): HealthSnapshot =
        synchronized(ReportFileAccess.lock) {
            val diagnostic = ReportHealthRegistry.snapshot(context)
            try {
                when {
                    destination.directory != null -> localHealth(destination.directory, diagnostic)
                    destination.treeUri != null -> treeHealth(destination.treeUri, diagnostic)
                    else -> HealthSnapshot(
                        destination = "", destinationType = "none", available = false, writable = false,
                        files = emptyList(), lastSuccessAt = diagnostic.lastSuccessAt,
                        lastFailureAt = diagnostic.lastFailureAt, lastOperation = diagnostic.lastOperation,
                        lastError = diagnostic.lastError.ifBlank { "Output report destination is not configured" }
                    )
                }
            } catch (error: Throwable) {
                ReportHealthRegistry.failure(context, "report-health", error)
                val failed = ReportHealthRegistry.snapshot(context)
                HealthSnapshot(
                    destination = destination.treeUri?.toString() ?: destination.directory?.absolutePath.orEmpty(),
                    destinationType = if (destination.treeUri != null) "saf" else "filesystem",
                    available = false, writable = false, files = emptyList(),
                    lastSuccessAt = failed.lastSuccessAt, lastFailureAt = failed.lastFailureAt,
                    lastOperation = failed.lastOperation, lastError = failed.lastError
                )
            }
        }

    fun deleteExactLine(
        status: ProcessingStatus,
        expectedLine: String,
        destination: Destination = currentDestination()
    ): DeleteResult = synchronized(ReportFileAccess.lock) {
        requireReportStatus(status)
        when {
            destination.directory != null -> deleteLocalLine(destination.directory, status, expectedLine)
            destination.treeUri != null -> deleteTreeLine(destination.treeUri, status, expectedLine)
            else -> DeleteResult(false, 404, "Output report destination is not configured")
        }
    }

    private fun appendLocal(root: File, date: String, status: ProcessingStatus, line: String, marker: String?) {
        ensureLocalRootReports(root)
        ensureLocalDailyReports(root, date)
        val dailyReport = localReportFile(root, date, status)
        val rootReport = localRootReportFile(root, status)
        if (marker == null || !containsMarkerLocal(dailyReport, marker)) appendLocalLine(dailyReport, line)
        if (marker == null || !containsMarkerLocal(rootReport, marker)) appendLocalLine(rootReport, line)
    }

    private fun appendLocalLine(report: File, line: String) {
        FileOutputStream(report, true).use { output ->
            output.write(line.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun appendTree(treeUri: Uri, date: String, status: ProcessingStatus, line: String, marker: String?) {
        ensureTreeRootReports(treeUri)
        ensureTreeDailyReports(treeUri, date)
        val resolver = context.contentResolver
        val dailyReport = findTreeDailyReport(resolver, treeUri, date, status)
            ?: throw IOException("Cannot find ${reportName(date, status)} after creation")
        val rootReport = findTreeRootReport(resolver, treeUri, status)
            ?: throw IOException("Cannot find ${rootReportName(status)} after creation")
        if (marker == null || !containsMarkerTree(resolver, dailyReport, marker)) appendTreeLine(resolver, dailyReport, line)
        if (marker == null || !containsMarkerTree(resolver, rootReport, marker)) appendTreeLine(resolver, rootReport, line)
    }

    private fun appendTreeLine(resolver: ContentResolver, report: Uri, line: String) {
        val appended = runCatching {
            resolver.openOutputStream(report, "wa")?.use { output ->
                output.write(line.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: throw IOException("Cannot append daily report")
        }.isSuccess
        if (!appended) {
            resolver.openFileDescriptor(report, "rw")?.use { descriptor ->
                val output = FileOutputStream(descriptor.fileDescriptor)
                output.channel.position(descriptor.statSize.coerceAtLeast(0L))
                output.write(line.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            } ?: throw IOException("Cannot open daily report for append")
        }
    }

    private fun readTailLocal(root: File, status: ProcessingStatus, maxLines: Int): List<String> {
        val report = localRootReportFile(root, status)
        if (!report.isFile || !report.canRead()) return emptyList()
        val ring = ArrayDeque<String>(maxLines.coerceAtLeast(1))
        report.bufferedReader(Charsets.UTF_8).useLines { sequence ->
            sequence.forEach { line ->
                if (line.isNotBlank()) {
                    if (ring.size >= maxLines) ring.removeFirst()
                    ring.addLast(line)
                }
            }
        }
        return ring.toList()
    }

    private fun readTailTree(treeUri: Uri, status: ProcessingStatus, maxLines: Int): List<String> {
        val resolver = context.contentResolver
        val report = findTreeRootReport(resolver, treeUri, status) ?: return emptyList()
        val ring = ArrayDeque<String>(maxLines.coerceAtLeast(1))
        resolver.openInputStream(report)?.bufferedReader(Charsets.UTF_8)?.useLines { sequence ->
            sequence.forEach { line ->
                if (line.isNotBlank()) {
                    if (ring.size >= maxLines) ring.removeFirst()
                    ring.addLast(line)
                }
            }
        }
        return ring.toList()
    }

    private fun deleteLocalLine(root: File, status: ProcessingStatus, expectedLine: String): DeleteResult {
        localDateDirectories(root).asReversed().forEach { daily ->
            val dailyReport = localReportFile(root, daily.name, status)
            if (!dailyReport.isFile) return@forEach
            val dailyResult = rewriteLocalReportWithoutLine(dailyReport, expectedLine)
            if (dailyResult.statusCode == 404) return@forEach
            if (!dailyResult.deleted) return dailyResult

            val rootReport = localRootReportFile(root, status)
            val rootResult = if (rootReport.isFile) rewriteLocalReportWithoutLine(rootReport, expectedLine)
                else DeleteResult(false, 404, "Root report entry no longer exists")
            if (rootResult.deleted || rootResult.statusCode == 404) return DeleteResult(true, 200, "Entry deleted")

            // Keep the two report levels consistent if the root rewrite failed.
            runCatching { appendLocalLine(dailyReport, expectedLine + "\n") }
            return rootResult
        }
        return DeleteResult(false, 404, "Entry no longer exists")
    }

    private fun rewriteLocalReportWithoutLine(report: File, expectedLine: String): DeleteResult {
        if (!report.canRead() || !report.canWrite()) return DeleteResult(false, 403, "${report.name} is not writable")
        val temporary = File(report.parentFile, ".${report.name}.rewrite-${System.nanoTime()}.tmp")
        val backup = File(report.parentFile, ".${report.name}.rewrite-backup")
        return try {
            val removed = rewriteWithoutLine(report, temporary, expectedLine)
            if (!removed) {
                temporary.delete()
                DeleteResult(false, 404, "Entry no longer exists")
            } else {
                java.io.RandomAccessFile(temporary, "rw").use { it.fd.sync() }
                val expectedSize = temporary.length()
                if (backup.exists()) backup.delete()
                if (!report.renameTo(backup)) throw IOException("Cannot preserve original report")
                if (!temporary.renameTo(report)) {
                    backup.renameTo(report)
                    throw IOException("Cannot replace report")
                }
                if (!report.isFile || !report.canRead() || report.length() != expectedSize) {
                    report.delete(); backup.renameTo(report)
                    throw IOException("Rewritten report verification failed")
                }
                backup.delete()
                DeleteResult(true, 200, "Entry deleted")
            }
        } catch (error: Throwable) {
            temporary.delete()
            if (!report.exists() && backup.exists()) backup.renameTo(report)
            DeleteResult(false, 500, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun deleteTreeLine(treeUri: Uri, status: ProcessingStatus, expectedLine: String): DeleteResult {
        val resolver = context.contentResolver
        listTreeDateDirectories(resolver, treeUri).asReversed().forEach { dateEntry ->
            val dailyReport = findTreeDailyReport(resolver, treeUri, dateEntry.name, status) ?: return@forEach
            val dailyResult = rewriteTreeReportWithoutLine(resolver, dailyReport, expectedLine, reportName(dateEntry.name, status))
            if (dailyResult.statusCode == 404) return@forEach
            if (!dailyResult.deleted) return dailyResult

            val rootReport = findTreeRootReport(resolver, treeUri, status)
            val rootResult = if (rootReport != null) {
                rewriteTreeReportWithoutLine(resolver, rootReport, expectedLine, rootReportName(status))
            } else {
                DeleteResult(false, 404, "Root report entry no longer exists")
            }
            if (rootResult.deleted || rootResult.statusCode == 404) return DeleteResult(true, 200, "Entry deleted")

            // Restore the daily copy if the cumulative root copy could not be updated.
            runCatching { appendTreeLine(resolver, dailyReport, expectedLine + "\n") }
            return rootResult
        }
        return DeleteResult(false, 404, "Entry no longer exists")
    }

    private fun rewriteTreeReportWithoutLine(
        resolver: ContentResolver,
        report: Uri,
        expectedLine: String,
        displayName: String
    ): DeleteResult {
        val backup = File(context.cacheDir, "report-backup-${System.nanoTime()}.txt")
        val rewritten = File(context.cacheDir, "report-rewrite-${System.nanoTime()}.txt")
        return try {
            resolver.openInputStream(report)?.use { input ->
                FileOutputStream(backup).use { output -> input.copyTo(output); output.fd.sync() }
            } ?: throw IOException("Cannot read $displayName")
            if (!rewriteWithoutLine(backup, rewritten, expectedLine)) {
                DeleteResult(false, 404, "Entry no longer exists")
            } else {
                FileOutputStream(rewritten, true).use { it.fd.sync() }
                writeWholeTreeDocument(resolver, report, rewritten)
                DeleteResult(true, 200, "Entry deleted")
            }
        } catch (error: Throwable) {
            if (backup.isFile) runCatching { writeWholeTreeDocument(resolver, report, backup) }
            DeleteResult(false, 500, error.message ?: error.javaClass.simpleName)
        } finally {
            backup.delete(); rewritten.delete()
        }
    }

    private fun ensureLocalRootReports(root: File): Set<ProcessingStatus> {
        ensureLocalDirectory(root)
        val created = linkedSetOf<ProcessingStatus>()
        statuses.forEach { status ->
            val report = localRootReportFile(root, status)
            if (!report.exists()) {
                if (!report.createNewFile()) throw IOException("Cannot create root report file: ${report.absolutePath}")
                created += status
            }
            if (!report.isFile || !report.canRead() || !report.canWrite()) {
                throw IOException("Root report is not readable/writable: ${report.absolutePath}")
            }
        }
        return created
    }

    private fun ensureTreeRootReports(treeUri: Uri): Set<ProcessingStatus> {
        val resolver = context.contentResolver
        val root = rootTreeDocument(treeUri)
        val created = linkedSetOf<ProcessingStatus>()
        statuses.forEach { status ->
            val name = rootReportName(status)
            if (findTreeChild(resolver, treeUri, root, name) == null) {
                DocumentsContract.createDocument(resolver, root, "text/plain", name)
                    ?: throw IOException("Cannot create $name")
                created += status
            }
        }
        return created
    }

    private fun reconcileLocalDailyFromRoot(root: File) {
        statuses.forEach { status ->
            val rootReport = localRootReportFile(root, status)
            if (!rootReport.isFile || !rootReport.canRead()) return@forEach
            rootReport.useLines(Charsets.UTF_8) { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val date = dateForLegacyLine(line)
                    ensureLocalDailyReports(root, date)
                    val dailyReport = localReportFile(root, date, status)
                    if (!containsExactLineLocal(dailyReport, line)) appendLocalLine(dailyReport, line + "\n")
                }
            }
        }
    }

    private fun reconcileTreeDailyFromRoot(treeUri: Uri) {
        val resolver = context.contentResolver
        statuses.forEach { status ->
            val rootReport = findTreeRootReport(resolver, treeUri, status) ?: return@forEach
            val lines = resolver.openInputStream(rootReport)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                reader.lineSequence().filter { it.isNotBlank() }.toList()
            } ?: emptyList()
            lines.forEach { line ->
                val date = dateForLegacyLine(line)
                ensureTreeDailyReports(treeUri, date)
                val dailyReport = findTreeDailyReport(resolver, treeUri, date, status)
                    ?: throw IOException("Cannot locate daily report while reconciling ${rootReportName(status)}")
                if (!containsExactLineTree(resolver, dailyReport, line)) appendTreeLine(resolver, dailyReport, line + "\n")
            }
        }
    }

    private fun destinationMigrationKey(destination: Destination): String = when {
        destination.treeUri != null -> "saf:${destination.treeUri}"
        destination.directory != null -> "file:${runCatching { destination.directory.canonicalPath }.getOrElse { destination.directory.absolutePath }}"
        else -> "none"
    }

    private fun backfillLocalRootFromDaily(root: File, status: ProcessingStatus) {
        val rootReport = localRootReportFile(root, status)
        val existing = rootReport.takeIf { it.isFile }?.readLines(Charsets.UTF_8)?.toHashSet() ?: hashSetOf()
        localDateDirectories(root).forEach { daily ->
            val report = localReportFile(root, daily.name, status)
            if (!report.isFile || !report.canRead()) return@forEach
            report.useLines(Charsets.UTF_8) { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    if (existing.add(line)) appendLocalLine(rootReport, line + "\n")
                }
            }
        }
    }

    private fun backfillTreeRootFromDaily(treeUri: Uri, status: ProcessingStatus) {
        val resolver = context.contentResolver
        val rootReport = findTreeRootReport(resolver, treeUri, status)
            ?: throw IOException("Cannot locate ${rootReportName(status)} for backfill")
        val existing = resolver.openInputStream(rootReport)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            reader.lineSequence().filter { it.isNotBlank() }.toHashSet()
        } ?: hashSetOf()
        listTreeDateDirectories(resolver, treeUri).forEach { dateEntry ->
            val dailyReport = findTreeDailyReport(resolver, treeUri, dateEntry.name, status) ?: return@forEach
            resolver.openInputStream(dailyReport)?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    if (existing.add(line)) appendTreeLine(resolver, rootReport, line + "\n")
                }
            }
        }
    }

    private fun localRootReportFile(root: File, status: ProcessingStatus): File = File(root, rootReportName(status))

    private fun findTreeRootReport(resolver: ContentResolver, treeUri: Uri, status: ProcessingStatus): Uri? =
        findTreeChild(resolver, treeUri, rootTreeDocument(treeUri), rootReportName(status))

    private fun ensureLocalDailyReports(root: File, date: String) {
        ensureLocalDirectory(root)
        statuses.forEach { status ->
            val folder = File(File(root, date), status.name)
            if (!folder.exists() && !folder.mkdirs()) throw IOException("Cannot create report folder: ${folder.absolutePath}")
            if (!folder.isDirectory) throw IOException("Report path is not a directory: ${folder.absolutePath}")
            val report = File(folder, reportName(date, status))
            if (!report.exists() && !report.createNewFile()) throw IOException("Cannot create report file: ${report.absolutePath}")
            if (!report.isFile || !report.canRead() || !report.canWrite()) throw IOException("Report is not readable/writable: ${report.absolutePath}")
        }
    }

    private fun ensureTreeDailyReports(treeUri: Uri, date: String) {
        val resolver = context.contentResolver
        val root = rootTreeDocument(treeUri)
        val dateDir = ensureTreeDirectory(resolver, treeUri, root, date)
        statuses.forEach { status ->
            val statusDir = ensureTreeDirectory(resolver, treeUri, dateDir, status.name)
            val name = reportName(date, status)
            if (findTreeChild(resolver, treeUri, statusDir, name) == null) {
                DocumentsContract.createDocument(resolver, statusDir, "text/plain", name)
                    ?: throw IOException("Cannot create $name")
            }
        }
    }

    private fun localReportFile(root: File, date: String, status: ProcessingStatus): File =
        File(File(File(root, date), status.name), reportName(date, status))

    private fun findTreeDailyReport(resolver: ContentResolver, treeUri: Uri, date: String, status: ProcessingStatus): Uri? {
        val root = rootTreeDocument(treeUri)
        val dateDir = findTreeChild(resolver, treeUri, root, date) ?: return null
        val statusDir = findTreeChild(resolver, treeUri, dateDir, status.name) ?: return null
        return findTreeChild(resolver, treeUri, statusDir, reportName(date, status))
    }

    private fun migrateLegacyLocalStatusFolderReports(root: File) {
        statuses.forEach { status ->
            val candidates = listOf(File(File(root, status.name), "${status.name}.TXT"))
            candidates.distinctBy { it.absolutePath }.forEach legacyLoop@ { legacy ->
                if (!legacy.isFile || !legacy.canRead()) return@legacyLoop
                val lines = legacy.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
                lines.forEach { line -> migrateLegacyLocalLine(root, status, line) }
                if (!legacy.delete()) throw IOException("Cannot remove migrated legacy report: ${legacy.absolutePath}")
                legacy.parentFile?.takeIf { it != root && it.isDirectory && it.list()?.isEmpty() == true }?.delete()
            }
        }
    }

    private fun migrateLegacyLocalLine(root: File, status: ProcessingStatus, line: String) {
        val date = dateForLegacyLine(line)
        ensureLocalDailyReports(root, date)
        val dailyReport = localReportFile(root, date, status)
        if (!containsExactLineLocal(dailyReport, line)) appendLocalLine(dailyReport, line + "\n")
        val rootReport = localRootReportFile(root, status)
        if (!containsExactLineLocal(rootReport, line)) appendLocalLine(rootReport, line + "\n")
    }

    private fun migrateLegacyTreeStatusFolderReports(treeUri: Uri) {
        val resolver = context.contentResolver
        val root = rootTreeDocument(treeUri)
        statuses.forEach { status ->
            val candidates = mutableListOf<Uri>()
            val oldStatusDir = findTreeChild(resolver, treeUri, root, status.name)
            if (oldStatusDir != null && isTreeDirectory(resolver, oldStatusDir)) {
                findTreeChild(resolver, treeUri, oldStatusDir, "${status.name}.TXT")?.let(candidates::add)
            }
            candidates.distinct().forEach { legacy ->
                val lines = resolver.openInputStream(legacy)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    reader.lineSequence().filter { it.isNotBlank() }.toList()
                } ?: emptyList()
                lines.forEach { line ->
                    val date = dateForLegacyLine(line)
                    ensureTreeDailyReports(treeUri, date)
                    val dailyReport = findTreeDailyReport(resolver, treeUri, date, status)
                        ?: throw IOException("Cannot locate migrated daily report")
                    if (!containsExactLineTree(resolver, dailyReport, line)) appendTreeLine(resolver, dailyReport, line + "\n")
                    val rootReport = findTreeRootReport(resolver, treeUri, status)
                        ?: throw IOException("Cannot locate root cumulative report")
                    if (!containsExactLineTree(resolver, rootReport, line)) appendTreeLine(resolver, rootReport, line + "\n")
                }
                if (!DocumentsContract.deleteDocument(resolver, legacy)) throw IOException("Cannot remove migrated legacy report")
            }
        }
    }

    private fun dateForLegacyLine(line: String): String {
        val parts = line.split('\t', limit = 3)
        val sourcePath = parts.getOrElse(1) { "" }
        val fallbackMillis = parts.getOrElse(0) { "" }.let { timestamp ->
            runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    isLenient = false
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(timestamp)?.time
            }.getOrNull()
        } ?: System.currentTimeMillis()
        return DatedOutputLayout.forRecording(File(sourcePath).name, fallbackMillis).date
    }

    private fun localDateDirectories(root: File): List<File> =
        root.listFiles()?.filter { it.isDirectory && DATE_REGEX.matches(it.name) }
            ?.sortedBy { dateSortKey(it.name) } ?: emptyList()

    private data class TreeEntry(val uri: Uri, val name: String, val mimeType: String?, val size: Long)

    private fun listTreeDateDirectories(resolver: ContentResolver, treeUri: Uri): List<TreeEntry> =
        listTreeChildren(resolver, treeUri, rootTreeDocument(treeUri))
            .filter { it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR && DATE_REGEX.matches(it.name) }
            .sortedBy { dateSortKey(it.name) }

    private fun listTreeChildren(resolver: ContentResolver, treeUri: Uri, parent: Uri): List<TreeEntry> {
        val parentId = DocumentsContract.getDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val result = mutableListOf<TreeEntry>()
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ), null, null, null
            )
            if (cursor != null) {
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    if (idCol < 0 || nameCol < 0) continue
                    result += TreeEntry(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol)),
                        name = cursor.getString(nameCol),
                        mimeType = if (mimeCol >= 0 && !cursor.isNull(mimeCol)) cursor.getString(mimeCol) else null,
                        size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol).coerceAtLeast(0L) else 0L
                    )
                }
            }
        } finally { cursor?.close() }
        return result
    }

    private fun ensureTreeDirectory(resolver: ContentResolver, treeUri: Uri, parent: Uri, name: String): Uri {
        findTreeChild(resolver, treeUri, parent, name)?.let { existing ->
            if (!isTreeDirectory(resolver, existing)) throw IOException("$name exists but is not a directory")
            return existing
        }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Cannot create directory $name")
    }

    private fun findTreeChild(resolver: ContentResolver, treeUri: Uri, parent: Uri, name: String): Uri? =
        listTreeChildren(resolver, treeUri, parent).firstOrNull { it.name.equals(name, ignoreCase = true) }?.uri

    private fun isTreeDirectory(resolver: ContentResolver, uri: Uri): Boolean {
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            val column = cursor?.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE) ?: -1
            cursor != null && cursor.moveToFirst() && column >= 0 && cursor.getString(column) == DocumentsContract.Document.MIME_TYPE_DIR
        } finally { cursor?.close() }
    }

    private fun verifyTreeRoot(treeUri: Uri) {
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == treeUri }
        if (permission == null || !permission.isReadPermission || !permission.isWritePermission) {
            throw IOException("Selected OUTPUT tree does not have persisted read/write permission")
        }
        // Force a provider round-trip now so monitoring cannot start with a stale/dead URI.
        listTreeChildren(context.contentResolver, treeUri, rootTreeDocument(treeUri))
    }

    private fun localHealth(root: File, diagnostic: ReportHealthRegistry.Snapshot): HealthSnapshot {
        val files = recentLocalReportStates(root)
        return HealthSnapshot(
            destination = root.absolutePath,
            destinationType = "filesystem",
            available = root.isDirectory && root.canRead(),
            writable = root.isDirectory && root.canWrite(),
            files = files,
            lastSuccessAt = diagnostic.lastSuccessAt,
            lastFailureAt = diagnostic.lastFailureAt,
            lastOperation = diagnostic.lastOperation,
            lastError = diagnostic.lastError
        )
    }

    private fun recentLocalReportStates(root: File): List<ReportFileState> {
        val rootStates = statuses.map { status ->
            val file = localRootReportFile(root, status)
            ReportFileState(
                name = file.name, exists = file.isFile,
                readable = file.isFile && file.canRead(), writable = file.isFile && file.canWrite(),
                sizeBytes = file.takeIf { it.isFile }?.length() ?: 0L
            )
        }
        val latest = localDateDirectories(root).lastOrNull() ?: return rootStates
        val dailyStates = statuses.map { status ->
            val file = localReportFile(root, latest.name, status)
            ReportFileState(
                name = "${latest.name}/${status.name}/${file.name}", exists = file.isFile,
                readable = file.isFile && file.canRead(), writable = file.isFile && file.canWrite(),
                sizeBytes = file.takeIf { it.isFile }?.length() ?: 0L
            )
        }
        return rootStates + dailyStates
    }

    private fun treeHealth(treeUri: Uri, diagnostic: ReportHealthRegistry.Snapshot): HealthSnapshot {
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == treeUri }
        val canRead = permission?.isReadPermission == true
        val canWrite = permission?.isWritePermission == true
        val resolver = context.contentResolver
        val rootStates = statuses.map { status ->
            val uri = if (canRead) findTreeRootReport(resolver, treeUri, status) else null
            ReportFileState(
                name = rootReportName(status), exists = uri != null,
                readable = uri != null && canRead, writable = uri != null && canWrite,
                sizeBytes = uri?.let(::documentSize) ?: 0L
            )
        }
        val latest = if (canRead) listTreeDateDirectories(resolver, treeUri).lastOrNull() else null
        val dailyStates = if (latest == null) emptyList() else statuses.map { status ->
            val uri = findTreeDailyReport(resolver, treeUri, latest.name, status)
            ReportFileState(
                name = "${latest.name}/${status.name}/${reportName(latest.name, status)}",
                exists = uri != null, readable = uri != null && canRead, writable = uri != null && canWrite,
                sizeBytes = uri?.let(::documentSize) ?: 0L
            )
        }
        val files = rootStates + dailyStates
        return HealthSnapshot(
            destination = treeUri.toString(), destinationType = "saf", available = canRead, writable = canWrite,
            files = files, lastSuccessAt = diagnostic.lastSuccessAt, lastFailureAt = diagnostic.lastFailureAt,
            lastOperation = diagnostic.lastOperation, lastError = diagnostic.lastError
        )
    }

    private fun documentSize(uri: Uri): Long {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
            val column = cursor?.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE) ?: -1
            if (cursor != null && cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) cursor.getLong(column).coerceAtLeast(0L) else 0L
        } finally { cursor?.close() }
    }

    private fun containsMarkerLocal(report: File, marker: String): Boolean =
        report.isFile && report.length() > 0L && ReportTailReader.recentBytesContain(report, marker, RECOVERY_MARKER_SCAN_BYTES)

    private fun containsMarkerTree(resolver: ContentResolver, report: Uri, marker: String): Boolean =
        resolver.openInputStream(report)?.bufferedReader(Charsets.UTF_8)?.useLines { lines -> lines.any { marker in it } } ?: false

    private fun containsExactLineLocal(report: File, expected: String): Boolean =
        report.isFile && report.useLines(Charsets.UTF_8) { lines -> lines.any { it == expected } }

    private fun containsExactLineTree(resolver: ContentResolver, report: Uri, expected: String): Boolean =
        resolver.openInputStream(report)?.bufferedReader(Charsets.UTF_8)?.useLines { lines -> lines.any { it == expected } } ?: false

    private fun rewriteWithoutLine(source: File, destination: File, expectedLine: String): Boolean {
        var removed = false
        FileOutputStream(destination).bufferedWriter(Charsets.UTF_8).use { writer ->
            source.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (!removed && line == expectedLine) removed = true else writer.append(line).append('\n')
                }
            }
            writer.flush()
        }
        return removed
    }

    private fun writeWholeTreeDocument(resolver: ContentResolver, uri: Uri, source: File) {
        resolver.openOutputStream(uri, "wt")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
            output.flush()
        } ?: throw IOException("Cannot rewrite output report")
    }

    private fun ensureLocalDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create OUTPUT directory: ${directory.absolutePath}")
        if (!directory.isDirectory) throw IOException("OUTPUT path is not a directory")
    }

    private fun rootTreeDocument(treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri)
    )

    private fun reportName(date: String, status: ProcessingStatus): String = "${date}_${status.name}.txt"

    private fun rootReportName(status: ProcessingStatus): String = "${status.name}.TXT"

    private fun dateSortKey(date: String): String {
        val parts = date.split('-')
        return if (parts.size == 3) "${parts[2]}-${parts[1]}-${parts[0]}" else date
    }

    private fun requireValidDate(date: String) {
        require(DATE_REGEX.matches(date)) { "Invalid output report date: $date" }
    }

    private fun requireReportStatus(status: ProcessingStatus) {
        require(status in statuses) { "Unsupported report status: ${status.name}" }
    }

    private fun sanitize(value: String): String = value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

    private fun utcTimestamp(): String = java.text.SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US
    ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())

    companion object {
        private val statuses = listOf(ProcessingStatus.GOOD, ProcessingStatus.FAILED, ProcessingStatus.ERROR)
        private val DATE_REGEX = Regex("\\d{2}-\\d{2}-\\d{4}")
        private const val REPORT_PREFERENCES = "output_report_layout"
        private const val KEY_DUAL_LAYOUT_MIGRATED_DESTINATION = "dual_layout_0546_destination"
        private const val RECOVERY_MARKER_SCAN_BYTES = 4L * 1024L * 1024L
    }
}
