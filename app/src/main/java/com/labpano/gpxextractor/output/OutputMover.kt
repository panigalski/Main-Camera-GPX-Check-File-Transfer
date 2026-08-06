package com.labpano.gpxextractor.output

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import com.labpano.gpxextractor.api.TransferProgressRegistry
import com.labpano.gpxextractor.ui.DocumentTreePathResolver
import com.labpano.gpxextractor.util.StorageAccessCoordinator
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Transfers finalized media to the selected output location with verification. */
class OutputMover(private val context: Context) {
    data class Result(
        val videoName: String,
        val gpxName: String?,
        val destination: String,
        val videoPath: String,
        val gpxPath: String?,
        val sourceCleanupPending: Boolean
    )

    fun movePair(
        video: File,
        gpx: File,
        outputDirectory: File?,
        outputTreeUri: Uri?,
        subfolderName: String? = null,
        beforeSourceCleanup: ((Result) -> Unit)? = null
    ): Result = StorageAccessCoordinator.withWrite(listOf(video, gpx)) {
        require(video.isFile) { "Video does not exist: ${video.absolutePath}" }
        require(gpx.isFile) { "GPX does not exist: ${gpx.absolutePath}" }
        if (outputTreeUri != null) movePairToTree(video, gpx, outputTreeUri, subfolderName, beforeSourceCleanup)
        else {
            val root = requireNotNull(outputDirectory)
            val destination = if (subfolderName.isNullOrBlank()) root else File(root, subfolderName)
            movePairToDirectory(video, gpx, destination, beforeSourceCleanup)
        }
    }

    fun moveSingle(
        video: File,
        outputDirectory: File?,
        outputTreeUri: Uri?,
        subfolderName: String? = null,
        beforeSourceCleanup: ((Result) -> Unit)? = null
    ): Result = StorageAccessCoordinator.withWrite(listOf(video)) {
        require(video.isFile) { "Video does not exist: ${video.absolutePath}" }
        if (outputTreeUri != null) moveSingleToTree(video, outputTreeUri, subfolderName, beforeSourceCleanup)
        else {
            val root = requireNotNull(outputDirectory)
            val destination = if (subfolderName.isNullOrBlank()) root else File(root, subfolderName)
            moveSingleToDirectory(video, destination, beforeSourceCleanup)
        }
    }

    fun retrySourceCleanup(videoPath: String, gpxPath: String?): Boolean {
        val files = listOfNotNull(File(videoPath), gpxPath?.let(::File))
        return StorageAccessCoordinator.withWrite(files) {
            files.all { file -> !file.exists() || file.delete() }
        }
    }

    private fun movePairToDirectory(
        video: File,
        gpx: File,
        directory: File,
        beforeSourceCleanup: ((Result) -> Unit)?
    ): Result {
        ensureDirectory(directory)
        val videoExtension = video.extension.ifBlank { "mp4" }

        val exactVideo = File(directory, "${video.nameWithoutExtension}.$videoExtension")
        val exactGpx = File(directory, "${video.nameWithoutExtension}.gpx")
        val exactVideoMatches = exactVideo.isFile && exactVideo.length() == video.length()
        val exactGpxMatches = exactGpx.isFile && exactGpx.length() == gpx.length()

        val targetVideo: File
        val targetGpx: File
        when {
            exactVideoMatches && exactGpxMatches -> {
                targetVideo = exactVideo
                targetGpx = exactGpx
            }
            exactVideoMatches && !exactGpx.exists() -> {
                targetVideo = exactVideo
                targetGpx = exactGpx
                copyFileAtomically(gpx, targetGpx)
            }
            exactGpxMatches && !exactVideo.exists() -> {
                targetVideo = exactVideo
                targetGpx = exactGpx
                copyFileAtomically(video, targetVideo)
            }
            else -> {
                val base = chooseBaseName(video.nameWithoutExtension, videoExtension) { name -> File(directory, name).exists() }
                targetVideo = File(directory, "$base.$videoExtension")
                targetGpx = File(directory, "$base.gpx")
                copyFileAtomically(video, targetVideo)
                try {
                    copyFileAtomically(gpx, targetGpx)
                } catch (error: Exception) {
                    targetVideo.delete()
                    throw error
                }
            }
        }

        verifyLocalDestination(targetVideo, video.length(), "video")
        verifyLocalDestination(targetGpx, gpx.length(), "GPX")
        val prepared = Result(
            videoName = targetVideo.name,
            gpxName = targetGpx.name,
            destination = directory.absolutePath,
            videoPath = targetVideo.absolutePath,
            gpxPath = targetGpx.absolutePath,
            sourceCleanupPending = true
        )
        beforeSourceCleanup?.invoke(prepared)
        val cleanupPending = !deleteAllSources(video, gpx)
        return prepared.copy(sourceCleanupPending = cleanupPending)
    }

    private fun moveSingleToDirectory(
        video: File,
        directory: File,
        beforeSourceCleanup: ((Result) -> Unit)?
    ): Result {
        ensureDirectory(directory)
        val extension = video.extension.ifBlank { "mp4" }
        val exact = File(directory, "${video.nameWithoutExtension}.$extension")
        val target = if (exact.isFile && exact.length() == video.length()) {
            exact
        } else {
            val base = chooseBaseName(video.nameWithoutExtension, extension) { name -> File(directory, name).exists() }
            File(directory, "$base.$extension").also { copyFileAtomically(video, it) }
        }
        verifyLocalDestination(target, video.length(), "video")
        val prepared = Result(
            videoName = target.name,
            gpxName = null,
            destination = directory.absolutePath,
            videoPath = target.absolutePath,
            gpxPath = null,
            sourceCleanupPending = true
        )
        beforeSourceCleanup?.invoke(prepared)
        val cleanupPending = !deleteAllSources(video)
        return prepared.copy(sourceCleanupPending = cleanupPending)
    }

    private fun movePairToTree(
        video: File,
        gpx: File,
        treeUri: Uri,
        subfolderName: String?,
        beforeSourceCleanup: ((Result) -> Unit)?
    ): Result {
        val resolver = context.contentResolver
        val destinationDocumentUri = destinationDocument(resolver, treeUri, subfolderName)
        val videoExtension = video.extension.ifBlank { "mp4" }
        val exactVideoName = "${video.nameWithoutExtension}.$videoExtension"
        val exactGpxName = "${video.nameWithoutExtension}.gpx"
        val exactVideoUri = findChildDocument(resolver, treeUri, destinationDocumentUri, exactVideoName)
        val exactGpxUri = findChildDocument(resolver, treeUri, destinationDocumentUri, exactGpxName)
        val exactVideoMatches = exactVideoUri != null && documentSize(resolver, exactVideoUri) == video.length()
        val exactGpxMatches = exactGpxUri != null && documentSize(resolver, exactGpxUri) == gpx.length()

        val videoName: String
        val gpxName: String
        val videoUri: Uri
        val gpxUri: Uri
        when {
            exactVideoMatches && exactGpxMatches -> {
                videoName = exactVideoName
                gpxName = exactGpxName
                videoUri = requireNotNull(exactVideoUri)
                gpxUri = requireNotNull(exactGpxUri)
            }
            exactVideoMatches && exactGpxUri == null -> {
                videoName = exactVideoName
                gpxName = exactGpxName
                videoUri = requireNotNull(exactVideoUri)
                gpxUri = createAndCopy(resolver, destinationDocumentUri, "application/gpx+xml", gpxName, gpx)
            }
            exactGpxMatches && exactVideoUri == null -> {
                videoName = exactVideoName
                gpxName = exactGpxName
                videoUri = createAndCopy(resolver, destinationDocumentUri, "video/mp4", videoName, video)
                gpxUri = requireNotNull(exactGpxUri)
            }
            else -> {
                val names = childNames(resolver, treeUri, destinationDocumentUri)
                val base = chooseBaseName(video.nameWithoutExtension, videoExtension) { names.contains(it.lowercase()) }
                videoName = "$base.$videoExtension"
                gpxName = "$base.gpx"
                videoUri = createAndCopy(resolver, destinationDocumentUri, "video/mp4", videoName, video)
                gpxUri = try {
                    createAndCopy(resolver, destinationDocumentUri, "application/gpx+xml", gpxName, gpx)
                } catch (error: Exception) {
                    runCatching { DocumentsContract.deleteDocument(resolver, videoUri) }
                    throw error
                }
            }
        }

        verifyDocumentCopy(resolver, videoUri, video.length(), videoName)
        verifyDocumentCopy(resolver, gpxUri, gpx.length(), gpxName)
        val destinationLabel = humanReadableTreePath(treeUri, subfolderName)
        val prepared = Result(
            videoName = videoName,
            gpxName = gpxName,
            destination = destinationLabel,
            videoPath = videoUri.toString(),
            gpxPath = gpxUri.toString(),
            sourceCleanupPending = true
        )
        beforeSourceCleanup?.invoke(prepared)
        val cleanupPending = !deleteAllSources(video, gpx)
        return prepared.copy(sourceCleanupPending = cleanupPending)
    }

    private fun moveSingleToTree(
        video: File,
        treeUri: Uri,
        subfolderName: String?,
        beforeSourceCleanup: ((Result) -> Unit)?
    ): Result {
        val resolver = context.contentResolver
        val destinationDocumentUri = destinationDocument(resolver, treeUri, subfolderName)
        val extension = video.extension.ifBlank { "mp4" }
        val exactName = "${video.nameWithoutExtension}.$extension"
        val exactUri = findChildDocument(resolver, treeUri, destinationDocumentUri, exactName)
        val reusable = exactUri != null && documentSize(resolver, exactUri) == video.length()
        val videoName: String
        val videoUri: Uri
        if (reusable) {
            videoName = exactName
            videoUri = requireNotNull(exactUri)
        } else {
            val names = childNames(resolver, treeUri, destinationDocumentUri)
            val base = chooseBaseName(video.nameWithoutExtension, extension) { names.contains(it.lowercase()) }
            videoName = "$base.$extension"
            videoUri = createAndCopy(resolver, destinationDocumentUri, "video/mp4", videoName, video)
        }
        verifyDocumentCopy(resolver, videoUri, video.length(), videoName)
        val destinationLabel = humanReadableTreePath(treeUri, subfolderName)
        val prepared = Result(
            videoName = videoName,
            gpxName = null,
            destination = destinationLabel,
            videoPath = videoUri.toString(),
            gpxPath = null,
            sourceCleanupPending = true
        )
        beforeSourceCleanup?.invoke(prepared)
        val cleanupPending = !deleteAllSources(video)
        return prepared.copy(sourceCleanupPending = cleanupPending)
    }

    private fun destinationDocument(
        resolver: ContentResolver,
        treeUri: Uri,
        subfolderName: String?
    ): Uri {
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        return if (subfolderName.isNullOrBlank()) {
            rootDocumentUri
        } else {
            subfolderName.split('/').filter { it.isNotBlank() }.fold(rootDocumentUri) { parent, segment ->
                findOrCreateDirectory(resolver, treeUri, parent, segment)
            }
        }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create output folder: ${directory.absolutePath}")
        if (!directory.isDirectory) throw IOException("Output path is not a directory: ${directory.absolutePath}")
    }

    private fun verifyLocalDestination(file: File, expectedSize: Long, label: String) {
        if (!file.isFile || file.length() != expectedSize || file.length() <= 0L) {
            throw IOException("Move verification failed: destination $label is missing or has the wrong size")
        }
    }

    private fun verifyDocumentCopy(
        resolver: ContentResolver,
        documentUri: Uri,
        expectedSize: Long,
        displayName: String
    ) {
        var lastSize: Long? = null
        repeat(DOCUMENT_VERIFY_ATTEMPTS) { attempt ->
            lastSize = documentSize(resolver, documentUri)
            if (lastSize == expectedSize) return
            if (attempt + 1 < DOCUMENT_VERIFY_ATTEMPTS) SystemClock.sleep(DOCUMENT_VERIFY_DELAY_MS)
        }
        val actualText = lastSize?.let { ", provider reports $it bytes" }.orEmpty()
        throw IOException("Move verification failed for $displayName, expected $expectedSize bytes$actualText")
    }

    private fun documentSize(resolver: ContentResolver, documentUri: Uri): Long? {
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                null,
                null,
                null
            )
            val sizeColumn = cursor?.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE) ?: -1
            if (cursor != null && cursor.moveToFirst() && sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                return cursor.getLong(sizeColumn)
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun humanReadableTreePath(treeUri: Uri, subfolderName: String?): String {
        val resolvedRoot = DocumentTreePathResolver.resolve(context, treeUri)
        if (resolvedRoot != null) {
            return if (subfolderName.isNullOrBlank()) resolvedRoot.absolutePath else File(resolvedRoot, subfolderName).absolutePath
        }
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (!documentId.isNullOrBlank()) {
            val separator = documentId.indexOf(':')
            val volumeId = if (separator >= 0) documentId.substring(0, separator) else documentId
            val relativePath = if (separator >= 0) documentId.substring(separator + 1) else ""
            val storageRoot = if (volumeId.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volumeId"
            return listOf(storageRoot, relativePath, subfolderName.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString("/")
                .replace(Regex("/{2,}"), "/")
        }
        return if (subfolderName.isNullOrBlank()) "Selected output folder" else "Selected output folder/$subfolderName"
    }

    private fun createAndCopy(
        resolver: ContentResolver,
        parentUri: Uri,
        mimeType: String,
        displayName: String,
        source: File
    ): Uri {
        val snapshot = SourceSnapshot.capture(source)
        val target = DocumentsContract.createDocument(resolver, parentUri, mimeType, displayName)
            ?: throw IOException("Cannot create $displayName in selected output folder")
        val progressId = TransferProgressRegistry.begin(source.name, displayName, snapshot.length)
        try {
            val written = resolver.openOutputStream(target, "w")?.use { output ->
                FileInputStream(source).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        ensureNotInterrupted()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        TransferProgressRegistry.update(progressId, copied)
                    }
                    output.flush()
                    copied
                }
            } ?: throw IOException("Cannot open $displayName for writing")
            if (written != snapshot.length) throw IOException("Incomplete copy of $displayName ($written/${snapshot.length} bytes)")
            snapshot.assertUnchanged(source)
            TransferProgressRegistry.phase(progressId, "FINALIZING")
            return target
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw error
        } finally {
            TransferProgressRegistry.finish(progressId)
        }
    }

    private fun findOrCreateDirectory(
        resolver: ContentResolver,
        treeUri: Uri,
        parentUri: Uri,
        displayName: String
    ): Uri = findChildDocument(
        resolver,
        treeUri,
        parentUri,
        displayName,
        DocumentsContract.Document.MIME_TYPE_DIR
    ) ?: DocumentsContract.createDocument(
        resolver,
        parentUri,
        DocumentsContract.Document.MIME_TYPE_DIR,
        displayName
    ) ?: throw IOException("Cannot create output subfolder: $displayName")

    private fun findChildDocument(
        resolver: ContentResolver,
        treeUri: Uri,
        parentUri: Uri,
        displayName: String,
        requiredMimeType: String? = null
    ): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parentUri))
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ), null, null, null
            )
            val idColumn = cursor?.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID) ?: -1
            val nameColumn = cursor?.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: -1
            val mimeColumn = cursor?.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE) ?: -1
            while (cursor != null && cursor.moveToNext()) {
                if (idColumn < 0 || nameColumn < 0) continue
                if (!cursor.getString(nameColumn).equals(displayName, ignoreCase = true)) continue
                if (requiredMimeType != null && (mimeColumn < 0 || cursor.getString(mimeColumn) != requiredMimeType)) continue
                return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun childNames(resolver: ContentResolver, treeUri: Uri, parentUri: Uri): Set<String> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parentUri))
        val names = mutableSetOf<String>()
        resolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) if (index >= 0) names += cursor.getString(index).lowercase()
        }
        return names
    }

    private fun chooseBaseName(original: String, videoExtension: String, exists: (String) -> Boolean): String {
        var index = 0
        while (true) {
            val base = if (index == 0) original else "$original ($index)"
            if (!exists("$base.$videoExtension") && !exists("$base.gpx")) return base
            index++
        }
    }

    private fun copyFileAtomically(source: File, destination: File) {
        val snapshot = SourceSnapshot.capture(source)
        val temporary = File(destination.parentFile, ".${destination.name}.part")
        if (temporary.exists() && !temporary.delete()) throw IOException("Cannot remove stale temporary file ${temporary.name}")
        val progressId = TransferProgressRegistry.begin(source.name, destination.name, snapshot.length)
        try {
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        ensureNotInterrupted()
                        val read = input.read(buffer)
                        if (read < 0) break
                        sourceDigest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        copied += read
                        TransferProgressRegistry.update(progressId, copied)
                    }
                    output.fd.sync()
                    if (copied != snapshot.length) throw IOException("Incomplete copy of ${source.name}")
                }
            }
            snapshot.assertUnchanged(source)
            TransferProgressRegistry.phase(progressId, "VERIFYING")
            val destinationDigest = sha256(temporary)
            if (!sourceDigest.digest().contentEquals(destinationDigest)) throw IOException("Copy verification failed for ${source.name}")
            TransferProgressRegistry.phase(progressId, "FINALIZING")
            if (!temporary.renameTo(destination)) throw IOException("Cannot finalize ${destination.name}")
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            TransferProgressRegistry.finish(progressId)
        }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                ensureNotInterrupted()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun deleteAllSources(vararg files: File): Boolean {
        var allDeleted = true
        files.forEach { file ->
            if (file.exists() && !file.delete()) allDeleted = false
        }
        return allDeleted && files.none { it.exists() }
    }

    private fun ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Transfer cancelled")
    }

    private data class SourceSnapshot(val length: Long, val modifiedAt: Long) {
        fun assertUnchanged(file: File) {
            if (!file.isFile || file.length() != length || file.lastModified() != modifiedAt) {
                throw IOException("Source changed during transfer: ${file.name}")
            }
        }

        companion object {
            fun capture(file: File): SourceSnapshot {
                if (!file.isFile) throw IOException("Source does not exist: ${file.absolutePath}")
                return SourceSnapshot(file.length(), file.lastModified())
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 1024 * 1024
        private const val DOCUMENT_VERIFY_ATTEMPTS = 12
        private const val DOCUMENT_VERIFY_DELAY_MS = 250L
    }
}
