package com.labpano.gpxextractor.report

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque

/** Reads recent report lines without walking an indefinitely growing cumulative TXT file. */
object ReportTailReader {
    fun lastNonBlankLines(file: File, limit: Int): List<String> {
        if (limit <= 0 || !file.isFile || file.length() <= 0L) return emptyList()
        RandomAccessFile(file, "r").use { input ->
            var position = input.length()
            var newlineCount = 0
            val chunks = ArrayDeque<ByteArray>()
            var totalBytes = 0

            while (position > 0L && newlineCount <= limit) {
                val chunkSize = minOf(CHUNK_BYTES.toLong(), position).toInt()
                position -= chunkSize
                input.seek(position)
                val chunk = ByteArray(chunkSize)
                input.readFully(chunk)
                newlineCount += chunk.count { it == '\n'.code.toByte() }
                chunks.addFirst(chunk)
                totalBytes += chunkSize
            }

            val combined = ByteArrayOutputStream(totalBytes)
            chunks.forEach { combined.write(it) }
            var bytes = combined.toByteArray()

            // If scanning stopped before byte zero, the first chunk begins inside an older line.
            if (position > 0L) {
                val firstNewline = bytes.indexOf('\n'.code.toByte())
                bytes = if (firstNewline >= 0 && firstNewline + 1 < bytes.size) {
                    bytes.copyOfRange(firstNewline + 1, bytes.size)
                } else {
                    ByteArray(0)
                }
            }

            return bytes.toString(Charsets.UTF_8)
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
                .takeLast(limit)
        }
    }

    fun recentBytesContain(file: File, marker: String, maxBytes: Long): Boolean {
        if (marker.isEmpty() || maxBytes <= 0L || !file.isFile || file.length() <= 0L) return false
        RandomAccessFile(file, "r").use { input ->
            val bytesToRead = minOf(input.length(), maxBytes).toInt()
            val start = input.length() - bytesToRead
            input.seek(start)
            val bytes = ByteArray(bytesToRead)
            input.readFully(bytes)
            return bytes.toString(Charsets.UTF_8).contains(marker)
        }
    }

    private const val CHUNK_BYTES = 64 * 1024
}
