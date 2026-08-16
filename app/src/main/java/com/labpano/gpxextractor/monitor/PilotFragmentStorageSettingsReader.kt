package com.labpano.gpxextractor.monitor

import android.content.ContentResolver
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.labpano.gpxextractor.util.AppLog

/**
 * Read-only compatibility fallback for Pilot OS images that mirror Camera preferences into an
 * Android Settings provider. Camera 5.18.11's /efs/video.properties remains authoritative while
 * it is readable. If /efs is permission-restricted, the mirror is allowed to refresh known values.
 */
object PilotFragmentStorageSettingsReader {
    private data class Keys(
        val prefix: String,
        val valueKeys: List<String>,
        val ableKeys: List<String>
    )

    @Volatile private var lastReadAt = 0L
    @Volatile private var hasSuccessfulBaseline = false
    @Volatile private var mirrorAvailable = false

    fun isMirrorAvailable(): Boolean = mirrorAvailable

    @Synchronized
    fun refresh(context: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReadAt < READ_INTERVAL_MS) return
        lastReadAt = now
        val resolver = context.applicationContext.contentResolver
        val efsAuthoritative = PilotFragmentStorageLocalReader.isAuthoritativeReadable()
        val hadSuccessfulBaseline = hasSuccessfulBaseline
        val changedPrefixes = mutableListOf<String>()
        var found = 0
        KEYS.forEach { keys ->
            val rawValue = readFirst(resolver, keys.valueKeys)
            val able = readFirst(resolver, keys.ableKeys)
            val explicitlyDisabled = able?.trim()?.lowercase()?.let { it in DISABLED_VALUES } == true
            if (rawValue != null || explicitlyDisabled) {
                found++
                val changed = PilotFragmentStorageRegistry.observeCameraProperty(
                    propertyPrefix = keys.prefix,
                    ableRaw = able.orEmpty(),
                    rawValue = rawValue.orEmpty(),
                    source = SOURCE,
                    // A readable /efs/video.properties owns the truth. If /efs is inaccessible,
                    // however, the Settings mirror must be allowed to UPDATE known values rather
                    // than only populate them once at connection time.
                    onlyIfUnknown = efsAuthoritative
                )
                if (changed) changedPrefixes += keys.prefix
            }
        }
        mirrorAvailable = found > 0
        if (found > 0) {
            if (!efsAuthoritative && hadSuccessfulBaseline && changedPrefixes.distinct().size == 1) {
                PilotCameraModeRegistry.observeFragmentStorageChange(
                    context = context,
                    propertyPrefix = changedPrefixes.single(),
                    source = SOURCE
                )
            }
            if (!efsAuthoritative) hasSuccessfulBaseline = true
            if ((!efsAuthoritative && !hadSuccessfulBaseline) || changedPrefixes.isNotEmpty()) {
                AppLog.info(
                    if (!hadSuccessfulBaseline) "Pilot Fragment Storage baseline read from Android Settings mirror ($found mode(s))"
                    else "Pilot Fragment Storage changed in Android Settings mirror: ${changedPrefixes.distinct().joinToString()}"
                )
            }
        }
    }

    private fun readFirst(resolver: ContentResolver, keys: List<String>): String? {
        keys.forEach { key ->
            val value = sequenceOf(
                { Settings.System.getString(resolver, key) },
                { Settings.Global.getString(resolver, key) },
                { Settings.Secure.getString(resolver, key) }
            ).mapNotNull { read -> runCatching { read() }.getOrNull() }
                .firstOrNull()
            if (value != null) return value
        }
        return null
    }

    private val KEYS = listOf(
        Keys("video", listOf("video.storagePart.value", "video_storage_part_value", "_camera\$video\$storagePart"), listOf("video.storagePart.able", "video_storage_part_able")),
        Keys("video_fishEye", listOf("video_fishEye.storagePart.value", "video_fishEye_storage_part_value", "_camera\$videoFishEye\$storagePart"), listOf("video_fishEye.storagePart.able", "video_fishEye_storage_part_able")),
        Keys("video_streetView", listOf("video_streetView.storagePart.value", "video_streetView_storage_part_value", "_camera\$videoStreetView\$storagePart"), listOf("video_streetView.storagePart.able", "video_streetView_storage_part_able")),
        Keys("video_timeLapse", listOf("video_timeLapse.storagePart.value", "video_timeLapse_storage_part_value", "_camera\$videoTimeLapse\$storagePart"), listOf("video_timeLapse.storagePart.able", "video_timeLapse_storage_part_able"))
    )

    private val DISABLED_VALUES = setOf("0", "false", "no", "off", "disabled")
    private const val SOURCE = "android-settings-camera-mirror"
    private const val READ_INTERVAL_MS = 1_000L
}
