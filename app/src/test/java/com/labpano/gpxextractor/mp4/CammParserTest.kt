package com.labpano.gpxextractor.mp4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CammParserTest {
    private val parser = CammParser()

    @Test
    fun decodesMinimalGpsPacket() {
        val bytes = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)
            .putShort(5)
            .putDouble(52.2297)
            .putDouble(21.0122)
            .putDouble(112.5)
            .array()

        val point = parser.decodeGpsPayload(bytes, 2_500_000L, 1_700_000_000_000L)

        assertNotNull(point)
        assertEquals(52.2297, point!!.latitude, 0.0000001)
        assertEquals(21.0122, point.longitude, 0.0000001)
        assertEquals(112.5, point.altitudeMeters!!, 0.0001)
        assertEquals(1_700_000_002_500L, point.timestampMillis)
    }

    @Test
    fun ignoresFullGpsPacketWithoutFix() {
        val bytes = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)
            .putShort(6)
            .putDouble(1_400_000_000.0)
            .putInt(0)
            .putDouble(52.0)
            .putDouble(21.0)
            .putFloat(100f)
            .putFloat(1f)
            .putFloat(2f)
            .putFloat(0f)
            .putFloat(0f)
            .putFloat(0f)
            .putFloat(0f)
            .array()

        assertNull(parser.decodeGpsPayload(bytes, 0L, 0L))
    }
}
