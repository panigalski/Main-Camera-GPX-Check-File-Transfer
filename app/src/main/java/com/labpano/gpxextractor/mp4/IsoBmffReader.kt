package com.labpano.gpxextractor.mp4

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

class IsoBmffReader(file: File) : Closeable {
    private val input = RandomAccessFile(file, "r")
    val length: Long get() = input.length()

    fun readBoxes(startOffset: Long = 0L, endOffset: Long = length): Sequence<IsoBox> = sequence {
        var offset = startOffset
        require(startOffset >= 0L && endOffset <= length && startOffset <= endOffset)
        while (offset + 8L <= endOffset) {
            input.seek(offset)
            val size32 = readUInt32Internal()
            val type = readTypeInternal()
            var headerSize = 8
            val size = when (size32) {
                0L -> endOffset - offset
                1L -> {
                    headerSize = 16
                    readUInt64Internal()
                }
                else -> size32
            }
            if (size < headerSize || offset + size > endOffset) {
                throw Mp4Exception("Invalid $type box at $offset: size=$size, boundary=$endOffset")
            }
            yield(IsoBox(type, offset, size, headerSize))
            if (size == 0L) break
            offset += size
        }
    }

    fun children(box: IsoBox, prefixBytes: Int = 0): Sequence<IsoBox> =
        readBoxes(box.contentOffset + prefixBytes, box.endOffset)

    fun readBytes(offset: Long, count: Int): ByteArray {
        require(count >= 0)
        if (offset < 0 || offset + count > length) throw Mp4Exception("Read outside file")
        val bytes = ByteArray(count)
        input.seek(offset)
        input.readFully(bytes)
        return bytes
    }

    fun readUInt8(offset: Long): Int {
        input.seek(offset)
        return input.readUnsignedByte()
    }

    fun readUInt16(offset: Long): Int {
        input.seek(offset)
        return input.readUnsignedShort()
    }

    fun readInt32(offset: Long): Int {
        input.seek(offset)
        return input.readInt()
    }

    fun readUInt32(offset: Long): Long {
        input.seek(offset)
        return readUInt32Internal()
    }

    fun readInt64(offset: Long): Long {
        input.seek(offset)
        return input.readLong()
    }

    fun readUInt64(offset: Long): Long {
        input.seek(offset)
        return readUInt64Internal()
    }

    fun readType(offset: Long): String {
        input.seek(offset)
        return readTypeInternal()
    }

    fun readLittleEndianBuffer(offset: Long, count: Int): ByteBuffer =
        ByteBuffer.wrap(readBytes(offset, count)).order(ByteOrder.LITTLE_ENDIAN)

    private fun readUInt32Internal(): Long = input.readInt().toLong() and 0xFFFF_FFFFL

    private fun readUInt64Internal(): Long {
        val value = input.readLong()
        if (value < 0L) throw Mp4Exception("64-bit unsigned value exceeds supported signed range")
        return value
    }

    private fun readTypeInternal(): String {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return bytes.toString(Charset.forName("ISO-8859-1"))
    }

    override fun close() = input.close()
}
