package com.labpano.gpxextractor.report

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.labpano.gpxextractor.data.ProcessingStatus
import com.labpano.gpxextractor.output.DatedOutputLayout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Writes one deterministic status report beside each classified recording. */
class RecordingResultReportStore(private val context: Context) {
    fun write(
        status: ProcessingStatus,
        date: String,
        videoName: String,
        sourcePath: String,
        message: String,
        transactionId: String,
        processedAtMillis: Long,
        destination: GlobalOutputReportStore.Destination
    ) = synchronized(ReportFileAccess.lock) {
        require(DATE_REGEX.matches(date)) { "Invalid output date: $date" }
        val layout = DatedOutputLayout(date)
        val reportName = layout.recordingReportFileName(videoName, status)
        val text = buildString {
            append("STATUS=").append(status.name).append('\n')
            append("VIDEO=").append(sanitize(videoName)).append('\n')
            append("SOURCE=").append(sanitize(sourcePath)).append('\n')
            append("PROCESSED_AT_UTC=").append(utcTimestamp(processedAtMillis)).append('\n')
            append("TRANSACTION_ID=").append(sanitize(transactionId)).append('\n')
            append("DETAILS=").append(sanitize(message)).append('\n')
        }
        when {
            destination.treeUri != null -> writeSaf(destination.treeUri, date, status, reportName, text)
            destination.directory != null -> writeLocal(destination.directory, date, status, reportName, text)
            else -> throw IOException("Output report destination is not configured")
        }
    }

    private fun writeLocal(
        root: File,
        date: String,
        status: ProcessingStatus,
        reportName: String,
        text: String
    ) {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
        if (!canonicalRoot.exists() && !canonicalRoot.mkdirs()) {
            throw IOException("Cannot create Output Folder: ${canonicalRoot.absolutePath}")
        }
        if (!canonicalRoot.isDirectory) throw IOException("Configured Output Folder is not a directory")

        val dateFolder = File(canonicalRoot, date).canonicalFile
        if (!dateFolder.path.startsWith(canonicalRoot.path + File.separator)) throw IOException("Invalid date folder")
        if (!dateFolder.exists() && !dateFolder.mkdirs()) throw IOException("Cannot create $date in Output Folder")
        if (!dateFolder.isDirectory) throw IOException("$date exists but is not a folder")

        val statusFolders = ProcessingStatus.values().associateWith { classifiedStatus ->
            val folder = File(dateFolder, classifiedStatus.name).canonicalFile
            if (!folder.path.startsWith(dateFolder.path + File.separator)) throw IOException("Invalid status folder")
            if (!folder.exists() && !folder.mkdirs()) throw IOException("Cannot create $date/${classifiedStatus.name}")
            if (!folder.isDirectory) throw IOException("$date/${classifiedStatus.name} exists but is not a folder")
            folder
        }
        val statusFolder = requireNotNull(statusFolders[status])

        val target = File(statusFolder, reportName).canonicalFile
        if (target.parentFile?.canonicalPath != statusFolder.canonicalPath) throw IOException("Invalid recording report name")
        if (target.isFile && runCatching { target.readText(Charsets.UTF_8) }.getOrNull() == text) return
        val temporary = File(statusFolder, ".${reportName}.write-${UUID.randomUUID().toString().take(8)}.tmp")
        val backup = File(statusFolder, ".${reportName}.previous")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            if (backup.exists() && !backup.delete()) throw IOException("Cannot clear old report backup")
            if (target.exists() && !target.renameTo(backup)) throw IOException("Cannot preserve ${target.absolutePath}")
            if (!temporary.renameTo(target)) {
                if (!target.exists() && backup.exists()) backup.renameTo(target)
                throw IOException("Cannot finalize ${target.absolutePath}")
            }
            if (!target.isFile || target.readText(Charsets.UTF_8) != text) {
                target.delete()
                if (backup.exists()) backup.renameTo(target)
                throw IOException("Recording report verification failed")
            }
            if (backup.exists()) backup.delete()
        } finally {
            if (temporary.exists()) temporary.delete()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
        }
    }

    private fun writeSaf(
        treeUri: Uri,
        date: String,
        status: ProcessingStatus,
        reportName: String,
        text: String
    ) {
        val resolver = context.contentResolver
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == treeUri }
        if (permission == null || !permission.isWritePermission) throw IOException("Output Folder permission is not writable")
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val dateFolder = ensureDirectory(resolver, treeUri, root, date)
        val statusFolders = ProcessingStatus.values().associateWith { classifiedStatus ->
            ensureDirectory(resolver, treeUri, dateFolder, classifiedStatus.name)
        }
        val statusFolder = requireNotNull(statusFolders[status])
        val existing = findChild(resolver, treeUri, statusFolder, reportName)
        if (existing != null && isDirectory(resolver, existing)) throw IOException("$reportName exists but is a folder")
        val report = existing ?: DocumentsContract.createDocument(resolver, statusFolder, "text/plain", reportName)
            ?: throw IOException("Cannot create $date/${status.name}/$reportName")
        resolver.openOutputStream(report, "wt")?.use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: throw IOException("Cannot write $date/${status.name}/$reportName")
    }

    private fun ensureDirectory(resolver: ContentResolver, treeUri: Uri, parent: Uri, name: String): Uri {
        findChild(resolver, treeUri, parent, name)?.let { existing ->
            if (!isDirectory(resolver, existing)) throw IOException("$name exists but is not a folder")
            return existing
        }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Cannot create folder $name")
    }

    private fun findChild(resolver: ContentResolver, treeUri: Uri, parent: Uri, name: String): Uri? {
        val parentId = runCatching { DocumentsContract.getDocumentId(parent) }.getOrNull() ?: return null
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        var cursor: Cursor? = null
        return try {
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
            null
        } finally {
            cursor?.close()
        }
    }

    private fun isDirectory(resolver: ContentResolver, uri: Uri): Boolean {
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            val column = cursor?.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE) ?: -1
            cursor != null && cursor.moveToFirst() && column >= 0 &&
                cursor.getString(column) == DocumentsContract.Document.MIME_TYPE_DIR
        } finally {
            cursor?.close()
        }
    }

    private fun sanitize(value: String): String =
        value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

    private fun utcTimestamp(millis: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    companion object {
        private val DATE_REGEX = Regex("^(0[1-9]|[12][0-9]|3[01])-(0[1-9]|1[0-2])-20[0-9]{2}$")
    }
}
