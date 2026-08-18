package com.labpano.gpxextractor.mp4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

class StreetViewTimelineTest {
    @Test
    fun mapsLeadingEmptyEditOntoMovieTimeline() {
        val movieStart = Instant.parse("2026-08-03T08:54:00Z").toEpochMilli()
        val file = buildMp4(
            movieStartMillis = movieStart,
            movieDurationMillis = 240_000L,
            sampleDeltaMillis = 1_000L,
            samples = listOf(type5(52.0), type5(52.1)),
            leadingEmptyEditMillis = 180_000L
        )

        val result = CammParser().parseDetailed(file)

        assertEquals(movieStart + 180_000L, result.points.first().timestampMillis)
        assertTrue(result.timing.overlapMillis!! > 0L)
        file.delete()
    }

    @Test
    fun correctsStableType6ClockOffsetToMovieTimeline() {
        val movieStart = Instant.parse("2026-08-03T08:57:00Z").toEpochMilli()
        val rawGpsStart = movieStart - 180_000L
        val file = buildMp4(
            movieStartMillis = movieStart,
            movieDurationMillis = 240_000L,
            sampleDeltaMillis = 1_000L,
            samples = listOf(type6(rawGpsStart, 52.0), type6(rawGpsStart + 1_000L, 52.1))
        )

        val result = CammParser().parseDetailed(file)

        assertEquals(movieStart, result.points.first().timestampMillis)
        assertEquals(180_000L, result.timing.appliedTimestampShiftMillis)
        assertTrue(result.timing.overlapMillis!! > 0L)
        file.delete()
    }


    @Test
    fun ignoresMidRecordingType6ClockJumpWhenPresentationTimelineIsContinuous() {
        val movieStart = Instant.parse("2026-08-17T14:19:44Z").toEpochMilli()
        val file = buildMp4(
            movieStartMillis = movieStart,
            movieDurationMillis = 10_000L,
            sampleDeltaMillis = 1_000L,
            samples = listOf(
                type6(movieStart, 52.0),
                type6(movieStart + 1_000L, 52.1),
                // Raw GPS clock jumps forward 15 seconds, while the CAMM PTS advances only 1 second.
                type6(movieStart + 17_000L, 52.2),
                type6(movieStart + 18_000L, 52.3)
            )
        )

        val result = CammParser().parseDetailed(file)

        assertEquals(
            listOf(movieStart, movieStart + 1_000L, movieStart + 2_000L, movieStart + 3_000L),
            result.points.map { it.timestampMillis }
        )
        assertTrue(result.timing.rawGpsClockDiscontinuityCount >= 2)
        assertTrue(result.points.zipWithNext().all { (a, b) -> b.timestampMillis - a.timestampMillis <= 1_000L })
        file.delete()
    }

    private fun buildMp4(
        movieStartMillis: Long,
        movieDurationMillis: Long,
        sampleDeltaMillis: Long,
        samples: List<ByteArray>,
        leadingEmptyEditMillis: Long = 0L
    ): File {
        require(samples.isNotEmpty())
        val macSeconds = movieStartMillis / 1_000L + MAC_TO_UNIX_EPOCH_SECONDS
        val mvhd = box("mvhd", fullBox() + uint32(macSeconds) + uint32(macSeconds) +
            uint32(1_000L) + uint32(movieDurationMillis))
        val mdhd = box("mdhd", fullBox() + uint32(macSeconds) + uint32(macSeconds) +
            uint32(1_000L) + uint32(movieDurationMillis))
        val stsd = box("stsd", fullBox() + uint32(1L) + boxHeaderOnly("camm"))
        val stszPayload = ByteArrayOutputStream().apply {
            write(fullBox())
            write(uint32(0L))
            write(uint32(samples.size.toLong()))
            samples.forEach { write(uint32(it.size.toLong())) }
        }.toByteArray()
        val stsz = box("stsz", stszPayload)
        val stsc = box("stsc", fullBox() + uint32(1L) + uint32(1L) +
            uint32(samples.size.toLong()) + uint32(1L))
        val stts = box("stts", fullBox() + uint32(1L) + uint32(samples.size.toLong()) +
            uint32(sampleDeltaMillis))
        val stco = box("stco", fullBox() + uint32(1L) + uint32(0L))
        val stbl = box("stbl", stsd + stsz + stsc + stts + stco)
        val mdia = box("mdia", mdhd + box("minf", stbl))
        val edit = if (leadingEmptyEditMillis > 0L) {
            val payload = fullBox() + uint32(2L) +
                editEntry(leadingEmptyEditMillis, -1) +
                editEntry(movieDurationMillis - leadingEmptyEditMillis, 0)
            box("edts", box("elst", payload))
        } else ByteArray(0)
        val moov = box("moov", mvhd + box("trak", edit + mdia))
        val mdat = box("mdat", samples.fold(ByteArray(0)) { acc, bytes -> acc + bytes })
        val bytes = (moov + mdat).clone()
        val stcoType = findType(bytes, "stco")
        val mdatType = findType(bytes, "mdat")
        val sampleOffset = mdatType + 4
        ByteBuffer.wrap(bytes, stcoType + 12, 4).order(ByteOrder.BIG_ENDIAN).putInt(sampleOffset)
        return File.createTempFile("street-view-timeline-", ".mp4").apply { writeBytes(bytes) }
    }

    private fun type5(latitude: Double): ByteArray = ByteBuffer.allocate(28)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(0)
        .putShort(5)
        .putDouble(latitude)
        .putDouble(21.0)
        .putDouble(100.0)
        .array()

    private fun type6(unixMillis: Long, latitude: Double): ByteArray {
        val leapSeconds = 18L
        val gpsSeconds = (unixMillis - GPS_EPOCH_UNIX_MILLIS) / 1_000.0 + leapSeconds
        return ByteBuffer.allocate(60)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)
            .putShort(6)
            .putDouble(gpsSeconds)
            .putInt(3)
            .putDouble(latitude)
            .putDouble(21.0)
            .putFloat(100f)
            .putFloat(1f)
            .putFloat(2f)
            .putFloat(0f)
            .putFloat(0f)
            .putFloat(0f)
            .putFloat(0f)
            .array()
    }

    private fun editEntry(segmentDuration: Long, mediaTime: Int): ByteArray =
        uint32(segmentDuration) + ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(mediaTime).array() +
            byteArrayOf(0, 1, 0, 0)

    private fun fullBox(): ByteArray = byteArrayOf(0, 0, 0, 0)

    private fun uint32(value: Long): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(value.toInt())
        .array()

    private fun boxHeaderOnly(type: String): ByteArray =
        uint32(8L) + type.toByteArray(Charsets.ISO_8859_1)

    private fun box(type: String, payload: ByteArray): ByteArray =
        uint32(payload.size.toLong() + 8L) + type.toByteArray(Charsets.ISO_8859_1) + payload

    private fun findType(bytes: ByteArray, type: String): Int {
        val target = type.toByteArray(Charsets.ISO_8859_1)
        for (index in 0..bytes.size - target.size) {
            if (target.indices.all { bytes[index + it] == target[it] }) return index
        }
        error("Missing box $type")
    }

    companion object {
        private const val MAC_TO_UNIX_EPOCH_SECONDS = 2_082_844_800L
        private const val GPS_EPOCH_UNIX_MILLIS = 315_964_800_000L
    }
}
