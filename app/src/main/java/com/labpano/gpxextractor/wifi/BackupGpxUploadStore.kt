package com.labpano.gpxextractor.wifi

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.labpano.gpxextractor.AppConfig
import com.labpano.gpxextractor.ui.MainActivity
import com.labpano.gpxextractor.util.StorageAccessCoordinator
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Restricted writer used by the companion Client's manual "Send GPX Files" action.
 *
 * It deliberately cannot write arbitrary paths/files: uploads are limited to one classified date/status folder
 * below the configured OUTPUT root and to Client-generated *_backup.gpx files.
 */
class BackupGpxUploadStore(private val context: Context) {
    class UploadException(val statusCode: Int, message: String) : IOException(message)

    data class Stored(
        val status: String,
        val subfolder: String,
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
        val destination: String,
        val alreadyPresent: Boolean
    )

    fun store(status: String, subfolder: String, fileName: String, bytes: ByteArray, expectedSha256: String?): Stored {
        val normalizedStatus = validateNames(status, subfolder, fileName)
        if (bytes.isEmpty()) throw UploadException(400, "GPX upload is empty")
        if (bytes.size > MAX_GPX_UPLOAD_BYTES) throw UploadException(413, "GPX upload exceeds the size limit")
        val textPrefix = bytes.copyOfRange(0, minOf(bytes.size, 4096)).toString(Charsets.UTF_8)
        if (!textPrefix.contains("<gpx", ignoreCase = true)) {
            throw UploadException(400, "Uploaded content is not a GPX document")
        }
        val digest = sha256(bytes)
        if (!expectedSha256.isNullOrBlank() && !digest.equals(expectedSha256.trim(), ignoreCase = true)) {
            throw UploadException(400, "Upload checksum does not match request checksum")
        }

        val prefs = context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val treeUri = prefs.getString(MainActivity.KEY_OUTPUT_TREE_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
        return if (treeUri != null) {
            storeSaf(treeUri, normalizedStatus, subfolder, fileName, bytes, digest)
        } else {
            val output = prefs.getString(MainActivity.KEY_OUTPUT_DIRECTORY, null)
                ?.takeIf { it.isNotBlank() && !it.startsWith("content://") }
                ?.let(::File)
                ?: AppConfig.defaultOutputDirectory
            storeLocal(output, normalizedStatus, subfolder, fileName, bytes, digest)
        }
    }

    private fun storeLocal(
        outputRoot: File,
        status: String,
        subfolder: String,
        fileName: String,
        bytes: ByteArray,
        digest: String
    ): Stored {
        val canonicalRoot = runCatching { outputRoot.canonicalFile }.getOrElse { outputRoot.absoluteFile }
        if (!canonicalRoot.exists() && !canonicalRoot.mkdirs()) {
            throw UploadException(500, "Cannot create configured Output Folder")
        }
        if (!canonicalRoot.isDirectory) throw UploadException(500, "Configured Output Folder is not a directory")

        val dateFolder = File(canonicalRoot, subfolder).canonicalFile
        if (!dateFolder.path.startsWith(canonicalRoot.path + File.separator)) {
            throw UploadException(400, "Invalid GPX date folder")
        }
        if (!dateFolder.exists() && !dateFolder.mkdirs()) {
            throw UploadException(500, "Cannot create Output Folder date folder $subfolder")
        }
        if (!dateFolder.isDirectory) throw UploadException(409, "$subfolder already exists but is not a folder")

        val folder = File(dateFolder, status).canonicalFile
        if (!folder.path.startsWith(dateFolder.path + File.separator)) {
            throw UploadException(400, "Invalid GPX status folder")
        }
        if (!folder.exists() && !folder.mkdirs()) throw UploadException(500, "Cannot create Output Folder subfolder $subfolder/$status")
        if (!folder.isDirectory) throw UploadException(409, "$subfolder/$status already exists but is not a folder")

        val destination = File(folder, fileName).canonicalFile
        if (destination.parentFile?.canonicalPath != folder.canonicalPath) {
            throw UploadException(400, "Invalid GPX file name")
        }
        val temp = File(folder, ".${fileName}.upload-${UUID.randomUUID().toString().take(8)}.tmp")

        return StorageAccessCoordinator.withWrite(listOf(destination, temp)) {
            if (destination.isFile) {
                val existingDigest = sha256(destination)
                if (destination.length() == bytes.size.toLong() && existingDigest.equals(digest, ignoreCase = true)) {
                    return@withWrite Stored(status, subfolder, fileName, destination.length(), digest, destination.absolutePath, true)
                }
                throw UploadException(409, "A different $fileName already exists in $subfolder/$status")
            }
            if (destination.exists()) throw UploadException(409, "$fileName already exists but is not a file")

            try {
                FileOutputStream(temp).use { output ->
                    output.write(bytes)
                    output.flush()
                    output.fd.sync()
                }
                verifyLocal(temp, bytes.size.toLong(), digest)
                if (!temp.renameTo(destination)) throw UploadException(500, "Cannot finalize GPX upload")
                verifyLocal(destination, bytes.size.toLong(), digest)
                Stored(status, subfolder, fileName, destination.length(), digest, destination.absolutePath, false)
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
    }

    private fun storeSaf(
        treeUri: Uri,
        status: String,
        subfolder: String,
        fileName: String,
        bytes: ByteArray,
        digest: String
    ): Stored {
        val resolver = context.contentResolver
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == treeUri }
        if (permission == null || !permission.isWritePermission) {
            throw UploadException(500, "Output Folder permission is no longer writable")
        }
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val dateFolder = findChild(resolver, root, subfolder)?.let { child ->
            if (child.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
                throw UploadException(409, "$subfolder already exists but is not a folder")
            }
            child.uri
        } ?: DocumentsContract.createDocument(
            resolver,
            root,
            DocumentsContract.Document.MIME_TYPE_DIR,
            subfolder
        ) ?: throw UploadException(500, "Cannot create Output Folder date folder $subfolder")

        val folder = findChild(resolver, dateFolder, status)?.let { child ->
            if (child.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
                throw UploadException(409, "$subfolder/$status already exists but is not a folder")
            }
            child.uri
        } ?: DocumentsContract.createDocument(
            resolver,
            dateFolder,
            DocumentsContract.Document.MIME_TYPE_DIR,
            status
        ) ?: throw UploadException(500, "Cannot create Output Folder subfolder $subfolder/$status")

        val existing = findChild(resolver, folder, fileName)
        if (existing != null) {
            if (existing.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                throw UploadException(409, "$fileName already exists but is a folder")
            }
            val (existingSize, existingDigest) = digestAndSize(resolver, existing.uri)
            if (existingSize == bytes.size.toLong() && existingDigest.equals(digest, ignoreCase = true)) {
                return Stored(status, subfolder, fileName, existingSize, digest, "$subfolder/$status/$fileName", true)
            }
            throw UploadException(409, "A different $fileName already exists in $subfolder/$status")
        }

        val temporaryName = ".upload-${UUID.randomUUID().toString().take(8)}-$fileName"
        val temporary = DocumentsContract.createDocument(resolver, folder, "application/gpx+xml", temporaryName)
            ?: DocumentsContract.createDocument(resolver, folder, "application/octet-stream", temporaryName)
            ?: throw UploadException(500, "Cannot create temporary GPX in Output Folder")
        var finalized = false
        try {
            val output = resolver.openOutputStream(temporary, "wt")
                ?: resolver.openOutputStream(temporary, "w")
                ?: throw UploadException(500, "Cannot write temporary GPX in Output Folder")
            output.use { it.write(bytes); it.flush() }
            verifySaf(resolver, temporary, bytes.size.toLong(), digest)

            val finalUri = DocumentsContract.renameDocument(resolver, temporary, fileName)
            if (finalUri != null) {
                verifySaf(resolver, finalUri, bytes.size.toLong(), digest)
                finalized = true
                return Stored(status, subfolder, fileName, bytes.size.toLong(), digest, "$subfolder/$status/$fileName", false)
            }

            // Provider has no rename support. Create the final document only after the temporary
            // document has been fully verified, then verify the final copy before deleting temp.
            val finalDocument = DocumentsContract.createDocument(resolver, folder, "application/gpx+xml", fileName)
                ?: DocumentsContract.createDocument(resolver, folder, "application/octet-stream", fileName)
                ?: throw UploadException(500, "Cannot create final GPX in Output Folder")
            try {
                val finalOutput = resolver.openOutputStream(finalDocument, "wt")
                    ?: resolver.openOutputStream(finalDocument, "w")
                    ?: throw UploadException(500, "Cannot write final GPX in Output Folder")
                finalOutput.use { it.write(bytes); it.flush() }
                verifySaf(resolver, finalDocument, bytes.size.toLong(), digest)
                runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
                finalized = true
                return Stored(status, subfolder, fileName, bytes.size.toLong(), digest, "$subfolder/$status/$fileName", false)
            } catch (error: Throwable) {
                runCatching { DocumentsContract.deleteDocument(resolver, finalDocument) }
                throw error
            }
        } finally {
            if (!finalized) runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
        }
    }

    private data class Child(val uri: Uri, val mimeType: String?)

    private fun findChild(resolver: ContentResolver, parent: Uri, displayName: String): Child? {
        val parentId = runCatching { DocumentsContract.getDocumentId(parent) }.getOrNull() ?: return null
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )
            if (cursor == null) return null
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (idColumn < 0 || nameColumn < 0) return null
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == displayName) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(idColumn))
                    val mime = if (mimeColumn >= 0 && !cursor.isNull(mimeColumn)) cursor.getString(mimeColumn) else null
                    return Child(uri, mime)
                }
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun verifyLocal(file: File, expectedSize: Long, expectedDigest: String) {
        if (!file.isFile || file.length() != expectedSize || !sha256(file).equals(expectedDigest, ignoreCase = true)) {
            throw UploadException(500, "Saved GPX verification failed")
        }
    }

    private fun verifySaf(resolver: ContentResolver, uri: Uri, expectedSize: Long, expectedDigest: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val input = resolver.openInputStream(uri) ?: throw UploadException(500, "Cannot verify saved GPX")
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                count += read
                digest.update(buffer, 0, read)
            }
        }
        if (count != expectedSize || hex(digest.digest()) != expectedDigest.lowercase(Locale.US)) {
            throw UploadException(500, "Saved GPX verification failed")
        }
    }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun digestAndSize(resolver: ContentResolver, uri: Uri): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val input = resolver.openInputStream(uri) ?: throw UploadException(500, "Cannot read existing GPX")
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                count += read
                digest.update(buffer, 0, read)
            }
        }
        return count to hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

    private fun validateNames(status: String, subfolder: String, fileName: String): String {
        val normalizedStatus = status.trim().uppercase(Locale.US)
        if (normalizedStatus !in ALLOWED_STATUSES) throw UploadException(400, "Invalid GPX status folder")
        if (!DATE_FOLDER.matches(subfolder)) throw UploadException(400, "GPX subfolder must use dd-MM-yyyy")
        if (!BACKUP_GPX_FILE.matches(fileName)) {
            throw UploadException(400, "Only Client-generated *_backup.gpx files can be uploaded")
        }
        if (fileName.contains('/') || fileName.contains('\\') || subfolder.contains('/') || subfolder.contains('\\')) {
            throw UploadException(400, "Invalid GPX destination name")
        }
        return normalizedStatus
    }

    companion object {
        const val MAX_GPX_UPLOAD_BYTES = 16 * 1024 * 1024
        private val DATE_FOLDER = Regex("^(0[1-9]|[12][0-9]|3[01])-(0[1-9]|1[0-2])-20[0-9]{2}$")
        private val ALLOWED_STATUSES = setOf("GOOD", "FAILED", "ERROR")
        private val BACKUP_GPX_FILE = Regex("^[A-Za-z0-9][A-Za-z0-9._() -]{0,180}_backup(?: \\(\\d+\\))?\\.gpx$", RegexOption.IGNORE_CASE)
    }
}
