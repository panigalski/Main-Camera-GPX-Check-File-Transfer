package com.labpano.gpxextractor.output

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import com.labpano.gpxextractor.api.StorageWriteAlertRegistry
import com.labpano.gpxextractor.api.TransferProgressRegistry
import com.labpano.gpxextractor.ui.DocumentTreePathResolver
import com.labpano.gpxextractor.util.StorageAccessCoordinator
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

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

        val directRoot = directFilesystemRoot(outputDirectory, outputTreeUri, subfolderName)
        if (directRoot != null && isAlreadyInDirectory(video, directRoot) && isAlreadyInDirectory(gpx, directRoot)) {
            return@withWrite adoptPairInPlace(video, gpx, directRoot, beforeSourceCleanup)
        }

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

        val directRoot = directFilesystemRoot(outputDirectory, outputTreeUri, subfolderName)
        if (directRoot != null && isAlreadyInDirectory(video, directRoot)) {
            return@withWrite adoptSingleInPlace(video, directRoot, beforeSourceCleanup)
        }

        if (outputTreeUri != null) moveSingleToTree(video, outputTreeUri, subfolderName, beforeSourceCleanup)
        else {
            val root = requireNotNull(outputDirectory)
            val destination = if (subfolderName.isNullOrBlank()) root else File(root, subfolderName)
            moveSingleToDirectory(video, destination, beforeSourceCleanup)
        }
    }

    private fun directFilesystemRoot(
        outputDirectory: File?,
        outputTreeUri: Uri?,
        subfolderName: String?
    ): File? {
        if (!subfolderName.isNullOrBlank()) return null
        return outputDirectory ?: outputTreeUri?.let { DocumentTreePathResolver.resolve(context, it) }
    }

    private fun isAlreadyInDirectory(file: File, directory: File): Boolean =
        file.parentFile?.let { OutputLayoutPolicy.sameDirectory(it, directory) } ?: false

    private fun adoptPairInPlace(
        video: File,
        gpx: File,
        directory: File,
        beforeSourceCleanup: ((Result) -> Unit)?
    ): Result {
        verifyLocalVideoDestination(video, video.length(), video)
        verifyLocalDestination(gpx, gpx.length(), "GPX")
        val result = Result(
            videoName = video.name,
            gpxName = gpx.name,
            destination = directory.absolutePath,
            videoPath = video.absolutePath,
            gpxPath = gpx.absolutePath,
            sourceCleanupPending = false
        )
        beforeSourceCleanup?.invoke(result)
        return result
    }

    private fun adoptSingleInPlace(
        video: File,
        directory: File,
        beforeSourceCleanup: ((Result) -> Unit)?
    ): Result {
        verifyLocalVideoDestination(video, video.length(), video)
        val result = Result(
            videoName = video.name,
            gpxName = null,
            destination = directory.absolutePath,
            videoPath = video.absolutePath,
            gpxPath = null,
            sourceCleanupPending = false
        )
        beforeSourceCleanup?.invoke(result)
        return result
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
        ensureDirectoryForVideo(directory, video)
        val videoExtension = video.extension.ifBlank { "mp4" }

        val exactVideo = File(directory, "${video.nameWithoutExtension}.$videoExtension")
        val exactGpx = File(directory, "${video.nameWithoutExtension}.gpx")
        val exactVideoMatches = exactVideo.isFile && sameFileContent(video, exactVideo)
        val exactGpxMatches = exactGpx.isFile && sameFileContent(gpx, exactGpx)

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
                copyVideoFileAtomically(video, targetVideo)
            }
            else -> {
                val base = chooseBaseName(video.nameWithoutExtension, videoExtension) { name -> File(directory, name).exists() }
                targetVideo = File(directory, "$base.$videoExtension")
                targetGpx = File(directory, "$base.gpx")
                copyVideoFileAtomically(video, targetVideo)
                try {
                    copyFileAtomically(gpx, targetGpx)
                } catch (error: Exception) {
                    targetVideo.delete()
                    throw error
                }
            }
        }

        verifyLocalVideoDestination(targetVideo, video.length(), video)
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
        ensureDirectoryForVideo(directory, video)
        val extension = video.extension.ifBlank { "mp4" }
        val exact = File(directory, "${video.nameWithoutExtension}.$extension")
        val target = if (exact.isFile && sameFileContent(video, exact)) {
            exact
        } else {
            val base = chooseBaseName(video.nameWithoutExtension, extension) { name -> File(directory, name).exists() }
            File(directory, "$base.$extension").also { copyVideoFileAtomically(video, it) }
        }
        verifyLocalVideoDestination(target, video.length(), video)
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
        val destinationDocumentUri = destinationDocumentForVideo(resolver, treeUri, subfolderName, video)
        val videoExtension = video.extension.ifBlank { "mp4" }
        val exactVideoName = "${video.nameWithoutExtension}.$videoExtension"
        val exactGpxName = "${video.nameWithoutExtension}.gpx"
        val exactVideoUri = findChildDocument(resolver, treeUri, destinationDocumentUri, exactVideoName)
        val exactGpxUri = findChildDocument(resolver, treeUri, destinationDocumentUri, exactGpxName)
        val exactVideoMatches = exactVideoUri != null && documentMatchesSource(resolver, exactVideoUri, video)
        val exactGpxMatches = exactGpxUri != null && documentMatchesSource(resolver, exactGpxUri, gpx)

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
                videoUri = createAndCopyVideo(resolver, destinationDocumentUri, treeUri, subfolderName, videoName, video)
                gpxUri = requireNotNull(exactGpxUri)
            }
            else -> {
                val names = childNames(resolver, treeUri, destinationDocumentUri)
                val base = chooseBaseName(video.nameWithoutExtension, videoExtension) { names.contains(it.lowercase()) }
                videoName = "$base.$videoExtension"
                gpxName = "$base.gpx"
                videoUri = createAndCopyVideo(resolver, destinationDocumentUri, treeUri, subfolderName, videoName, video)
                gpxUri = try {
                    createAndCopy(resolver, destinationDocumentUri, "application/gpx+xml", gpxName, gpx)
                } catch (error: Exception) {
                    runCatching { DocumentsContract.deleteDocument(resolver, videoUri) }
                    throw error
                }
            }
        }

        verifyDocumentVideoCopy(resolver, videoUri, video.length(), videoName, treeUri, subfolderName, video)
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
        val destinationDocumentUri = destinationDocumentForVideo(resolver, treeUri, subfolderName, video)
        val extension = video.extension.ifBlank { "mp4" }
        val exactName = "${video.nameWithoutExtension}.$extension"
        val exactUri = findChildDocument(resolver, treeUri, destinationDocumentUri, exactName)
        val reusable = exactUri != null && documentMatchesSource(resolver, exactUri, video)
        val videoName: String
        val videoUri: Uri
        if (reusable) {
            videoName = exactName
            videoUri = requireNotNull(exactUri)
        } else {
            val names = childNames(resolver, treeUri, destinationDocumentUri)
            val base = chooseBaseName(video.nameWithoutExtension, extension) { names.contains(it.lowercase()) }
            videoName = "$base.$extension"
            videoUri = createAndCopyVideo(resolver, destinationDocumentUri, treeUri, subfolderName, videoName, video)
        }
        verifyDocumentVideoCopy(resolver, videoUri, video.length(), videoName, treeUri, subfolderName, video)
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

    private fun ensureDirectoryForVideo(directory: File, video: File) {
        try {
            ensureDirectory(directory)
        } catch (error: Throwable) {
            recordVideoWriteFailure(
                storageType = storageTypeForDirectory(directory),
                videoName = video.name,
                destination = directory.absolutePath,
                operation = "PREPARE_OUTPUT_FOLDER",
                error = error
            )
            throw error
        }
    }

    private fun copyVideoFileAtomically(source: File, destination: File) {
        try {
            copyFileAtomically(source, destination)
        } catch (error: Throwable) {
            recordVideoWriteFailure(
                storageType = storageTypeForDirectory(destination.parentFile ?: destination),
                videoName = source.name,
                destination = destination.absolutePath,
                operation = "WRITE_MP4",
                error = error
            )
            throw error
        }
    }

    private fun verifyLocalVideoDestination(destination: File, expectedSize: Long, source: File) {
        try {
            verifyLocalDestination(destination, expectedSize, "video")
        } catch (error: Throwable) {
            recordVideoWriteFailure(
                storageType = storageTypeForDirectory(destination.parentFile ?: destination),
                videoName = source.name,
                destination = destination.absolutePath,
                operation = "VERIFY_MP4",
                error = error
            )
            throw error
        }
    }

    private fun destinationDocumentForVideo(
        resolver: ContentResolver,
        treeUri: Uri,
        subfolderName: String?,
        video: File
    ): Uri {
        return try {
            destinationDocument(resolver, treeUri, subfolderName)
        } catch (error: Throwable) {
            recordVideoWriteFailure(
                storageType = storageTypeForTree(treeUri),
                videoName = video.name,
                destination = safeTreeLabel(treeUri, subfolderName),
                operation = "PREPARE_OUTPUT_FOLDER",
                error = error
            )
            throw error
        }
    }

    private fun createAndCopyVideo(
        resolver: ContentResolver,
        parentUri: Uri,
        treeUri: Uri,
        subfolderName: String?,
        displayName: String,
        source: File
    ): Uri {
        return try {
            createAndCopy(resolver, parentUri, "video/mp4", displayName, source)
        } catch (error: Throwable) {
            recordVideoWriteFailure(
                storageType = storageTypeForTree(treeUri),
                videoName = source.name,
                destination = safeTreeLabel(treeUri, subfolderName) + "/" + displayName,
                operation = "WRITE_MP4",
                error = error
            )
            throw error
        }
    }

    private fun verifyDocumentVideoCopy(
        resolver: ContentResolver,
        documentUri: Uri,
        expectedSize: Long,
        displayName: String,
        treeUri: Uri,
        subfolderName: String?,
        source: File
    ) {
        try {
            verifyDocumentCopy(resolver, documentUri, expectedSize, displayName)
        } catch (error: Throwable) {
            recordVideoWriteFailure(
                storageType = storageTypeForTree(treeUri),
                videoName = source.name,
                destination = safeTreeLabel(treeUri, subfolderName) + "/" + displayName,
                operation = "VERIFY_MP4",
                error = error
            )
            throw error
        }
    }

    private fun recordVideoWriteFailure(
        storageType: String,
        videoName: String,
        destination: String,
        operation: String,
        error: Throwable
    ) {
        if (!isDestinationWriteFailure(error)) return
        StorageWriteAlertRegistry.recordFailure(
            context = context,
            storageType = storageType,
            videoName = videoName,
            destination = destination,
            operation = operation,
            error = error
        )
    }

    private fun isDestinationWriteFailure(error: Throwable): Boolean {
        if (error is InterruptedException) return false
        val message = error.message.orEmpty().lowercase()
        if (message.contains("source changed during transfer") || message.contains("source does not exist")) return false
        return true
    }

    private fun storageTypeForDirectory(directory: File): String {
        if (runCatching { Environment.isExternalStorageRemovable(directory) }.getOrDefault(false)) {
            return "EXTERNAL"
        }
        val path = runCatching { directory.canonicalPath }.getOrElse { directory.absolutePath }
        val primary = runCatching { Environment.getExternalStorageDirectory().canonicalPath }
            .getOrElse { Environment.getExternalStorageDirectory().absolutePath }
        return when {
            path == primary || path.startsWith(primary + File.separator) -> "INTERNAL"
            path.startsWith("/storage/") && !path.startsWith("/storage/emulated/") -> "EXTERNAL"
            path.startsWith("/mnt/media_rw/") -> "EXTERNAL"
            else -> "INTERNAL"
        }
    }

    private fun storageTypeForTree(treeUri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
        val volumeId = documentId.substringBefore(':')
        return when {
            volumeId.equals("primary", ignoreCase = true) -> "INTERNAL"
            documentId.contains("/storage/emulated/", ignoreCase = true) -> "INTERNAL"
            volumeId.isNotBlank() -> "EXTERNAL"
            else -> "UNKNOWN"
        }
    }

    private fun safeTreeLabel(treeUri: Uri, subfolderName: String?): String =
        runCatching { humanReadableTreePath(treeUri, subfolderName) }
            .getOrElse { treeUri.toString() }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create output folder: ${directory.absolutePath}")
        if (!directory.isDirectory) throw IOException("Output path is not a directory: ${directory.absolutePath}")
    }


    private fun sameFileContent(source: File, destination: File): Boolean {
        if (!source.isFile || !destination.isFile || source.length() <= 0L || source.length() != destination.length()) {
            return false
        }
        if (runCatching { source.canonicalPath == destination.canonicalPath }.getOrDefault(false)) return true
        return runCatching { sampledLocalContentMatches(source, destination) }.getOrDefault(false)
    }

    /**
     * Reusing an already-existing SAF document is optional. Only reuse it when bounded random-access
     * samples match; providers which cannot seek simply return false and we create a unique copy.
     * This avoids a second full multi-gigabyte MP4 read just to decide whether a name can be reused.
     */
    private fun documentMatchesSource(resolver: ContentResolver, documentUri: Uri, source: File): Boolean {
        if (!source.isFile || source.length() <= 0L) return false
        if (documentSize(resolver, documentUri) != source.length()) return false
        return runCatching { sampledDocumentContentMatches(resolver, documentUri, source) }.getOrDefault(false)
    }

    private fun sampledLocalContentMatches(source: File, destination: File): Boolean {
        val length = source.length()
        if (length != destination.length() || length <= 0L) return false
        RandomAccessFile(source, "r").use { sourceFile ->
            RandomAccessFile(destination, "r").use { destinationFile ->
                for ((position, count) in verificationSamples(length)) {
                    ensureNotInterrupted()
                    val sourceBytes = ByteArray(count)
                    val destinationBytes = ByteArray(count)
                    sourceFile.seek(position)
                    destinationFile.seek(position)
                    sourceFile.readFully(sourceBytes)
                    destinationFile.readFully(destinationBytes)
                    if (!sourceBytes.contentEquals(destinationBytes)) return false
                }
            }
        }
        return true
    }

    private fun sampledDocumentContentMatches(
        resolver: ContentResolver,
        documentUri: Uri,
        source: File
    ): Boolean {
        val length = source.length()
        val descriptor = resolver.openFileDescriptor(documentUri, "r") ?: return false
        descriptor.use { pfd ->
            RandomAccessFile(source, "r").use { sourceFile ->
                // ParcelFileDescriptor owns the underlying fd. Do not independently close the
                // FileInputStream wrapper because some Android 7 providers reject a double close.
                val destinationInput = FileInputStream(pfd.fileDescriptor)
                val channel = destinationInput.channel
                for ((position, count) in verificationSamples(length)) {
                    ensureNotInterrupted()
                    val sourceBytes = ByteArray(count)
                    val destinationBytes = ByteArray(count)
                    sourceFile.seek(position)
                    sourceFile.readFully(sourceBytes)
                    channel.position(position)
                    var offset = 0
                    while (offset < count) {
                        val read = destinationInput.read(destinationBytes, offset, count - offset)
                        if (read < 0) return false
                        offset += read
                    }
                    if (!sourceBytes.contentEquals(destinationBytes)) return false
                }
            }
        }
        return true
    }

    private fun verificationSamples(length: Long): List<Pair<Long, Int>> {
        val count = minOf(VERIFY_SAMPLE_BYTES.toLong(), length).toInt()
        if (count <= 0) return emptyList()
        val maxStart = (length - count).coerceAtLeast(0L)
        return linkedSetOf(
            0L,
            maxStart / 3L,
            (maxStart * 2L) / 3L,
            maxStart
        ).map { it to count }
    }

    private fun verifyLocalDestination(file: File, expectedSize: Long, label: String) {
        if (!file.isFile || file.length() != expectedSize || expectedSize < 0L) {
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
            TransferProgressRegistry.phase(progressId, "VERIFYING")
            // The copy loop already proved exactly N source bytes were written and the source did
            // not change. SAF verification is therefore bounded to provider-reported destination
            // size instead of re-reading the entire MP4 a second time.
            verifyDocumentCopy(resolver, target, snapshot.length, displayName)
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
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
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
                    output.fd.sync()
                    if (copied != snapshot.length) throw IOException("Incomplete copy of ${source.name}")
                }
            }
            snapshot.assertUnchanged(source)
            TransferProgressRegistry.phase(progressId, "VERIFYING")
            verifyLocalDestination(temporary, snapshot.length, source.name)
            if (snapshot.length > 0L && !sampledLocalContentMatches(source, temporary)) {
                throw IOException("Copy verification failed for ${source.name}")
            }
            TransferProgressRegistry.phase(progressId, "FINALIZING")
            if (!temporary.renameTo(destination)) throw IOException("Cannot finalize ${destination.name}")
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            TransferProgressRegistry.finish(progressId)
        }
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
        private const val VERIFY_SAMPLE_BYTES = 256 * 1024
    }
}
