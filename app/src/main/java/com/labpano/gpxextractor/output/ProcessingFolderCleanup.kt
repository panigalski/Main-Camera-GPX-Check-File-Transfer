package com.labpano.gpxextractor.output

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.labpano.gpxextractor.report.GlobalOutputReportStore
import com.labpano.gpxextractor.util.AppLog

/** Removes legacy empty OUTPUT/dd-MM-yyyy/PROCESSING folders left by older releases. */
object ProcessingFolderCleanup {
    fun cleanup(context: Context, destination: GlobalOutputReportStore.Destination) {
        runCatching {
            when {
                destination.treeUri != null -> cleanupTree(context.contentResolver, destination.treeUri)
                destination.directory != null -> cleanupLocal(destination.directory)
                else -> Unit
            }
        }.onFailure { error ->
            AppLog.warn("Cannot clean legacy PROCESSING folders: ${error.message}")
        }
    }

    fun cleanupLocal(root: java.io.File): Int = ProcessingFolderCleanupPolicy.cleanupLocal(root)

    private fun cleanupTree(resolver: ContentResolver, treeUri: Uri) {
        val root = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        listChildren(resolver, treeUri, root).forEach { dateChild ->
            if (!ProcessingFolderCleanupPolicy.isRecordingDateFolder(dateChild.name) || !isDirectory(resolver, dateChild.uri)) return@forEach
            val processing = listChildren(resolver, treeUri, dateChild.uri).firstOrNull {
                it.name.equals(ProcessingFolderCleanupPolicy.PROCESSING_FOLDER_NAME, ignoreCase = true) && isDirectory(resolver, it.uri)
            } ?: return@forEach
            if (listChildren(resolver, treeUri, processing.uri).isEmpty()) {
                DocumentsContract.deleteDocument(resolver, processing.uri)
            }
        }
    }

    private data class Child(val name: String, val uri: Uri)

    private fun listChildren(resolver: ContentResolver, treeUri: Uri, parent: Uri): List<Child> {
        val parentId = runCatching { DocumentsContract.getDocumentId(parent) }.getOrNull() ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val result = mutableListOf<Child>()
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                childrenUri,
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
                    val id = cursor.getString(idColumn)
                    val name = cursor.getString(nameColumn).orEmpty()
                    result += Child(name, DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                }
            }
        } finally {
            cursor?.close()
        }
        return result
    }

    private fun isDirectory(resolver: ContentResolver, uri: Uri): Boolean {
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            )
            val column = cursor?.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE) ?: -1
            cursor != null && cursor.moveToFirst() && column >= 0 &&
                cursor.getString(column) == DocumentsContract.Document.MIME_TYPE_DIR
        } finally {
            cursor?.close()
        }
    }
}
