package com.labpano.gpxextractor.monitor

import android.content.Context
import android.os.SystemClock
import com.labpano.gpxextractor.util.AppLog
import java.io.File
import java.io.FileInputStream
import java.util.Properties

/**
 * Read-only Fragment Storage collector grounded in the stock Pilot Camera 5.18.11 APK.
 *
 * Camera 5.18.11 stores its video preferences in /efs/video.properties.  The stitched recording
 * screen reads video.storagePart.able + video.storagePart.value; the other recording modes use the
 * equivalent video_fishEye/video_streetView/video_timeLapse prefixes.  Reading this file is the
 * closest available source of truth because this firmware rejects the public camera.getOptions
 * operation for Fragment Storage.
 *
 * /efs may be permission-restricted to the system Camera app on some Pilot OS images.  In that case
 * this reader records an explicit diagnostic and the rolling-transfer path remains independent from
 * this setting: a proven next-fragment writer can still release its predecessor.
 */
object PilotFragmentStorageLocalReader {
    @Volatile private var lastReadAt: Long = 0L
    @Volatile private var authoritativeReadable: Boolean = false
    @Volatile private var hasSuccessfulBaseline: Boolean = false
    @Volatile private var hasReadAnyValues: Boolean = false
    @Volatile private var lastWarningAt: Long = 0L
    @Volatile private var lastWarningText: String = ""

    fun isAuthoritativeReadable(): Boolean = authoritativeReadable

    @Synchronized
    fun refresh(context: Context, force: Boolean = false) {
        // Keep Context in the API because callers already have it and future Pilot variants may need
        // a Context-based source.  The 5.18.11 source itself is the absolute /efs properties file.
        context.applicationContext
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReadAt < READ_INTERVAL_MS) return
        lastReadAt = now

        val file = File(VIDEO_PROPERTIES_PATH)
        val properties = Properties()
        try {
            FileInputStream(file).use { properties.load(it) }
            // Opening/parsing the Camera-owned file succeeded. Keep it authoritative even if we
            // caught the Properties rewrite in a transient empty/partial state.
            authoritativeReadable = true
            val hadSuccessfulBaseline = hasSuccessfulBaseline
            val hadReadAnyValues = hasReadAnyValues
            val changedPrefixes = mutableListOf<String>()
            val foundPrefixes = mutableSetOf<String>()
            var found = 0
            PROPERTY_PREFIXES.forEach { prefix ->
                val able = properties.getProperty("$prefix.storagePart.able")
                val value = properties.getProperty("$prefix.storagePart.value")
                val explicitlyDisabled = able?.trim()?.lowercase()?.let { it in DISABLED_VALUES } == true
                // Camera writes the whole Properties file. If we catch it between truncate/write,
                // able=true can be visible before the selected value. Never publish that partial
                // state as a concrete setting; CLOSE_WRITE/polling will immediately retry it.
                if (value != null || explicitlyDisabled) {
                    found += 1
                    foundPrefixes += prefix
                    val changed = PilotFragmentStorageRegistry.observeCameraProperty(
                        propertyPrefix = prefix,
                        ableRaw = able.orEmpty(),
                        rawValue = value.orEmpty(),
                        source = SOURCE
                    )
                    if (changed) changedPrefixes += prefix
                }
            }
            if (found == 0) {
                PilotFragmentStorageRegistry.observeLocalReadFailure(
                    "$VIDEO_PROPERTIES_PATH contains no storagePart keys", SOURCE
                )
                logWarningRateLimited(now, "Pilot Camera properties file has no Fragment Storage keys")
            } else {
                PilotFragmentStorageRegistry.observeLocalReadSuccess(SOURCE)
                // The first successful read establishes a baseline only. On later reads, exactly one
                // changed mode-specific key identifies the Camera settings family the user edited.
                // This is a stronger signal than the Main App's output directory.
                val completeVideoBaseline = MODE_HINT_PREFIXES.all { it in foundPrefixes }
                if (completeVideoBaseline && hadSuccessfulBaseline && changedPrefixes.distinct().size == 1) {
                    PilotCameraModeRegistry.observeFragmentStorageChange(
                        context = context,
                        propertyPrefix = changedPrefixes.single(),
                        source = SOURCE
                    )
                }
                // Do not infer a recording family from a poll that caught Properties mid-rewrite.
                // The three requested video families must be visible together before changes are
                // treated as a trustworthy Camera-settings baseline.
                if (completeVideoBaseline) hasSuccessfulBaseline = true
                hasReadAnyValues = true
                if (!hadReadAnyValues || changedPrefixes.isNotEmpty()) {
                    AppLog.info(
                        if (!hadReadAnyValues) "Pilot Fragment Storage baseline read from $VIDEO_PROPERTIES_PATH ($found mode(s))"
                        else "Pilot Fragment Storage changed in $VIDEO_PROPERTIES_PATH: ${changedPrefixes.distinct().joinToString()}"
                    )
                }
            }
        } catch (error: Throwable) {
            authoritativeReadable = false
            val detail = when {
                !file.exists() -> "$VIDEO_PROPERTIES_PATH does not exist"
                !file.canRead() -> "$VIDEO_PROPERTIES_PATH is not readable by this app"
                else -> "$VIDEO_PROPERTIES_PATH: ${error.message ?: error.javaClass.simpleName}"
            }
            PilotFragmentStorageRegistry.observeLocalReadFailure(detail, SOURCE)
            logWarningRateLimited(now, "Cannot read Pilot Camera Fragment Storage properties: $detail")
        }
    }

    private fun logWarningRateLimited(nowElapsed: Long, message: String) {
        if (message != lastWarningText || nowElapsed - lastWarningAt >= WARNING_LOG_INTERVAL_MS) {
            lastWarningText = message
            lastWarningAt = nowElapsed
            AppLog.warn(message)
        }
    }

    internal const val VIDEO_PROPERTIES_PATH = "/efs/video.properties"
    internal const val SOURCE = "camera-efs-video.properties"
    private val PROPERTY_PREFIXES = listOf(
        "video",
        "video_fishEye",
        "video_streetView",
        "video_timeLapse"
    )
    private val MODE_HINT_PREFIXES = setOf("video", "video_fishEye", "video_streetView")
    private val DISABLED_VALUES = setOf("0", "false", "no", "off", "disabled")
    private const val READ_INTERVAL_MS = 750L
    private const val WARNING_LOG_INTERVAL_MS = 30_000L
}
