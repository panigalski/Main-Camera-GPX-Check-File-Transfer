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

/** Writes canonical daily reports into the selected output location. */
class DatedOutputReportWriter(private val context: Context) {
    fun append(
        status: ProcessingStatus,
        sourcePath: String,
        message: String,
        layout: DatedOutputLayout,
        outputDirectory: File?,
        outputTreeUri: Uri?
    ) = appendOnce(status, sourcePath, message, null, layout, outputDirectory, outputTreeUri)

    fun appendOnce(
        status: ProcessingStatus,
        sourcePath: String,
        message: String,
        transactionId: String?,
        layout: DatedOutputLayout,
        outputDirectory: File?,
        outputTreeUri: Uri?
    ) = synchronized(ReportFileAccess.lock) {
        val safePath = sanitize(sourcePath)
        val safeMessage = sanitize(message)
        val line = "${utcTimestamp()}\t$safePath\t$safeMessage\n"
        if (outputTreeUri != null) {
            appendToTree(outputTreeUri, layout, status, line, transactionId)
        } else {
            appendToDirectory(requireNotNull(outputDirectory), layout, status, line, transactionId)
        }
    }

    private fun appendToDirectory(
        root: File,
        layout: DatedOutputLayout,
        status: ProcessingStatus,
        line: String,
        transactionId: String?
    ) {
        val daily = File(root, layout.date)
        if (!daily.exists() && !daily.mkdirs()) throw IOException("Cannot create daily output folder: ${daily.absolutePath}")
        val report = File(daily, layout.reportFileName(status))
        val marker = transactionId?.let { "transactionId=$it" }
        if (marker != null && report.isFile && report.useLines(Charsets.UTF_8) { it.any { row -> marker in row } }) return
        FileOutputStream(report, true).use { output ->
            output.write(line.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun appendToTree(
        treeUri: Uri,
        layout: DatedOutputLayout,
        status: ProcessingStatus,
        line: String,
        transactionId: String?
    ) {
        val resolver = context.contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val daily = findOrCreateDirectory(resolver, treeUri, root, layout.date)
        val fileName = layout.reportFileName(status)
        val report = findChild(resolver, treeUri, daily, fileName)
            ?: DocumentsContract.createDocument(resolver, daily, "text/plain", fileName)
            ?: throw IOException("Cannot create report $fileName")

        val marker = transactionId?.let { "transactionId=$it" }
        if (marker != null && documentContains(resolver, report, marker)) return

        val bytes = line.toByteArray(Charsets.UTF_8)
        val beforeSize = documentSize(resolver, report) ?: 0L
        val appended = appendUsingSeekableDescriptor(resolver, report, bytes) ||
            appendUsingProviderMode(resolver, report, bytes)
        if (appended && (
                verifyDocumentGrowth(resolver, report, beforeSize + bytes.size) ||
                    (marker != null && documentContains(resolver, report, marker))
            )
        ) return

        // Last-resort provider fallback. Daily report files are bounded to avoid loading an
        // arbitrarily large document into memory when append is unsupported.
        val existingSize = documentSize(resolver, report) ?: 0L
        if (existingSize > MAX_REWRITE_BYTES) {
            throw IOException("Storage provider does not support safe report append for $fileName")
        }
        val existing = resolver.openInputStream(report)?.use { it.readBytes() } ?: ByteArray(0)
        resolver.openOutputStream(report, "w")?.use {
            it.write(existing)
            it.write(bytes)
            it.flush()
        } ?: throw IOException("Cannot write report $fileName")
        if (!verifyDocumentGrowth(resolver, report, existing.size.toLong() + bytes.size)) {
            throw IOException("Report append verification failed for $fileName")
        }
    }

    private fun appendUsingSeekableDescriptor(resolver: ContentResolver, report: Uri, bytes: ByteArray): Boolean =
        runCatching {
            resolver.openFileDescriptor(report, "rw")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).channel.use { channel ->
                    channel.position(channel.size())
                    val buffer = java.nio.ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
            } ?: return@runCatching false
            true
        }.getOrDefault(false)

    private fun appendUsingProviderMode(resolver: ContentResolver, report: Uri, bytes: ByteArray): Boolean =
        runCatching {
            val stream = resolver.openOutputStream(report, "wa") ?: return@runCatching false
            stream.use {
                it.write(bytes)
                it.flush()
            }
            true
        }.getOrDefault(false)

    private fun verifyDocumentGrowth(resolver: ContentResolver, report: Uri, expectedMinimum: Long): Boolean {
        repeat(8) {
            val size = documentSize(resolver, report)
            if (size != null && size >= expectedMinimum) return true
            android.os.SystemClock.sleep(100L)
        }
        return false
    }

    private fun documentContains(resolver: ContentResolver, report: Uri, marker: String): Boolean {
        val size = documentSize(resolver, report) ?: return false
        if (size <= 0L || size > MAX_REWRITE_BYTES) return false
        return resolver.openInputStream(report)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            reader.lineSequence().any { marker in it }
        } ?: false
    }

    private fun documentSize(resolver: ContentResolver, uri: Uri): Long? {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
        }
        return null
    }

    private fun findOrCreateDirectory(
        resolver: ContentResolver,
        treeUri: Uri,
        parentUri: Uri,
        name: String
    ): Uri = findChild(resolver, treeUri, parentUri, name, DocumentsContract.Document.MIME_TYPE_DIR)
        ?: DocumentsContract.createDocument(resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
        ?: throw IOException("Cannot create output folder: $name")

    private fun findChild(
        resolver: ContentResolver,
        treeUri: Uri,
        parentUri: Uri,
        name: String,
        mimeType: String? = null
    ): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parentUri))
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ), null, null, null
            )
            val idIndex = cursor?.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID) ?: -1
            val nameIndex = cursor?.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: -1
            val mimeIndex = cursor?.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE) ?: -1
            while (cursor != null && cursor.moveToNext()) {
                if (idIndex < 0 || nameIndex < 0) continue
                if (!cursor.getString(nameIndex).equals(name, ignoreCase = true)) continue
                if (mimeType != null && (mimeIndex < 0 || cursor.getString(mimeIndex) != mimeType)) continue
                return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun sanitize(value: String): String =
        value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

    private fun utcTimestamp(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    companion object {
        private const val MAX_REWRITE_BYTES = 16L * 1024L * 1024L
    }
}
