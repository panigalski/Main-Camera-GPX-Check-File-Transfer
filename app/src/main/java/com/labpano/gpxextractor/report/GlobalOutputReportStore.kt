package com.labpano.gpxextractor.report

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.labpano.gpxextractor.AppConfig
import com.labpano.gpxextractor.data.ProcessingStatus
import com.labpano.gpxextractor.ui.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Owns the three cumulative reports at the selected OUTPUT root.
 *
 * OUTPUT/
 *   GOOD.TXT
 *   FAILED.TXT
 *   ERROR.TXT
 *   dd-mm-yyyy/   (media/GPX only; no per-day TXT files)
 *
 * Supports both ordinary filesystem output and a persisted Storage Access Framework tree.
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

    fun ensureReportFiles(destination: Destination = currentDestination()) = synchronized(ReportFileAccess.lock) {
        try {
            statuses.forEach { ensureReport(destination, it) }
            ReportHealthRegistry.success(context, "ensure-report-files")
        } catch (error: Throwable) {
            ReportHealthRegistry.failure(context, "ensure-report-files", error)
            throw error
        }
    }

    fun appendOnce(
        status: ProcessingStatus,
        sourcePath: String,
        message: String,
        transactionId: String?,
        destination: Destination
    ) = synchronized(ReportFileAccess.lock) {
        try {
            val marker = transactionId?.let { "transactionId=$it" }
            val safePath = sanitize(sourcePath)
            val safeMessage = sanitize(message)
            val line = "${utcTimestamp()}\t$safePath\t$safeMessage\n"

            when {
                destination.treeUri != null -> appendTree(destination.treeUri, status, line, marker)
                destination.directory != null -> appendLocal(destination.directory, status, line, marker)
                else -> throw IOException("Output report destination is not configured")
            }
            ReportHealthRegistry.success(context, "append-${status.name.lowercase(Locale.US)}")
        } catch (error: Throwable) {
            ReportHealthRegistry.failure(context, "append-${status.name.lowercase(Locale.US)}", error)
            throw error
        }
    }

    fun readTail(status: ProcessingStatus, maxLines: Int, destination: Destination = currentDestination()): List<String> =
        synchronized(ReportFileAccess.lock) {
            try {
                val lines = when {
                    destination.treeUri != null -> readTailTree(destination.treeUri, status, maxLines)
                    destination.directory != null -> {
                        val file = File(destination.directory, reportName(status))
                        if (!file.isFile || !file.canRead()) emptyList()
                        else ReportTailReader.lastNonBlankLines(file, maxLines)
                    }
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
                    destination.treeUri != null -> treeHealth(destination.treeUri, diagnostic)
                    destination.directory != null -> localHealth(destination.directory, diagnostic)
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
        when {
            destination.treeUri != null -> deleteTreeLine(destination.treeUri, status, expectedLine)
            destination.directory != null -> deleteLocalLine(destination.directory, status, expectedLine)
            else -> DeleteResult(false, 404, "Output report destination is not configured")
        }
    }

    private fun appendLocal(
        directory: File,
        status: ProcessingStatus,
        line: String,
        marker: String?
    ) {
        ensureLocalDirectory(directory)
        val report = File(directory, reportName(status))
        if (!report.exists() && !report.createNewFile()) {
            throw IOException("Cannot create report file: ${report.absolutePath}")
        }
        if (marker != null && containsMarkerLocal(report, marker)) return
        FileOutputStream(report, true).use { output ->
            output.write(line.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun appendTree(treeUri: Uri, status: ProcessingStatus, line: String, marker: String?) {
        val resolver = context.contentResolver
        val report = ensureTreeReport(resolver, treeUri, status)
        if (marker != null && containsMarkerTree(resolver, report, marker)) return

        // ExternalStorageProvider supports append mode on Pilot OS / Android 7. Fall back to a
        // seekable read-write descriptor for providers that do not implement "wa" directly.
        val appended = runCatching {
            resolver.openOutputStream(report, "wa")?.use { output ->
                output.write(line.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: throw IOException("Cannot append ${reportName(status)}")
        }.isSuccess
        if (!appended) {
            resolver.openFileDescriptor(report, "rw")?.use { descriptor ->
                val output = FileOutputStream(descriptor.fileDescriptor)
                val size = descriptor.statSize.coerceAtLeast(0L)
                output.channel.position(size)
                output.write(line.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
                // ParcelFileDescriptor owns the descriptor and closes it at the end of this block.
            } ?: throw IOException("Cannot open ${reportName(status)} for append")
        }
    }

    private fun readTailTree(treeUri: Uri, status: ProcessingStatus, maxLines: Int): List<String> {
        if (maxLines <= 0) return emptyList()
        val resolver = context.contentResolver
        val report = findTreeReport(resolver, treeUri, reportName(status)) ?: return emptyList()
        readRecentTreeText(resolver, report, TREE_TAIL_SCAN_BYTES)?.let { text ->
            return text.lineSequence().filter { it.isNotBlank() }.toList().takeLast(maxLines)
        }

        // Rare provider fallback when a seekable descriptor/stat size is unavailable.
        val lines = ArrayDeque<String>(maxLines.coerceAtLeast(1))
        resolver.openInputStream(report)?.bufferedReader(Charsets.UTF_8)?.useLines { sequence ->
            sequence.forEach { line ->
                if (line.isNotBlank()) {
                    if (lines.size >= maxLines) lines.removeFirst()
                    lines.addLast(line)
                }
            }
        }
        return lines.toList()
    }

    private fun deleteLocalLine(directory: File, status: ProcessingStatus, expectedLine: String): DeleteResult {
        val report = File(directory, reportName(status))
        if (!report.exists()) return DeleteResult(false, 404, "${report.name} does not exist")
        if (!report.isFile || !report.canRead() || !report.canWrite()) {
            return DeleteResult(false, 403, "${report.name} is not writable")
        }

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
                    report.delete()
                    backup.renameTo(report)
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
        val report = findTreeReport(resolver, treeUri, reportName(status))
            ?: return DeleteResult(false, 404, "${reportName(status)} does not exist")
        val backup = File(context.cacheDir, "report-backup-${System.nanoTime()}.txt")
        val rewritten = File(context.cacheDir, "report-rewrite-${System.nanoTime()}.txt")
        return try {
            resolver.openInputStream(report)?.use { input ->
                FileOutputStream(backup).use { output -> input.copyTo(output); output.fd.sync() }
            } ?: throw IOException("Cannot read ${reportName(status)}")
            val removed = rewriteWithoutLine(backup, rewritten, expectedLine)
            if (!removed) return DeleteResult(false, 404, "Entry no longer exists")
            FileOutputStream(rewritten, true).use { it.fd.sync() }
            writeWholeTreeDocument(resolver, report, rewritten)
            DeleteResult(true, 200, "Entry deleted")
        } catch (error: Throwable) {
            // Best-effort restoration if a provider failed after truncation.
            if (backup.isFile) runCatching { writeWholeTreeDocument(resolver, report, backup) }
            DeleteResult(false, 500, error.message ?: error.javaClass.simpleName)
        } finally {
            backup.delete()
            rewritten.delete()
        }
    }

    private fun rewriteWithoutLine(source: File, destination: File, expectedLine: String): Boolean {
        var removed = false
        FileOutputStream(destination).bufferedWriter(Charsets.UTF_8).use { writer ->
            source.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (!removed && line == expectedLine) removed = true
                    else writer.append(line).append('\n')
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

    private fun ensureReport(destination: Destination, status: ProcessingStatus) {
        when {
            destination.treeUri != null -> ensureTreeReport(context.contentResolver, destination.treeUri, status)
            destination.directory != null -> {
                ensureLocalDirectory(destination.directory)
                val report = File(destination.directory, reportName(status))
                if (!report.exists() && !report.createNewFile()) {
                    throw IOException("Cannot create report file: ${report.absolutePath}")
                }
                if (!report.isFile) throw IOException("Report path is not a file: ${report.absolutePath}")
                if (!report.canRead()) throw IOException("Report file is not readable: ${report.absolutePath}")
                if (!report.canWrite()) throw IOException("Report file is not writable: ${report.absolutePath}")
            }
            else -> throw IOException("Output report destination is not configured")
        }
    }

    private fun ensureLocalDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Cannot create output report directory: ${directory.absolutePath}")
        }
        if (!directory.isDirectory) throw IOException("Output report path is not a directory")
    }

    private fun ensureTreeReport(resolver: ContentResolver, treeUri: Uri, status: ProcessingStatus): Uri {
        val name = reportName(status)
        findTreeReport(resolver, treeUri, name)?.let { return it }
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        return DocumentsContract.createDocument(resolver, rootDocument, "text/plain", name)
            ?: throw IOException("Cannot create $name in selected OUTPUT folder")
    }

    private fun findTreeReport(resolver: ContentResolver, treeUri: Uri, name: String): Uri? {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )
            val idColumn = cursor?.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID) ?: -1
            val nameColumn = cursor?.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: -1
            if (cursor != null && idColumn >= 0 && nameColumn >= 0) {
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn).equals(name, ignoreCase = true)) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
                    }
                }
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun containsMarkerLocal(report: File, marker: String): Boolean {
        if (!report.isFile || report.length() == 0L) return false
        return ReportTailReader.recentBytesContain(report, marker, RECOVERY_MARKER_SCAN_BYTES)
    }

    private fun containsMarkerTree(resolver: ContentResolver, report: Uri, marker: String): Boolean {
        readRecentTreeText(resolver, report, RECOVERY_MARKER_SCAN_BYTES)?.let { return marker in it }
        return resolver.openInputStream(report)?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
            lines.any { marker in it }
        } ?: false
    }

    private fun readRecentTreeText(resolver: ContentResolver, report: Uri, maxBytes: Long): String? {
        return runCatching {
            resolver.openFileDescriptor(report, "r")?.use { descriptor ->
                val size = descriptor.statSize
                if (size < 0L) return@use null
                val toRead = minOf(size, maxBytes).toInt()
                val start = size - toRead
                val input = java.io.FileInputStream(descriptor.fileDescriptor)
                input.channel.position(start)
                val bytes = ByteArray(toRead)
                var total = 0
                while (total < bytes.size) {
                    val count = input.read(bytes, total, bytes.size - total)
                    if (count < 0) break
                    total += count
                }
                var text = bytes.copyOf(total).toString(Charsets.UTF_8)
                if (start > 0L) text = text.substringAfter('\n', "")
                text
            }
        }.getOrNull()
    }

    private fun localHealth(directory: File, diagnostic: ReportHealthRegistry.Snapshot): HealthSnapshot {
        val files = statuses.map { status ->
            val file = File(directory, reportName(status))
            ReportFileState(
                name = file.name,
                exists = file.isFile,
                readable = file.isFile && file.canRead(),
                writable = file.isFile && file.canWrite(),
                sizeBytes = file.takeIf { it.isFile }?.length() ?: 0L
            )
        }
        val directoryAvailable = directory.isDirectory && directory.canRead()
        val directoryWritable = directory.isDirectory && directory.canWrite()
        return HealthSnapshot(
            destination = directory.absolutePath,
            destinationType = "filesystem",
            available = directoryAvailable && files.all { it.exists && it.readable },
            writable = directoryWritable && files.all { it.exists && it.writable },
            files = files,
            lastSuccessAt = diagnostic.lastSuccessAt,
            lastFailureAt = diagnostic.lastFailureAt,
            lastOperation = diagnostic.lastOperation,
            lastError = diagnostic.lastError
        )
    }

    private fun treeHealth(treeUri: Uri, diagnostic: ReportHealthRegistry.Snapshot): HealthSnapshot {
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == treeUri }
        val canRead = permission?.isReadPermission == true
        val canWrite = permission?.isWritePermission == true
        val files = statuses.map { status ->
            val name = reportName(status)
            val uri = findTreeReport(context.contentResolver, treeUri, name)
            ReportFileState(
                name = name,
                exists = uri != null,
                readable = uri != null && canRead,
                writable = uri != null && canWrite,
                sizeBytes = uri?.let(::documentSize) ?: 0L
            )
        }
        return HealthSnapshot(
            destination = treeUri.toString(),
            destinationType = "saf",
            available = canRead && files.all { it.exists && it.readable },
            writable = canWrite && files.all { it.exists && it.writable },
            files = files,
            lastSuccessAt = diagnostic.lastSuccessAt,
            lastFailureAt = diagnostic.lastFailureAt,
            lastOperation = diagnostic.lastOperation,
            lastError = diagnostic.lastError
        )
    }

    private fun documentSize(uri: Uri): Long {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null
            )
            val column = cursor?.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE) ?: -1
            if (cursor != null && cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) {
                cursor.getLong(column).coerceAtLeast(0L)
            } else 0L
        } finally {
            cursor?.close()
        }
    }

    private fun reportName(status: ProcessingStatus): String = "${status.name}.TXT"

    private fun sanitize(value: String): String =
        value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

    private fun utcTimestamp(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    companion object {
        private val statuses = listOf(ProcessingStatus.GOOD, ProcessingStatus.FAILED, ProcessingStatus.ERROR)
        private const val RECOVERY_MARKER_SCAN_BYTES = 4L * 1024L * 1024L
        private const val TREE_TAIL_SCAN_BYTES = 8L * 1024L * 1024L
    }
}
