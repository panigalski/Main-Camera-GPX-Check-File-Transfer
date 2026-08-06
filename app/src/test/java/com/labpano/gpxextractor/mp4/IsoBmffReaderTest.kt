package com.labpano.gpxextractor.mp4

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class IsoBmffReaderTest {
    @Test
    fun readsStandardBoxHeader() {
        val file = File.createTempFile("box", ".mp4")
        try {
            file.writeBytes(byteArrayOf(0, 0, 0, 12, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 1, 2, 3, 4))
            IsoBmffReader(file).use { reader ->
                val box = reader.readBoxes().single()
                assertEquals("ftyp", box.type)
                assertEquals(12L, box.size)
                assertEquals(8, box.headerSize)
            }
        } finally {
            file.delete()
        }
    }
}
