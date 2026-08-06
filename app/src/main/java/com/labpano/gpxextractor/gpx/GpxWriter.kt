package com.labpano.gpxextractor.gpx

import com.labpano.gpxextractor.mp4.GpsPoint
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GpxWriter {
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun write(points: List<GpsPoint>, output: File, trackName: String) {
        require(points.isNotEmpty()) { "Cannot write an empty GPX track" }
        output.parentFile?.mkdirs()
        OutputStreamWriter(output.outputStream(), Charsets.UTF_8).use { writer ->
            writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.append("<gpx version=\"1.1\" creator=\"Labpano GPX Extractor\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            writer.append("  <metadata><time>")
                .append(timestampFormat.format(Date(points.first().timestampMillis)))
                .append("</time></metadata>\n")
            writer.append("  <trk><name>").append(escapeXml(trackName)).append("</name><trkseg>\n")
            points.forEach { point ->
                writer.append("    <trkpt lat=\"").append(point.latitude.toString())
                    .append("\" lon=\"").append(point.longitude.toString()).append("\">")
                point.altitudeMeters?.let { writer.append("<ele>").append(it.toString()).append("</ele>") }
                writer.append("<time>").append(timestampFormat.format(Date(point.timestampMillis))).append("</time>")
                writer.append("</trkpt>\n")
            }
            writer.append("  </trkseg></trk>\n</gpx>\n")
        }
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
