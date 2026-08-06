package com.labpano.gpxextractor.mp4

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Streaming ISO-BMFF/CAMM parser.
 *
 * In addition to decoding CAMM GPS packets, this parser maps CAMM media times through
 * the track edit list onto the MP4 movie timeline. That distinction is important for
 * Street View Studio, which compares the GPX timestamps with the edited video timeline.
 */
class CammParser {
    fun parse(file: File): List<GpsPoint> = parseDetailed(file).points

    fun parseDetailed(file: File): CammParseResult {
        require(file.isFile) { "MP4 does not exist: ${file.absolutePath}" }
        IsoBmffReader(file).use { reader ->
            val topLevel = reader.readBoxes().toList()
            val moov = topLevel.singleOrNull { it.type == "moov" }
                ?: throw Mp4Exception("MP4 has no unique moov box")
            if (topLevel.none { it.type == "mdat" }) throw Mp4Exception("MP4 has no mdat box")

            val moovChildren = reader.children(moov).toList()
            val movieHeader = moovChildren.firstOrNull { it.type == "mvhd" }
                ?.let { parseMovieHeader(reader, it) }
            val trakBoxes = moovChildren.filter { it.type == "trak" }
            val videoTrack = trakBoxes.mapNotNull { parseVideoTrackHeader(reader, it) }
                .maxByOrNull { it.durationUs }

            val tracks = trakBoxes
                .mapNotNull { parseCammTrack(reader, it, movieHeader?.timescale) }

            if (tracks.isEmpty()) throw Mp4Exception("No CAMM track found")
            if (tracks.size > 1) throw Mp4Exception("Multiple CAMM tracks found: ${tracks.size}")

            val track = tracks.single()
            if (track.samples.isEmpty()) throw Mp4Exception("CAMM track contains no samples")

            val anchor = resolveVideoAnchor(file, movieHeader, videoTrack, track)
            val decoded = ArrayList<DecodedGps>()
            var malformedGpsPackets = 0
            track.samples.forEach { sample ->
                val item = try {
                    decodeGpsSample(reader, sample, anchor.startMillis)
                } catch (_: IllegalArgumentException) {
                    malformedGpsPackets++
                    null
                }
                if (item != null) decoded += item
            }

            if (decoded.isEmpty()) {
                val suffix = if (malformedGpsPackets > 0) {
                    "; malformed GPS packets=$malformedGpsPackets"
                } else ""
                throw Mp4Exception("CAMM track has no GPS packets (types 5 or 6)$suffix")
            }

            val synchronized = synchronizeToVideoTimeline(decoded, anchor)
            val points = synchronized.points
                .filter { it.latitude.isFinite() && it.longitude.isFinite() }
                .sortedBy { it.timestampMillis }
                .distinctBy { Triple(it.timestampMillis, it.latitude, it.longitude) }

            if (points.isEmpty()) throw Mp4Exception("CAMM GPS points were invalid after timestamp synchronization")

            val videoEnd = anchor.durationUs.takeIf { it > 0L }
                ?.let { anchor.startMillis + it / 1000L }
            val gpxStart = points.first().timestampMillis
            val gpxEnd = points.last().timestampMillis
            val overlap = videoEnd?.let { calculateOverlapMillis(anchor.startMillis, it, gpxStart, gpxEnd) }

            return CammParseResult(
                points = points,
                timing = GpsTimingDiagnostics(
                    anchorSource = anchor.source,
                    videoStartMillis = anchor.startMillis,
                    videoEndMillis = videoEnd,
                    gpxStartMillis = gpxStart,
                    gpxEndMillis = gpxEnd,
                    appliedTimestampShiftMillis = synchronized.appliedShiftMillis,
                    overlapMillis = overlap
                )
            )
        }
    }

    internal fun decodeGpsPayload(
        bytes: ByteArray,
        presentationTimeUs: Long,
        fallbackStartMillis: Long
    ): GpsPoint? = decodeGpsPayloadDetailed(bytes, presentationTimeUs, fallbackStartMillis)?.point

    private data class DecodedGps(
        val point: GpsPoint,
        val packetType: Int,
        val presentationTimeUs: Long
    )

    private data class SyncResult(
        val points: List<GpsPoint>,
        val appliedShiftMillis: Long
    )

    private data class VideoAnchor(
        val startMillis: Long,
        val durationUs: Long,
        val source: String,
        val authoritative: Boolean
    )

    private data class MovieHeader(
        val creationTimeUnixMillis: Long?,
        val timescale: Long,
        val duration: Long
    ) {
        val durationUs: Long
            get() = if (timescale > 0L) scaleValueToMicros(duration, timescale) else 0L
    }

    private data class VideoTrackHeader(
        val creationTimeUnixMillis: Long?,
        val durationUs: Long
    )

    private data class EditEntry(
        val segmentDurationMovieTicks: Long,
        val mediaTimeTicks: Long,
        val mediaRateInteger: Int,
        val mediaRateFraction: Int
    )

    private fun decodeGpsPayloadDetailed(
        bytes: ByteArray,
        presentationTimeUs: Long,
        fallbackStartMillis: Long
    ): DecodedGps? {
        if (bytes.size < 4) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.short // reserved
        val type = buffer.short.toInt() and 0xFFFF
        val point = when (type) {
            5 -> decodeMinimalGps(buffer, presentationTimeUs, fallbackStartMillis)
            6 -> decodeFullGps(buffer)
            else -> null
        } ?: return null
        return DecodedGps(point, type, presentationTimeUs)
    }

    private fun decodeGpsSample(
        reader: IsoBmffReader,
        sample: CammSample,
        fallbackStartMillis: Long
    ): DecodedGps? {
        if (sample.size <= 0 || sample.size > MAX_CAMM_SAMPLE_SIZE) {
            throw IllegalArgumentException("Invalid CAMM sample size ${sample.size}")
        }
        return decodeGpsPayloadDetailed(
            reader.readBytes(sample.fileOffset, sample.size),
            sample.presentationTimeUs,
            fallbackStartMillis
        )
    }

    private fun decodeMinimalGps(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        fallbackStartMillis: Long
    ): GpsPoint {
        require(buffer.remaining() >= 24) { "CAMM type 5 packet is truncated" }
        val latitude = buffer.double
        val longitude = buffer.double
        val altitude = buffer.double
        validateCoordinates(latitude, longitude)
        return GpsPoint(
            timestampMillis = fallbackStartMillis + presentationTimeUs / 1000L,
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitude.takeIf { it.isFinite() }
        )
    }

    private fun decodeFullGps(buffer: ByteBuffer): GpsPoint? {
        require(buffer.remaining() >= 56) { "CAMM type 6 packet is truncated" }
        val gpsEpochSeconds = buffer.double
        val fixType = buffer.int
        val latitude = buffer.double
        val longitude = buffer.double
        val altitude = buffer.float.toDouble()
        val horizontalAccuracy = buffer.float.toDouble()
        val verticalAccuracy = buffer.float.toDouble()
        buffer.float // velocity east
        buffer.float // velocity north
        buffer.float // velocity up
        buffer.float // speed accuracy

        if (fixType == 0) return null
        validateCoordinates(latitude, longitude)
        require(gpsEpochSeconds.isFinite() && gpsEpochSeconds >= 0.0) {
            "Invalid GPS epoch time"
        }
        return GpsPoint(
            timestampMillis = gpsSecondsToUnixMillis(gpsEpochSeconds),
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitude.takeIf { it.isFinite() },
            horizontalAccuracyMeters = horizontalAccuracy.takeIf { it.isFinite() && it >= 0.0 },
            verticalAccuracyMeters = verticalAccuracy.takeIf { it.isFinite() && it >= 0.0 }
        )
    }

    private fun validateCoordinates(latitude: Double, longitude: Double) {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude $latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude $longitude" }
    }

    private fun parseCammTrack(
        reader: IsoBmffReader,
        trak: IsoBox,
        movieTimescale: Long?
    ): CammTrack? {
        val trakChildren = reader.children(trak).toList()
        val tkhd = trakChildren.firstOrNull { it.type == "tkhd" }
        val mdia = trakChildren.firstOrNull { it.type == "mdia" } ?: return null
        val mdiaChildren = reader.children(mdia).toList()
        val mdhd = mdiaChildren.firstOrNull { it.type == "mdhd" }
            ?: throw Mp4Exception("Track is missing mdhd")
        val minf = mdiaChildren.firstOrNull { it.type == "minf" } ?: return null
        val stbl = reader.children(minf).firstOrNull { it.type == "stbl" } ?: return null
        val boxes = reader.children(stbl).associateBy { it.type }
        val stsd = boxes["stsd"] ?: return null
        if (!containsCammSampleEntry(reader, stsd)) return null

        val mediaHeader = parseMediaHeader(reader, mdhd)
        val trackId = tkhd?.let { parseTrackId(reader, it) } ?: 0L
        val sampleSizes = boxes["stsz"]?.let { parseStsz(reader, it) }
            ?: boxes["stz2"]?.let { parseStz2(reader, it) }
            ?: throw Mp4Exception("CAMM track has neither stsz nor stz2")
        val chunkOffsets = boxes["stco"]?.let { parseStco(reader, it) }
            ?: boxes["co64"]?.let { parseCo64(reader, it) }
            ?: throw Mp4Exception("CAMM track has neither stco nor co64")
        val sampleToChunk = boxes["stsc"]?.let { parseStsc(reader, it) }
            ?: throw Mp4Exception("CAMM track has no stsc")
        val decodeTimes = boxes["stts"]?.let { parseStts(reader, it, sampleSizes.size) }
            ?: throw Mp4Exception("CAMM track has no stts")
        val compositionOffsets = boxes["ctts"]?.let { parseCtts(reader, it, sampleSizes.size) }
            ?: LongArray(sampleSizes.size)
        val edits = parseEditList(reader, trakChildren, movieTimescale)

        val samples = expandSamples(
            sampleSizes = sampleSizes,
            chunkOffsets = chunkOffsets,
            sampleToChunk = sampleToChunk,
            decodeTimes = decodeTimes.first,
            sampleDurations = decodeTimes.second,
            compositionOffsets = compositionOffsets,
            timescale = mediaHeader.timescale,
            movieTimescale = movieTimescale,
            editEntries = edits,
            fileLength = reader.length
        )

        return CammTrack(
            trackId = trackId,
            timescale = mediaHeader.timescale,
            creationTimeUnixMillis = mediaHeader.creationTimeUnixMillis,
            durationUs = scaleToMicros(mediaHeader.duration, mediaHeader.timescale),
            samples = samples
        )
    }

    private fun parseMovieHeader(reader: IsoBmffReader, mvhd: IsoBox): MovieHeader {
        val version = reader.readUInt8(mvhd.contentOffset)
        val cursor = mvhd.contentOffset + 4
        val creation: Long
        val timescale: Long
        val duration: Long
        when (version) {
            0 -> {
                creation = reader.readUInt32(cursor)
                timescale = reader.readUInt32(cursor + 8)
                duration = reader.readUInt32(cursor + 12)
            }
            1 -> {
                creation = reader.readUInt64(cursor)
                timescale = reader.readUInt32(cursor + 16)
                duration = reader.readUInt64(cursor + 20)
            }
            else -> throw Mp4Exception("Unsupported mvhd version $version")
        }
        if (timescale <= 0L) throw Mp4Exception("Invalid movie timescale $timescale")
        return MovieHeader(macSecondsToUnixMillis(creation), timescale, duration)
    }

    private fun parseVideoTrackHeader(reader: IsoBmffReader, trak: IsoBox): VideoTrackHeader? {
        val trakChildren = reader.children(trak).toList()
        val mdia = trakChildren.firstOrNull { it.type == "mdia" } ?: return null
        val mdiaChildren = reader.children(mdia).toList()
        val hdlr = mdiaChildren.firstOrNull { it.type == "hdlr" } ?: return null
        if (hdlr.contentSize < 12L || reader.readType(hdlr.contentOffset + 8L) != "vide") return null
        val mdhd = mdiaChildren.firstOrNull { it.type == "mdhd" } ?: return null
        val header = parseMediaHeader(reader, mdhd)
        return VideoTrackHeader(
            creationTimeUnixMillis = header.creationTimeUnixMillis,
            durationUs = scaleToMicros(header.duration, header.timescale)
        )
    }

    private fun parseEditList(
        reader: IsoBmffReader,
        trakChildren: List<IsoBox>,
        movieTimescale: Long?
    ): List<EditEntry> {
        if (movieTimescale == null || movieTimescale <= 0L) return emptyList()
        val edts = trakChildren.firstOrNull { it.type == "edts" } ?: return emptyList()
        val elst = reader.children(edts).firstOrNull { it.type == "elst" } ?: return emptyList()
        if (elst.contentSize < 8L) throw Mp4Exception("Truncated elst")
        val version = reader.readUInt8(elst.contentOffset)
        val count = checkedInt(reader.readUInt32(elst.contentOffset + 4L), "elst entry count")
        val entrySize = when (version) {
            0 -> 12L
            1 -> 20L
            else -> throw Mp4Exception("Unsupported elst version $version")
        }
        if (elst.contentOffset + 8L + count * entrySize > elst.endOffset) {
            throw Mp4Exception("Truncated elst entries")
        }
        return List(count) { index ->
            val base = elst.contentOffset + 8L + index * entrySize
            val segmentDuration = if (version == 0) reader.readUInt32(base) else reader.readUInt64(base)
            val mediaTime = if (version == 0) reader.readInt32(base + 4L).toLong() else reader.readInt64(base + 8L)
            val rateOffset = if (version == 0) base + 8L else base + 16L
            val rateInteger = reader.readUInt16(rateOffset).toShort().toInt()
            val rateFraction = reader.readUInt16(rateOffset + 2L).toShort().toInt()
            EditEntry(segmentDuration, mediaTime, rateInteger, rateFraction)
        }
    }

    private fun resolveVideoAnchor(
        file: File,
        movieHeader: MovieHeader?,
        videoTrack: VideoTrackHeader?,
        cammTrack: CammTrack
    ): VideoAnchor {
        val durationUs = movieHeader?.durationUs?.takeIf { it in 1L..MAX_VIDEO_DURATION_US }
            ?: videoTrack?.durationUs?.takeIf { it in 1L..MAX_VIDEO_DURATION_US }
            ?: cammTrack.durationUs.takeIf { it in 1L..MAX_VIDEO_DURATION_US }
            ?: 0L

        val videoCreation = videoTrack?.creationTimeUnixMillis?.takeIf(::isPlausibleCaptureTime)
        if (videoCreation != null) {
            return VideoAnchor(videoCreation, durationUs, "MP4 video track header (mdhd)", true)
        }
        val movieCreation = movieHeader?.creationTimeUnixMillis?.takeIf(::isPlausibleCaptureTime)
        if (movieCreation != null) {
            return VideoAnchor(movieCreation, durationUs, "MP4 movie header (mvhd)", true)
        }
        val filenameTime = parseTimestampFromFilename(file.nameWithoutExtension)
        if (filenameTime != null) {
            return VideoAnchor(filenameTime, durationUs, "recording filename", false)
        }
        val cammCreation = cammTrack.creationTimeUnixMillis?.takeIf(::isPlausibleCaptureTime)
        if (cammCreation != null) {
            return VideoAnchor(cammCreation, durationUs, "CAMM track header (mdhd)", false)
        }
        val modifiedFallback = (file.lastModified() - durationUs / 1000L).takeIf(::isPlausibleCaptureTime)
            ?: file.lastModified()
        return VideoAnchor(modifiedFallback, durationUs, "file modification time minus duration", false)
    }

    private fun synchronizeToVideoTimeline(
        samples: List<DecodedGps>,
        anchor: VideoAnchor
    ): SyncResult {
        var adjusted = samples
        var totalShift = 0L

        // CAMM type 6 carries an absolute GPS-clock timestamp. Some camera firmware writes
        // MP4 capture time and GPS time on different clock bases (or leaves the 18-second GPS/UTC
        // offset unresolved). Street View compares against the MP4 timeline, so normalize a stable
        // constant offset while preserving every interval between genuine samples.
        val fullGps = adjusted.filter { it.packetType == 6 }
        if (anchor.authoritative && fullGps.size >= 2) {
            val corrections = fullGps.map {
                (anchor.startMillis + it.presentationTimeUs / 1000L) - it.point.timestampMillis
            }.sorted()
            val correction = median(corrections)
            val deviations = corrections.map { abs(it - correction) }.sorted()
            val medianDeviation = median(deviations)
            if (abs(correction) >= MIN_CLOCK_CORRECTION_MS && medianDeviation <= MAX_CLOCK_JITTER_MS) {
                adjusted = adjusted.map { sample ->
                    if (sample.packetType == 6) sample.copy(point = shiftPoint(sample.point, correction)) else sample
                }
                totalShift += correction
            }
        }

        val videoEnd = anchor.durationUs.takeIf { it > 0L }
            ?.let { anchor.startMillis + it / 1000L }
        if (videoEnd != null) {
            val first = adjusted.minOf { it.point.timestampMillis }
            val last = adjusted.maxOf { it.point.timestampMillis }
            if (calculateOverlapMillis(anchor.startMillis, videoEnd, first, last) <= 0L) {
                val corrections = adjusted.map {
                    (anchor.startMillis + it.presentationTimeUs / 1000L) - it.point.timestampMillis
                }.sorted()
                val correction = median(corrections)
                adjusted = adjusted.map { it.copy(point = shiftPoint(it.point, correction)) }
                totalShift += correction
            }
        }

        return SyncResult(adjusted.map { it.point }, totalShift)
    }

    private fun shiftPoint(point: GpsPoint, shiftMillis: Long): GpsPoint =
        point.copy(timestampMillis = safeAdd(point.timestampMillis, shiftMillis, "GPS timestamp shift"))

    private fun calculateOverlapMillis(
        videoStart: Long,
        videoEnd: Long,
        gpxStart: Long,
        gpxEnd: Long
    ): Long = max(0L, min(videoEnd, gpxEnd) - max(videoStart, gpxStart))

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle]
        else ((values[middle - 1].toDouble() + values[middle].toDouble()) / 2.0).roundToLong()
    }

    private fun parseTimestampFromFilename(name: String): Long? {
        val match = FILENAME_TIMESTAMP_REGEX.find(name) ?: return null
        val formatter = SimpleDateFormat("yyMMdd_HHmmssSSS", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getDefault()
            set2DigitYearStart(Date(TWO_DIGIT_YEAR_START_MILLIS))
        }
        return runCatching { formatter.parse(match.value)?.time }.getOrNull()
            ?.takeIf(::isPlausibleCaptureTime)
    }

    private fun isPlausibleCaptureTime(value: Long): Boolean =
        value in MIN_CAPTURE_TIME_MILLIS..MAX_CAPTURE_TIME_MILLIS

    private fun macSecondsToUnixMillis(seconds: Long): Long? = seconds
        .takeIf { it > MAC_TO_UNIX_EPOCH_SECONDS }
        ?.let { safeMultiply(it - MAC_TO_UNIX_EPOCH_SECONDS, 1000L, "MP4 creation time") }

    private fun containsCammSampleEntry(reader: IsoBmffReader, stsd: IsoBox): Boolean {
        if (stsd.contentSize < 8) throw Mp4Exception("Truncated stsd")
        val entryCount = checkedInt(reader.readUInt32(stsd.contentOffset + 4), "stsd entry count")
        var offset = stsd.contentOffset + 8
        repeat(entryCount) {
            if (offset + 8 > stsd.endOffset) throw Mp4Exception("Truncated stsd entry")
            val size = reader.readUInt32(offset)
            val type = reader.readType(offset + 4)
            if (size < 8 || offset + size > stsd.endOffset) throw Mp4Exception("Invalid stsd entry")
            if (type == "camm") return true
            offset += size
        }
        return false
    }

    private data class MediaHeader(val creationTimeUnixMillis: Long?, val timescale: Long, val duration: Long)

    private fun parseMediaHeader(reader: IsoBmffReader, mdhd: IsoBox): MediaHeader {
        val version = reader.readUInt8(mdhd.contentOffset)
        val cursor = mdhd.contentOffset + 4
        val creation: Long
        val timescale: Long
        val duration: Long
        when (version) {
            0 -> {
                creation = reader.readUInt32(cursor)
                timescale = reader.readUInt32(cursor + 8)
                duration = reader.readUInt32(cursor + 12)
            }
            1 -> {
                creation = reader.readUInt64(cursor)
                timescale = reader.readUInt32(cursor + 16)
                duration = reader.readUInt64(cursor + 20)
            }
            else -> throw Mp4Exception("Unsupported mdhd version $version")
        }
        if (timescale <= 0L) throw Mp4Exception("Invalid CAMM timescale $timescale")
        return MediaHeader(macSecondsToUnixMillis(creation), timescale, duration)
    }

    private fun parseTrackId(reader: IsoBmffReader, tkhd: IsoBox): Long {
        val version = reader.readUInt8(tkhd.contentOffset)
        val cursor = tkhd.contentOffset + 4
        return when (version) {
            0 -> reader.readUInt32(cursor + 8)
            1 -> reader.readUInt32(cursor + 16)
            else -> throw Mp4Exception("Unsupported tkhd version $version")
        }
    }

    private fun parseStsz(reader: IsoBmffReader, box: IsoBox): IntArray {
        val sampleSize = reader.readUInt32(box.contentOffset + 4)
        val count = checkedInt(reader.readUInt32(box.contentOffset + 8), "sample count")
        validateSampleCount(count)
        if (sampleSize != 0L) {
            val size = checkedInt(sampleSize, "sample size")
            return IntArray(count) { size }
        }
        val required = box.contentOffset + 12L + count * 4L
        if (required > box.endOffset) throw Mp4Exception("Truncated stsz")
        return IntArray(count) { index ->
            checkedInt(reader.readUInt32(box.contentOffset + 12L + index * 4L), "sample size")
        }
    }

    private fun parseStz2(reader: IsoBmffReader, box: IsoBox): IntArray {
        val fieldSize = reader.readUInt8(box.contentOffset + 7)
        val count = checkedInt(reader.readUInt32(box.contentOffset + 8), "sample count")
        validateSampleCount(count)
        val payloadSize = checkedInt(box.endOffset - (box.contentOffset + 12), "stz2 payload")
        val requiredBytes = when (fieldSize) {
            4 -> (count + 1) / 2
            8 -> count
            16 -> count * 2
            else -> throw Mp4Exception("Unsupported stz2 field size $fieldSize")
        }
        if (requiredBytes > payloadSize) throw Mp4Exception("Truncated stz2")
        val data = reader.readBytes(box.contentOffset + 12, requiredBytes)
        return when (fieldSize) {
            4 -> IntArray(count) { index ->
                val byte = data[index / 2].toInt() and 0xFF
                if (index % 2 == 0) byte ushr 4 else byte and 0x0F
            }
            8 -> IntArray(count) { index -> data[index].toInt() and 0xFF }
            16 -> IntArray(count) { index ->
                val p = index * 2
                ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
            }
            else -> throw Mp4Exception("Unsupported stz2 field size $fieldSize")
        }
    }

    private data class StscEntry(val firstChunk: Int, val samplesPerChunk: Int)

    private fun parseStsc(reader: IsoBmffReader, box: IsoBox): List<StscEntry> {
        val count = checkedInt(reader.readUInt32(box.contentOffset + 4), "stsc entry count")
        if (count <= 0) throw Mp4Exception("Empty stsc")
        if (box.contentOffset + 8L + count * 12L > box.endOffset) throw Mp4Exception("Truncated stsc")
        return List(count) { index ->
            val base = box.contentOffset + 8L + index * 12L
            val firstChunk = checkedInt(reader.readUInt32(base), "first chunk")
            val samplesPerChunk = checkedInt(reader.readUInt32(base + 4), "samples per chunk")
            if (firstChunk <= 0 || samplesPerChunk <= 0) throw Mp4Exception("Invalid stsc entry")
            StscEntry(firstChunk, samplesPerChunk)
        }.also { entries ->
            if (entries.first().firstChunk != 1) throw Mp4Exception("stsc must begin at chunk 1")
            if (entries.zipWithNext().any { (a, b) -> b.firstChunk <= a.firstChunk }) {
                throw Mp4Exception("stsc chunks are not strictly increasing")
            }
        }
    }

    private fun parseStco(reader: IsoBmffReader, box: IsoBox): LongArray {
        val count = checkedInt(reader.readUInt32(box.contentOffset + 4), "chunk count")
        if (box.contentOffset + 8L + count * 4L > box.endOffset) throw Mp4Exception("Truncated stco")
        return LongArray(count) { index -> reader.readUInt32(box.contentOffset + 8L + index * 4L) }
    }

    private fun parseCo64(reader: IsoBmffReader, box: IsoBox): LongArray {
        val count = checkedInt(reader.readUInt32(box.contentOffset + 4), "chunk count")
        if (box.contentOffset + 8L + count * 8L > box.endOffset) throw Mp4Exception("Truncated co64")
        return LongArray(count) { index -> reader.readUInt64(box.contentOffset + 8L + index * 8L) }
    }

    private fun parseStts(reader: IsoBmffReader, box: IsoBox, sampleCount: Int): Pair<LongArray, LongArray> {
        val entryCount = checkedInt(reader.readUInt32(box.contentOffset + 4), "stts entry count")
        if (box.contentOffset + 8L + entryCount * 8L > box.endOffset) throw Mp4Exception("Truncated stts")
        val times = LongArray(sampleCount)
        val durations = LongArray(sampleCount)
        var sampleIndex = 0
        var decodeTime = 0L
        repeat(entryCount) { entryIndex ->
            val base = box.contentOffset + 8L + entryIndex * 8L
            val count = checkedInt(reader.readUInt32(base), "stts sample count")
            val delta = reader.readUInt32(base + 4)
            repeat(count) {
                if (sampleIndex >= sampleCount) throw Mp4Exception("stts describes too many samples")
                times[sampleIndex] = decodeTime
                durations[sampleIndex] = delta
                decodeTime = safeAdd(decodeTime, delta, "decode time")
                sampleIndex++
            }
        }
        if (sampleIndex != sampleCount) throw Mp4Exception("stts describes $sampleIndex of $sampleCount samples")
        return times to durations
    }

    private fun parseCtts(reader: IsoBmffReader, box: IsoBox, sampleCount: Int): LongArray {
        val version = reader.readUInt8(box.contentOffset)
        val entryCount = checkedInt(reader.readUInt32(box.contentOffset + 4), "ctts entry count")
        if (box.contentOffset + 8L + entryCount * 8L > box.endOffset) throw Mp4Exception("Truncated ctts")
        val offsets = LongArray(sampleCount)
        var sampleIndex = 0
        repeat(entryCount) { entryIndex ->
            val base = box.contentOffset + 8L + entryIndex * 8L
            val count = checkedInt(reader.readUInt32(base), "ctts sample count")
            val offset = when (version) {
                0 -> reader.readUInt32(base + 4)
                1 -> reader.readInt32(base + 4).toLong()
                else -> throw Mp4Exception("Unsupported ctts version $version")
            }
            repeat(count) {
                if (sampleIndex >= sampleCount) throw Mp4Exception("ctts describes too many samples")
                offsets[sampleIndex++] = offset
            }
        }
        if (sampleIndex != sampleCount) throw Mp4Exception("ctts describes $sampleIndex of $sampleCount samples")
        return offsets
    }

    private fun expandSamples(
        sampleSizes: IntArray,
        chunkOffsets: LongArray,
        sampleToChunk: List<StscEntry>,
        decodeTimes: LongArray,
        sampleDurations: LongArray,
        compositionOffsets: LongArray,
        timescale: Long,
        movieTimescale: Long?,
        editEntries: List<EditEntry>,
        fileLength: Long
    ): List<CammSample> {
        if (chunkOffsets.isEmpty() && sampleSizes.isNotEmpty()) throw Mp4Exception("No chunks for CAMM samples")
        val result = ArrayList<CammSample>(sampleSizes.size)
        var sampleIndex = 0
        var stscIndex = 0
        chunkOffsets.forEachIndexed { chunkIndexZero, chunkOffset ->
            val chunkNumber = chunkIndexZero + 1
            while (stscIndex + 1 < sampleToChunk.size &&
                chunkNumber >= sampleToChunk[stscIndex + 1].firstChunk) {
                stscIndex++
            }
            var offset = chunkOffset
            repeat(sampleToChunk[stscIndex].samplesPerChunk) {
                if (sampleIndex >= sampleSizes.size) return@repeat
                val size = sampleSizes[sampleIndex]
                if (size < 0 || offset < 0L || offset + size > fileLength) {
                    throw Mp4Exception("CAMM sample $sampleIndex lies outside MP4")
                }
                val pts = safeAdd(decodeTimes[sampleIndex], compositionOffsets[sampleIndex], "presentation time")
                result += CammSample(
                    presentationTimeUs = mapMediaPresentationToMovieMicros(
                        mediaPresentationTicks = pts,
                        mediaTimescale = timescale,
                        movieTimescale = movieTimescale,
                        editEntries = editEntries
                    ),
                    durationUs = scaleToMicros(sampleDurations[sampleIndex], timescale),
                    fileOffset = offset,
                    size = size
                )
                offset += size
                sampleIndex++
            }
        }
        if (sampleIndex != sampleSizes.size) {
            throw Mp4Exception("Chunk tables map $sampleIndex of ${sampleSizes.size} CAMM samples")
        }
        return result
    }


    private fun mapMediaPresentationToMovieMicros(
        mediaPresentationTicks: Long,
        mediaTimescale: Long,
        movieTimescale: Long?,
        editEntries: List<EditEntry>
    ): Long {
        if (movieTimescale == null || movieTimescale <= 0L || editEntries.isEmpty()) {
            return scaleToMicros(mediaPresentationTicks, mediaTimescale)
        }

        var movieCursorTicks = 0L
        editEntries.forEach { edit ->
            if (edit.mediaTimeTicks < 0L) {
                movieCursorTicks = safeAdd(movieCursorTicks, edit.segmentDurationMovieTicks, "edit-list cursor")
                return@forEach
            }
            if (edit.mediaRateInteger != 1 || edit.mediaRateFraction != 0) {
                return scaleToMicros(mediaPresentationTicks, mediaTimescale)
            }

            val segmentDurationMediaTicks = (
                edit.segmentDurationMovieTicks.toDouble() * mediaTimescale.toDouble() /
                    movieTimescale.toDouble()
                ).roundToLong()
            val segmentEndMediaTicks = safeAdd(edit.mediaTimeTicks, segmentDurationMediaTicks, "edit-list media end")
            if (mediaPresentationTicks in edit.mediaTimeTicks..segmentEndMediaTicks) {
                val relativeMediaTicks = mediaPresentationTicks - edit.mediaTimeTicks
                val relativeMovieTicks = (
                    relativeMediaTicks.toDouble() * movieTimescale.toDouble() /
                        mediaTimescale.toDouble()
                    ).roundToLong()
                val movieTicks = safeAdd(movieCursorTicks, relativeMovieTicks, "edit-list movie time")
                return scaleToMicros(movieTicks, movieTimescale)
            }
            movieCursorTicks = safeAdd(movieCursorTicks, edit.segmentDurationMovieTicks, "edit-list cursor")
        }
        return scaleToMicros(mediaPresentationTicks, mediaTimescale)
    }

    private fun scaleToMicros(value: Long, timescale: Long): Long {
        if (timescale <= 0L) throw Mp4Exception("Invalid timescale")
        return if (value == 0L) 0L else (value.toDouble() * 1_000_000.0 / timescale.toDouble()).roundToLong()
    }

    private fun gpsSecondsToUnixMillis(gpsSeconds: Double): Long {
        val gpsMillis = (gpsSeconds * 1000.0).roundToLong()
        var unixMillis = GPS_EPOCH_UNIX_MILLIS + gpsMillis
        repeat(3) {
            unixMillis = GPS_EPOCH_UNIX_MILLIS + gpsMillis - leapSecondsAtUnixMillis(unixMillis) * 1000L
        }
        return unixMillis
    }

    private fun leapSecondsAtUnixMillis(unixMillis: Long): Int {
        var count = 0
        for (threshold in LEAP_SECOND_EFFECTIVE_UNIX_MILLIS) {
            if (unixMillis >= threshold) count++ else break
        }
        return count
    }

    private fun checkedInt(value: Long, label: String): Int {
        if (value < 0L || value > Int.MAX_VALUE) throw Mp4Exception("$label is too large: $value")
        return value.toInt()
    }

    private fun validateSampleCount(count: Int) {
        if (count < 0 || count > MAX_SAMPLE_COUNT) throw Mp4Exception("Unreasonable sample count $count")
    }

    private fun safeAdd(a: Long, b: Long, label: String): Long {
        val value = a + b
        if ((a xor value) and (b xor value) < 0) throw Mp4Exception("Overflow in $label")
        return value
    }

    private fun safeMultiply(a: Long, b: Long, label: String): Long {
        if (a == 0L || b == 0L) return 0L
        val value = a * b
        if (value / b != a) throw Mp4Exception("Overflow in $label")
        return value
    }

    companion object {
        private const val MAX_SAMPLE_COUNT = 10_000_000
        private const val MAX_CAMM_SAMPLE_SIZE = 1024 * 1024
        private const val MAC_TO_UNIX_EPOCH_SECONDS = 2_082_844_800L
        private const val GPS_EPOCH_UNIX_MILLIS = 315_964_800_000L
        private const val MIN_CLOCK_CORRECTION_MS = 1_500L
        private const val MAX_CLOCK_JITTER_MS = 2_000L
        private const val MAX_VIDEO_DURATION_US = 24L * 60L * 60L * 1_000_000L
        private const val MIN_CAPTURE_TIME_MILLIS = 631_152_000_000L // 1990-01-01 UTC
        private const val MAX_CAPTURE_TIME_MILLIS = 4_102_444_800_000L // 2100-01-01 UTC
        private const val TWO_DIGIT_YEAR_START_MILLIS = 946_684_800_000L // 2000-01-01 UTC
        private val FILENAME_TIMESTAMP_REGEX = Regex("\\d{6}_\\d{9}")

        private fun scaleValueToMicros(value: Long, timescale: Long): Long =
            if (value == 0L) 0L else (value.toDouble() * 1_000_000.0 / timescale.toDouble()).roundToLong()

        // UTC instants immediately after each GPS-relevant leap second.
        private val LEAP_SECOND_EFFECTIVE_UNIX_MILLIS = longArrayOf(
            362_793_600_000L, 394_329_600_000L, 425_865_600_000L, 489_024_000_000L,
            567_993_600_000L, 631_152_000_000L, 662_688_000_000L, 709_948_800_000L,
            741_484_800_000L, 773_020_800_000L, 820_454_400_000L, 867_715_200_000L,
            915_148_800_000L, 1_136_073_600_000L, 1_230_768_000_000L,
            1_341_100_800_000L, 1_435_708_800_000L, 1_483_228_800_000L
        )
    }
}
