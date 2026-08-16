package com.labpano.gpxextractor.output

import java.io.File

/** Shared policy for keeping the output root flat when it is also the monitoring root. */
object OutputLayoutPolicy {
    fun shouldWriteDailyMonitoringReports(monitoringDirectory: File, outputDirectory: File?): Boolean =
        outputDirectory == null || !sameDirectory(monitoringDirectory, outputDirectory)

    fun sameDirectory(first: File, second: File): Boolean {
        val firstPath = runCatching { first.canonicalFile }.getOrElse { first.absoluteFile }
        val secondPath = runCatching { second.canonicalFile }.getOrElse { second.absoluteFile }
        return firstPath == secondPath
    }
}
