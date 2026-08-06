package com.labpano.gpxextractor.ui

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Resolves a Storage Access Framework directory URI to a filesystem directory.
 *
 * FileObserver requires a real filesystem path. Android's primary shared-storage
 * document provider exposes tree IDs such as "primary:DCIM/Videos/Stitched".
 * Removable storage IDs are matched against directories returned by
 * Context.getExternalFilesDirs().
 */
object DocumentTreePathResolver {
    fun resolve(context: Context, treeUri: Uri): File? {
        if (!DocumentsContract.isTreeUri(treeUri)) return null

        val documentId = runCatching {
            DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull() ?: return null

        val separator = documentId.indexOf(':')
        val volumeId = if (separator >= 0) documentId.substring(0, separator) else documentId
        val relativePath = if (separator >= 0) documentId.substring(separator + 1) else ""

        val root = when {
            volumeId.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory()
            else -> findRemovableStorageRoot(context, volumeId)
        } ?: return null

        return if (relativePath.isBlank()) root else File(root, relativePath)
    }

    private fun findRemovableStorageRoot(context: Context, volumeId: String): File? {
        return context.getExternalFilesDirs(null)
            .asSequence()
            .filterNotNull()
            .mapNotNull(::extractStorageRoot)
            .firstOrNull { it.name.equals(volumeId, ignoreCase = true) }
    }

    private fun extractStorageRoot(appExternalDirectory: File): File? {
        val marker = "${File.separator}Android${File.separator}data${File.separator}"
        val path = appExternalDirectory.absolutePath
        val markerIndex = path.indexOf(marker)
        if (markerIndex <= 0) return null
        return File(path.substring(0, markerIndex))
    }
}
