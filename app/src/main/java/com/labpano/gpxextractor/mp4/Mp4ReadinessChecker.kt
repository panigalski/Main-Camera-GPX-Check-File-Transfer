package com.labpano.gpxextractor.mp4

import java.io.File

/**
 * Performs a read-only structural preflight before CAMM parsing.
 *
 * A Pilot One MP4 may pause in size growth before its final `moov` box and final box lengths are
 * committed. Such a file is not corrupt; it is simply not ready yet.
 */
class Mp4ReadinessChecker {
    sealed class Result {
        object Ready : Result()
        data class Incomplete(val reason: String) : Result()
    }

    fun check(file: File): Result {
        if (!file.isFile) return Result.Incomplete("File is unavailable")

        val sizeBefore = file.length()
        val modifiedBefore = file.lastModified()
        if (sizeBefore < 8L) return Result.Incomplete("MP4 header is incomplete")

        val result = try {
            IsoBmffReader(file).use { reader ->
                val boxes = reader.readBoxes().toList()
                val moovCount = boxes.count { it.type == "moov" }
                val mdatCount = boxes.count { it.type == "mdat" }
                when {
                    moovCount != 1 -> Result.Incomplete("MP4 has no unique moov box")
                    mdatCount < 1 -> Result.Incomplete("MP4 has no mdat box")
                    boxes.isEmpty() -> Result.Incomplete("MP4 contains no complete boxes")
                    boxes.last().endOffset != reader.length -> Result.Incomplete("MP4 has trailing incomplete data")
                    else -> Result.Ready
                }
            }
        } catch (error: Exception) {
            Result.Incomplete(error.message ?: error.javaClass.simpleName)
        }

        val sizeAfter = file.length()
        val modifiedAfter = file.lastModified()
        return if (sizeBefore != sizeAfter || modifiedBefore != modifiedAfter) {
            Result.Incomplete("Recording changed during structural check")
        } else {
            result
        }
    }
}
