package com.labpano.gpxextractor.monitor

import com.labpano.gpxextractor.AppProcessClock
import com.labpano.gpxextractor.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.ConnectException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Cached read-only view of Pilot Camera's Fragment Storage option.
 *
 * Pilot Camera 5.18.11 itself reads /efs/video.properties, so values collected from that file are
 * authoritative when the file is readable.  The public camera.getOptions path is kept only as a
 * compatibility fallback for other Pilot firmware; 5.18.11 may explicitly reject that operation.
 * Filesystem rollover observation is the final fallback and never gates transfer eligibility.
 */
object PilotFragmentStorageRegistry {
    data class ModeSetting(
        val known: Boolean,
        val enabled: Boolean,
        val rawValue: String,
        val displayValue: String
    )

    data class Snapshot(
        val available: Boolean,
        val stitched: ModeSetting,
        val unstitched: ModeSetting,
        val streetView: ModeSetting,
        val timeLapse: ModeSetting,
        val updatedAt: Long,
        val source: String,
        val error: String = "",
        /** Monotonic within this Main-App process; lets the Client accept setting changes even if wall clock moves. */
        val revision: Long = 0L
    )

    private data class OptionSpec(
        val current: String,
        val support: String
    )

    data class LimitValue(
        val type: String,
        val sizeGb: Int? = null,
        val durationMinutes: Int? = null,
        val display: String
    )

    data class SelectedMode(
        val name: String,
        val setting: ModeSetting,
        val limit: LimitValue
    )

    /** Shared Main-App process epoch. Unlike wall time it cannot move backwards via GPS/NTP. */
    val processStartedElapsedRealtime: Long = AppProcessClock.processStartedElapsedRealtime

    private val stitchedSpec = OptionSpec(
        current = "_camera\$video\$storagePart",
        support = "_camera\$video\$storagePartSupport"
    )
    private val unstitchedSpec = OptionSpec(
        current = "_camera\$videoFishEye\$storagePart",
        support = "_camera\$videoFishEye\$storagePartSupport"
    )
    private val streetViewSpec = OptionSpec(
        current = "_camera\$videoStreetView\$storagePart",
        support = "_camera\$videoStreetView\$storagePartSupport"
    )
    private val timeLapseSpec = OptionSpec(
        current = "_camera\$videoTimeLapse\$storagePart",
        support = "_camera\$videoTimeLapse\$storagePartSupport"
    )
    private val specs = listOf(stitchedSpec, unstitchedSpec, streetViewSpec, timeLapseSpec)

    private data class CameraPropertyAlias(
        val spec: OptionSpec,
        val valueKeys: List<String>,
        val ableKeys: List<String>
    )

    // Camera 5.18.11 itself uses these keys in /efs/video.properties. Some Pilot control builds
    // mirror the same fields through camera.getOptions instead of the older _camera$ aliases.
    private val cameraPropertyAliases = listOf(
        CameraPropertyAlias(stitchedSpec, listOf("video.storagePart.value", "video_storage_part_value"), listOf("video.storagePart.able", "video_storage_part_able")),
        CameraPropertyAlias(unstitchedSpec, listOf("video_fishEye.storagePart.value", "video_fishEye_storage_part_value"), listOf("video_fishEye.storagePart.able", "video_fishEye_storage_part_able")),
        CameraPropertyAlias(streetViewSpec, listOf("video_streetView.storagePart.value", "video_streetView_storage_part_value"), listOf("video_streetView.storagePart.able", "video_streetView_storage_part_able")),
        CameraPropertyAlias(timeLapseSpec, listOf("video_timeLapse.storagePart.value", "video_timeLapse_storage_part_value"), listOf("video_timeLapse.storagePart.able", "video_timeLapse_storage_part_able"))
    )

    private val unknownMode = ModeSetting(false, false, "", "Unknown")
    private val refreshing = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pilot-fragment-storage").apply { isDaemon = true }
    }

    @Volatile private var lastAttemptElapsedRealtime: Long = 0L
    @Volatile private var cached = Snapshot(
        available = false,
        stitched = unknownMode,
        unstitched = unknownMode,
        streetView = unknownMode,
        timeLapse = unknownMode,
        updatedAt = 0L,
        source = "pilot-control-protocol",
        error = "Not queried yet"
    )

    /** Returns immediately and refreshes stale data on a background thread. */
    fun snapshot(): Snapshot {
        refreshAsync()
        return cached
    }

    /** Current in-process value without starting a compatibility HTTP refresh. */
    internal fun peek(): Snapshot = cached

    fun refreshAsync(force: Boolean = false) {
        // Camera 5.18.11 reads this exact properties source itself.  Avoid repeatedly issuing a
        // camera.getOptions operation that this firmware explicitly rejects once EFS gave us the
        // setting.
        val currentLocalSource =
            (PilotFragmentStorageLocalReader.isAuthoritativeReadable() && cached.source.contains("camera-efs-video.properties")) ||
                (PilotFragmentStorageSettingsReader.isMirrorAvailable() && cached.source.contains("android-settings-camera-mirror"))
        if (currentLocalSource && cached.available) return
        val nowElapsed = AppProcessClock.nowElapsedRealtime()
        if (shouldThrottleRefresh(lastAttemptElapsedRealtime, nowElapsed, force)) return
        if (!refreshing.compareAndSet(false, true)) return
        lastAttemptElapsedRealtime = nowElapsed
        executor.execute {
            try {
                val fetched = fetch()
                // Local /efs and Settings observers can update [cached] from different threads.
                // Merge + assignment must be one registry lock operation or a slower HTTP fallback
                // can overwrite a Camera value that arrived while the request was in flight.
                synchronized(this) {
                    cached = mergeFetchedWithObserved(fetched, cached)
                }
            } catch (error: Throwable) {
                synchronized(this) {
                    val previous = cached
                    // Preserve protocol or rollover-observed values through a transient HTTP failure.
                    cached = if (previous.available) {
                        previous.copy(error = error.message ?: error.javaClass.simpleName)
                    } else if (previous.source.contains("camera-efs-video.properties") && previous.error.isNotBlank()) {
                        previous.copy(updatedAt = System.currentTimeMillis())
                    } else {
                        previous.copy(
                            updatedAt = System.currentTimeMillis(),
                            error = error.message ?: error.javaClass.simpleName
                        )
                    }
                }
                AppLog.warn("Cannot read Pilot Fragment Storage: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                refreshing.set(false)
            }
        }
    }

    /** Refresh throttling must use elapsed time so GPS/NTP wall-clock changes cannot freeze polling. */
    internal fun shouldThrottleRefresh(lastElapsed: Long, nowElapsed: Long, force: Boolean = false): Boolean {
        if (force || lastElapsed <= 0L) return false
        // A smaller elapsed value means device/process epoch changed; never carry a throttle across it.
        if (nowElapsed < lastElapsed) return false
        return nowElapsed - lastElapsed < REFRESH_INTERVAL_MS
    }

    private fun mergeFetchedWithObserved(fetched: Snapshot, previous: Snapshot): Snapshot {
        // /efs/video.properties is authoritative only while it is CURRENTLY readable. If access is
        // denied on this Pilot build, the protocol/settings fallback must be allowed to replace an
        // older cached value; otherwise Fragment Storage becomes permanently frozen after connect.
        val efsAuthoritative = PilotFragmentStorageLocalReader.isAuthoritativeReadable() &&
            previous.source.contains("camera-efs-video.properties")
        val settingsMirrorAuthoritative = !efsAuthoritative &&
            PilotFragmentStorageSettingsReader.isMirrorAvailable() &&
            previous.source.contains("android-settings-camera-mirror")
        val localAuthoritative = efsAuthoritative || settingsMirrorAuthoritative

        fun mergedMode(prior: ModeSetting, incoming: ModeSetting): ModeSetting = when {
            localAuthoritative && prior.known -> prior
            incoming.known -> incoming
            prior.known -> prior
            else -> incoming
        }

        val stitched = mergedMode(previous.stitched, fetched.stitched)
        val unstitched = mergedMode(previous.unstitched, fetched.unstitched)
        val streetView = mergedMode(previous.streetView, fetched.streetView)
        val timeLapse = mergedMode(previous.timeLapse, fetched.timeLapse)
        val settingsChanged = !sameSetting(previous.stitched, stitched) ||
            !sameSetting(previous.unstitched, unstitched) ||
            !sameSetting(previous.streetView, streetView) ||
            !sameSetting(previous.timeLapse, timeLapse)
        val anyKnown = listOf(stitched, unstitched, streetView, timeLapse).any { it.known }

        val source = when {
            localAuthoritative && fetched.source.isNotBlank() && !previous.source.contains(fetched.source) ->
                mergeSource(previous.source, fetched.source)
            localAuthoritative -> previous.source
            fetched.source.isNotBlank() -> fetched.source
            else -> previous.source
        }
        val nextUpdatedAt = if (settingsChanged) {
            monotonicWallTimestamp(previous.updatedAt)
        } else {
            maxOf(previous.updatedAt, fetched.updatedAt)
        }
        val nextRevision = if (settingsChanged) previous.revision + 1L else previous.revision

        return fetched.copy(
            available = anyKnown,
            stitched = stitched,
            unstitched = unstitched,
            streetView = streetView,
            timeLapse = timeLapse,
            updatedAt = nextUpdatedAt,
            revision = nextRevision,
            source = source,
            error = if (anyKnown) fetched.error else fetched.error.ifBlank { previous.error }
        )
    }

    /**
     * Records an observed Camera fragment rollover as a fallback source of truth. This is used only
     * when the control protocol has not returned a setting. A size-limited fragment can therefore
     * still show e.g. "4 GB (observed)" and enable rolling transfer on firmware whose getOptions
     * endpoint is unavailable to third-party apps.
     */
    @Synchronized
    fun observeFragmentRollover(
        completedFile: File,
        completedSizeBytes: Long,
        firstSeenAt: Long,
        completedAt: Long
    ) {
        val path = completedFile.absolutePath.lowercase()
        val inferredMode = when {
            path.contains("unstitched") || path.contains("fisheye") -> "unstitched"
            path.contains("streetview") || path.contains("street_view") -> "streetView"
            path.contains("timelapse") || path.contains("time_lapse") -> "timeLapse"
            else -> PilotCameraModeRegistry.currentFreshMode().takeIf { it == "stitched" || it == "streetView" }
        } ?: return // Generic /Stitched/ is ambiguous on Camera 5.18.11; never fabricate a family.

        val existing = when (inferredMode) {
            "unstitched" -> cached.unstitched
            "streetView" -> cached.streetView
            "timeLapse" -> cached.timeLapse
            else -> cached.stitched
        }
        // A successful value for this exact media family wins over size/duration inference.
        if (existing.known) return

        val inferred = inferSetting(completedSizeBytes, firstSeenAt, completedAt) ?: return
        val priorSource = cached.source
        val mergedSource = if (cached.available && priorSource.startsWith("pilot-control-protocol")) {
            "pilot-control-protocol+fragment-rollover-observed"
        } else {
            "fragment-rollover-observed"
        }
        val commonUpdatedAt = maxOf(completedAt, cached.updatedAt + 1L)
        cached = when (inferredMode) {
            "unstitched" -> cached.copy(
                available = true, unstitched = inferred, updatedAt = commonUpdatedAt,
                source = mergedSource, error = cached.error, revision = cached.revision + 1L
            )
            "streetView" -> cached.copy(
                available = true, streetView = inferred, updatedAt = commonUpdatedAt,
                source = mergedSource, error = cached.error, revision = cached.revision + 1L
            )
            "timeLapse" -> cached.copy(
                available = true, timeLapse = inferred, updatedAt = commonUpdatedAt,
                source = mergedSource, error = cached.error, revision = cached.revision + 1L
            )
            else -> cached.copy(
                available = true, stitched = inferred, updatedAt = commonUpdatedAt,
                source = mergedSource, error = cached.error, revision = cached.revision + 1L
            )
        }
    }

    /** Exact Camera 5.18.11 /efs/video.properties value. */
    @Synchronized
    fun observeCameraProperty(
        propertyPrefix: String,
        ableRaw: String,
        rawValue: String,
        source: String = "camera-efs-video.properties",
        onlyIfUnknown: Boolean = false
    ): Boolean {
        val spec = when (propertyPrefix) {
            "video" -> stitchedSpec
            "video_fishEye" -> unstitchedSpec
            "video_streetView" -> streetViewSpec
            "video_timeLapse" -> timeLapseSpec
            else -> return false
        }
        val enabled = parseCameraAble(ableRaw, rawValue)
        val display = if (!enabled) OFF_DISPLAY else normalizeCameraPropertyValue(rawValue)
        val mode = ModeSetting(
            known = true,
            enabled = enabled,
            rawValue = rawValue.trim(),
            displayValue = display
        )
        val old = cached
        val existing = when (spec) {
            stitchedSpec -> old.stitched
            unstitchedSpec -> old.unstitched
            streetViewSpec -> old.streetView
            timeLapseSpec -> old.timeLapse
            else -> unknownMode
        }
        if (onlyIfUnknown && existing.known) return false
        if (sameSetting(existing, mode)) {
            // Do not manufacture a newer setting event on every 750 ms poll. Clear only a transient
            // read error while retaining the revision that identifies the last real Camera change.
            if (old.error.isNotBlank()) cached = old.copy(error = "")
            return false
        }

        val updatedAt = monotonicWallTimestamp(old.updatedAt)
        val revision = old.revision + 1L
        cached = when (spec) {
            stitchedSpec -> old.copy(available = true, stitched = mode, updatedAt = updatedAt, source = mergeSource(old.source, source), error = "", revision = revision)
            unstitchedSpec -> old.copy(available = true, unstitched = mode, updatedAt = updatedAt, source = mergeSource(old.source, source), error = "", revision = revision)
            streetViewSpec -> old.copy(available = true, streetView = mode, updatedAt = updatedAt, source = mergeSource(old.source, source), error = "", revision = revision)
            timeLapseSpec -> old.copy(available = true, timeLapse = mode, updatedAt = updatedAt, source = mergeSource(old.source, source), error = "", revision = revision)
            else -> old
        }
        return true
    }

    @Synchronized
    fun observeLocalReadSuccess(source: String) {
        if (cached.available && cached.source.contains(source)) cached = cached.copy(error = "")
    }

    @Synchronized
    fun observeLocalReadFailure(message: String, source: String) {
        // Do not erase a concrete value because a later read transiently failed. If no value is
        // known, surface the actual /efs permission/path problem once. Repeating the same expected
        // permission error every polling interval must not manufacture a new Client-visible state.
        if (cached.available) return
        if (cached.source == source && cached.error == message) return
        cached = cached.copy(
            updatedAt = monotonicWallTimestamp(cached.updatedAt),
            source = source,
            error = message
        )
    }

    private fun parseCameraAble(ableRaw: String, rawValue: String): Boolean {
        return when (ableRaw.trim().lowercase()) {
            "1", "true", "yes", "on", "enabled" -> true
            "0", "false", "no", "off", "disabled" -> false
            else -> rawValue.isNotBlank()
        }
    }

    internal fun normalizeCameraPropertyValue(raw: String): String {
        return when (raw.trim().lowercase()) {
            "10min" -> "10 min"
            "30min" -> "30 min"
            "1h" -> "1 Hour"
            "2h" -> "2 Hours"
            "4gb" -> "4 GB"
            "6gb" -> "6 GB"
            "8gb" -> "8 GB"
            "10gb" -> "10 GB"
            "" -> "Enabled"
            else -> normalizeRawValue(raw)
        }
    }

    private fun mergeSource(old: String, source: String): String {
        if (old.isBlank() || old == "pilot-control-protocol" || !cached.available) return source
        if (old.contains(source)) return old
        return "$source+$old"
    }

    /**
     * Read-only local fallback for Pilot OS builds that mirror camera options into Android's Settings
     * provider but do not accept camera.getOptions from an on-camera third-party app. The protocol
     * option name is kept exact; a local value only fills an unknown mode and never overwrites a
     * value already obtained from the control protocol or a proven filesystem rollover.
     */
    @Synchronized
    fun observeLocalOption(optionName: String, rawValue: String, source: String) {
        val spec = specs.firstOrNull { it.current == optionName } ?: return
        val existing = when (spec) {
            stitchedSpec -> cached.stitched
            unstitchedSpec -> cached.unstitched
            streetViewSpec -> cached.streetView
            timeLapseSpec -> cached.timeLapse
            else -> unknownMode
        }
        if (existing.known) return

        val current = JSONObject().put(spec.current, rawValue)
        val parsed = parseMode(current, JSONObject(), spec)
        if (!parsed.known) return
        val mergedSource = when {
            cached.source.isBlank() -> source
            cached.source.contains(source) -> cached.source
            cached.available -> cached.source + "+" + source
            else -> source
        }
        cached = when (spec) {
            stitchedSpec -> cached.copy(available = true, stitched = parsed, updatedAt = System.currentTimeMillis(), source = mergedSource)
            unstitchedSpec -> cached.copy(available = true, unstitched = parsed, updatedAt = System.currentTimeMillis(), source = mergedSource)
            streetViewSpec -> cached.copy(available = true, streetView = parsed, updatedAt = System.currentTimeMillis(), source = mergedSource)
            timeLapseSpec -> cached.copy(available = true, timeLapse = parsed, updatedAt = System.currentTimeMillis(), source = mergedSource)
            else -> cached
        }
    }

    /**
     * Fragment policy for a recording directory. Generic /Stitched/ is shared with Street View on
     * Camera 5.18.11, so use a fresh Camera-derived mode hint when available and otherwise return
     * true only when both possible persisted modes agree that Fragment Storage is enabled.
     */
    fun enabledForDirectory(
        directory: File,
        snapshot: Snapshot = snapshot(),
        ambiguousModeHint: String = ""
    ): Boolean {
        if (!snapshot.available) return false
        val path = directory.absolutePath.lowercase()
        val explicit = when {
            path.contains("unstitched") || path.contains("fisheye") -> snapshot.unstitched
            path.contains("streetview") || path.contains("street_view") -> snapshot.streetView
            path.contains("timelapse") || path.contains("time_lapse") -> snapshot.timeLapse
            else -> null
        }
        if (explicit != null) return explicit.known && explicit.enabled
        return when (ambiguousModeHint) {
            "stitched" -> snapshot.stitched.known && snapshot.stitched.enabled
            "streetView" -> snapshot.streetView.known && snapshot.streetView.enabled
            else -> snapshot.stitched.known && snapshot.streetView.known &&
                snapshot.stitched.enabled && snapshot.streetView.enabled
        }
    }

    fun displayForDirectory(
        directory: File,
        snapshot: Snapshot = snapshot(),
        ambiguousModeHint: String = ""
    ): String {
        if (!snapshot.available) return "Unavailable"
        val mode = modeForDirectory(directory, snapshot, ambiguousModeHint)
        return if (mode.known) mode.displayValue else "Unavailable"
    }

    private fun modeForDirectory(
        directory: File,
        snapshot: Snapshot,
        ambiguousModeHint: String = ""
    ): ModeSetting {
        val path = directory.absolutePath.lowercase()
        return when {
            path.contains("unstitched") || path.contains("fisheye") -> snapshot.unstitched
            path.contains("streetview") || path.contains("street_view") -> snapshot.streetView
            path.contains("timelapse") || path.contains("time_lapse") -> snapshot.timeLapse
            ambiguousModeHint == "stitched" -> snapshot.stitched
            ambiguousModeHint == "streetView" -> snapshot.streetView
            // Camera 5.18.11 writes both Stitched and Street View into /Videos/Stitched.
            else -> unknownMode
        }
    }

    fun selectedForDirectory(
        directory: File,
        snapshot: Snapshot = snapshot(),
        ambiguousModeHint: String = ""
    ): SelectedMode {
        val path = directory.absolutePath.lowercase()
        val pair = when {
            path.contains("unstitched") || path.contains("fisheye") -> "unstitched" to snapshot.unstitched
            path.contains("streetview") || path.contains("street_view") -> "streetView" to snapshot.streetView
            path.contains("timelapse") || path.contains("time_lapse") -> "timeLapse" to snapshot.timeLapse
            ambiguousModeHint == "stitched" -> "stitched" to snapshot.stitched
            ambiguousModeHint == "streetView" -> "streetView" to snapshot.streetView
            else -> "" to unknownMode
        }
        return SelectedMode(pair.first, pair.second, limitValue(pair.second))
    }

    /** Select a mode from an explicit Camera-derived family, never from the Main App monitor folder. */
    fun selectedForMode(modeName: String, snapshot: Snapshot = snapshot()): SelectedMode? {
        val pair = when (modeName) {
            "stitched" -> "stitched" to snapshot.stitched
            "unstitched" -> "unstitched" to snapshot.unstitched
            "streetView" -> "streetView" to snapshot.streetView
            "timeLapse" -> "timeLapse" to snapshot.timeLapse
            else -> return null
        }
        return SelectedMode(pair.first, pair.second, limitValue(pair.second))
    }

    fun limitValue(mode: ModeSetting): LimitValue {
        if (!mode.known) return LimitValue("unknown", display = "Unknown")
        if (!mode.enabled) return LimitValue("unlimited", display = OFF_DISPLAY)
        return parseLimitValue(mode.rawValue, mode.displayValue)
    }

    internal fun parseLimitValue(rawValue: String, fallbackDisplay: String = ""): LimitValue {
        val raw = rawValue.trim()
        val compact = raw.lowercase().replace(" ", "")
        Regex("^(4|6|8|10)(?:g|gb)$").matchEntire(compact)?.let { match ->
            val gb = match.groupValues[1].toInt()
            return LimitValue("size", sizeGb = gb, display = "$gb GB")
        }
        Regex("^(10|30)(?:m|min|mins|minute|minutes)$").matchEntire(compact)?.let { match ->
            val minutes = match.groupValues[1].toInt()
            return LimitValue("time", durationMinutes = minutes, display = "$minutes min")
        }
        Regex("^(1|2)(?:h|hr|hrs|hour|hours)$").matchEntire(compact)?.let { match ->
            val hours = match.groupValues[1].toInt()
            val minutes = hours * 60
            return LimitValue("time", durationMinutes = minutes, display = if (hours == 1) "1 Hour" else "$hours Hours")
        }
        raw.toLongOrNull()?.let { number ->
            normalizeNumericValue(number)?.let { normalized ->
                Regex("^(4|6|8|10) GB$").matchEntire(normalized)?.let { m ->
                    return LimitValue("size", sizeGb = m.groupValues[1].toInt(), display = normalized)
                }
                val minutes = when (normalized.lowercase()) {
                    "10 min" -> 10
                    "30 min" -> 30
                    "1 hour" -> 60
                    "2 hours" -> 120
                    else -> null
                }
                if (minutes != null) return LimitValue("time", durationMinutes = minutes, display = normalized)
            }
        }
        val display = fallbackDisplay.ifBlank { normalizeCameraPropertyValue(raw) }.ifBlank { "Enabled" }
        return LimitValue("other", display = display)
    }

    /** Parses either a full or partial getOptions response. Kept internal for focused tests. */
    internal fun parseResponse(text: String): Snapshot {
        val options = parseOptionsResponse(text)
        val stitched = parseMode(options, options, stitchedSpec)
        val unstitched = parseMode(options, options, unstitchedSpec)
        val streetView = parseMode(options, options, streetViewSpec)
        val timeLapse = parseMode(options, options, timeLapseSpec)
        val available = listOf(stitched, unstitched, streetView, timeLapse).any { it.known }
        if (!available) throw IllegalStateException("Fragment Storage options were not returned")

        return Snapshot(
            available = true,
            stitched = stitched,
            unstitched = unstitched,
            streetView = streetView,
            timeLapse = timeLapse,
            updatedAt = System.currentTimeMillis(),
            source = "pilot-control-protocol"
        )
    }

    private fun parseMode(currentOptions: JSONObject, supportOptions: JSONObject, spec: OptionSpec): ModeSetting {
        if (!currentOptions.has(spec.current)) return unknownMode
        val rawNode = currentOptions.opt(spec.current) ?: return unknownMode
        val raw = rawNode.toString().trim()
        // The protocol defines ONLY an empty string as Fragment Storage disabled. JSON null is not a
        // documented "Off" value, so keep it Unknown; otherwise one unsupported/partial option can
        // incorrectly block concrete fragment rollover for the selected recording folder.
        if (raw.equals("null", ignoreCase = true)) return unknownMode
        val support = supportArray(supportOptions, spec.support)
        val display = if (raw.isBlank()) {
            OFF_DISPLAY
        } else {
            findSupportDisplay(support, raw).ifBlank { normalizeRawValue(raw) }
        }
        return ModeSetting(
            known = true,
            enabled = raw.isNotBlank(),
            rawValue = raw,
            displayValue = display
        )
    }

    private fun supportArray(options: JSONObject, key: String): JSONArray? {
        options.optJSONArray(key)?.let { return it }
        val raw = options.opt(key) as? String ?: return null
        return runCatching { JSONArray(raw) }.getOrNull()
    }

    private fun findSupportDisplay(array: JSONArray?, raw: String): String {
        if (array == null) return ""
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            if (entry.optString("value").trim().equals(raw.trim(), ignoreCase = true)) {
                return entry.optString("name").trim()
            }
        }
        return ""
    }

    private fun normalizeRawValue(raw: String): String {
        val value = raw.trim()
        val lower = value.lowercase()
        Regex("^(\\d+(?:\\.\\d+)?)\\s*(g|gb)$", RegexOption.IGNORE_CASE).matchEntire(value)?.let {
            return "${it.groupValues[1]} GB"
        }
        Regex("^(\\d+)\\s*(min|mins|minute|minutes)$", RegexOption.IGNORE_CASE).matchEntire(value)?.let {
            return "${it.groupValues[1]} min"
        }
        Regex("^(\\d+)\\s*(h|hr|hrs|hour|hours)$", RegexOption.IGNORE_CASE).matchEntire(value)?.let {
            return "${it.groupValues[1]} hour${if (it.groupValues[1] == "1") "" else "s"}"
        }

        val number = lower.toLongOrNull()
        if (number != null) {
            normalizeNumericValue(number)?.let { return it }
        }
        return value
    }

    private fun normalizeNumericValue(value: Long): String? {
        // Firmware/support tables have used different raw units across generations. Recognize the
        // user-visible values without assuming one unit globally.
        val directMinutes = mapOf(10L to "10 min", 30L to "30 min", 60L to "1 hour", 120L to "2 hours")
        directMinutes[value]?.let { return it }
        val seconds = mapOf(600L to "10 min", 1_800L to "30 min", 3_600L to "1 hour", 7_200L to "2 hours")
        seconds[value]?.let { return it }
        val millis = mapOf(600_000L to "10 min", 1_800_000L to "30 min", 3_600_000L to "1 hour", 7_200_000L to "2 hours")
        millis[value]?.let { return it }
        val mebibytes = mapOf(4_096L to "4 GB", 6_144L to "6 GB", 8_192L to "8 GB", 10_240L to "10 GB")
        mebibytes[value]?.let { return it }
        val binaryBytes = mapOf(
            4L * GIB to "4 GB",
            6L * GIB to "6 GB",
            8L * GIB to "8 GB",
            10L * GIB to "10 GB"
        )
        binaryBytes[value]?.let { return it }
        val decimalBytes = mapOf(
            4_000_000_000L to "4 GB",
            6_000_000_000L to "6 GB",
            8_000_000_000L to "8 GB",
            10_000_000_000L to "10 GB"
        )
        return decimalBytes[value]
    }

    private fun inferSetting(sizeBytes: Long, firstSeenAt: Long, completedAt: Long): ModeSetting? {
        if (sizeBytes > 0L) {
            for (gb in listOf(4, 6, 8, 10)) {
                val binaryTarget = gb.toLong() * GIB
                val decimalTarget = gb.toLong() * 1_000_000_000L
                if (near(sizeBytes, binaryTarget, SIZE_INFERENCE_TOLERANCE) ||
                    near(sizeBytes, decimalTarget, SIZE_INFERENCE_TOLERANCE)
                ) {
                    return ModeSetting(true, true, sizeBytes.toString(), "$gb GB (observed)")
                }
            }
        }

        val duration = completedAt - firstSeenAt
        if (firstSeenAt > 0L && duration > 0L) {
            val timeTargets = listOf(
                10L * 60_000L to "10 min",
                30L * 60_000L to "30 min",
                60L * 60_000L to "1 hour",
                120L * 60_000L to "2 hours"
            )
            timeTargets.firstOrNull { (target, _) -> near(duration, target, TIME_INFERENCE_TOLERANCE) }?.let { (_, label) ->
                return ModeSetting(true, true, duration.toString(), "$label (observed)")
            }
        }
        return null
    }

    private fun near(actual: Long, target: Long, fraction: Double): Boolean =
        abs(actual.toDouble() - target.toDouble()) <= target.toDouble() * fraction

    private fun sameSetting(first: ModeSetting, second: ModeSetting): Boolean =
        first.known == second.known &&
            first.enabled == second.enabled &&
            first.rawValue.equals(second.rawValue, ignoreCase = true) &&
            first.displayValue.equals(second.displayValue, ignoreCase = true)

    private fun monotonicWallTimestamp(previous: Long): Long =
        maxOf(System.currentTimeMillis(), previous + 1L)

    private fun fetch(): Snapshot {
        var lastError: Throwable? = null
        for (host in cameraHosts()) {
            try {
                return fetchHost(host, sourceSuffix = "direct")
            } catch (directError: Throwable) {
                lastError = directError
                // Only a hard connect/routing failure proves that this host is unusable. A read
                // timeout can also mean the Camera accepted the socket but requires a protocol
                // session before servicing camera.getOptions, so it MUST still reach the session
                // fallback below. Treating SocketTimeoutException as unreachable was a 0.5.32 bug.
                if (isHostUnreachable(directError)) continue
            }

            // The protocol's connection chapter requires a session before Camera communication.
            // Some firmware accepts getOptions directly, some does not. Use a short-lived read-only
            // session only as a compatibility fallback, then close it immediately.
            try {
                return withTemporarySession(host) { fetchHost(host, sourceSuffix = "session") }
            } catch (sessionError: Throwable) {
                lastError = sessionError
            }
        }
        throw lastError ?: IllegalStateException("Pilot camera control service unavailable")
    }

    private fun fetchHost(host: String, sourceSuffix: String): Snapshot {
        var firstError: Throwable? = null
        val options = JSONObject()

        // Return the first concrete current value instead of waiting for every optional recording
        // family. On some Pilot One firmwares an unsupported FishEye/StreetView/TimeLapse option can
        // time out rather than fail quickly; waiting for those after Stitched already returned 4 GB
        // made the Client remain "Unavailable" for far too long. Stitched is intentionally first.
        specs.forEach { spec ->
            runCatching { executeOptionsCompatible(host, listOf(spec.current, spec.support)) }
                .onSuccess { mergeKnownOptions(options, it, listOf(spec.current, spec.support)) }
                .onFailure { if (firstError == null) firstError = it }
            if (options.has(spec.current)) return snapshotFromOptions(options, sourceSuffix)

            runCatching { executeOptionsCompatible(host, listOf(spec.current)) }
                .onSuccess { mergeKnownOptions(options, it, listOf(spec.current)) }
                .onFailure { if (firstError == null) firstError = it }
            if (options.has(spec.current)) return snapshotFromOptions(options, sourceSuffix)
        }

        // Camera-5.18.11-key compatibility: some Pilot control-service builds expose the same raw
        // fields used by the stock Camera app rather than the older _camera$ option aliases. Query
        // these only after the documented aliases fail, and translate them into the canonical model.
        cameraPropertyAliases.forEach { alias ->
            val names = alias.valueKeys + alias.ableKeys
            runCatching { executeOptionsCompatible(host, names) }
                .onSuccess { rawOptions ->
                    val rawValue = alias.valueKeys.firstNotNullOfOrNull { key ->
                        if (rawOptions.has(key) && !rawOptions.isNull(key)) rawOptions.opt(key)?.toString() else null
                    }
                    val ableRaw = alias.ableKeys.firstNotNullOfOrNull { key ->
                        if (rawOptions.has(key) && !rawOptions.isNull(key)) rawOptions.opt(key)?.toString() else null
                    }.orEmpty()
                    if (rawValue != null) {
                        val enabled = parseCameraAble(ableRaw, rawValue)
                        options.put(alias.spec.current, if (enabled) rawValue else "")
                    } else if (ableRaw.trim().lowercase() in DISABLED_VALUES) {
                        // Disabled is concrete even if a firmware omits the remembered value. In
                        // contrast, able=true without value does not tell us the selected limit.
                        options.put(alias.spec.current, "")
                    }
                }
                .onFailure { if (firstError == null) firstError = it }
            if (options.has(alias.spec.current)) return snapshotFromOptions(options, "$sourceSuffix:camera-property-keys")

            // A strict server may reject a request containing even one unknown alias. Try each value
            // key alone; an empty current value is still a valid Off/Unlimited result.
            alias.valueKeys.forEach { valueKey ->
                runCatching { executeOptionsCompatible(host, listOf(valueKey)) }
                    .onSuccess { rawOptions ->
                        if (rawOptions.has(valueKey) && !rawOptions.isNull(valueKey)) {
                            options.put(alias.spec.current, rawOptions.opt(valueKey)?.toString().orEmpty())
                        }
                    }
                    .onFailure { if (firstError == null) firstError = it }
                if (options.has(alias.spec.current)) return snapshotFromOptions(options, "$sourceSuffix:camera-property-key")
            }
        }

        // Last compatibility attempt: a few control-service builds only answer multi-option requests.
        val allNames = specs.flatMap { listOf(it.current, it.support) }
        runCatching { executeOptionsCompatible(host, allNames) }
            .onSuccess { mergeKnownOptions(options, it, allNames) }
            .onFailure { if (firstError == null) firstError = it }
        if (specs.any { options.has(it.current) }) return snapshotFromOptions(options, sourceSuffix)

        throw firstError ?: IllegalStateException("Fragment Storage values were not returned")
    }

    private fun snapshotFromOptions(options: JSONObject, sourceSuffix: String): Snapshot {
        val stitched = parseMode(options, options, stitchedSpec)
        val unstitched = parseMode(options, options, unstitchedSpec)
        val streetView = parseMode(options, options, streetViewSpec)
        val timeLapse = parseMode(options, options, timeLapseSpec)
        val available = listOf(stitched, unstitched, streetView, timeLapse).any { it.known }
        if (!available) throw IllegalStateException("Fragment Storage current value was empty/unsupported")
        return Snapshot(
            available = true,
            stitched = stitched,
            unstitched = unstitched,
            streetView = streetView,
            timeLapse = timeLapse,
            updatedAt = System.currentTimeMillis(),
            source = "pilot-control-protocol:$sourceSuffix",
            error = ""
        )
    }

    private fun mergeKnownOptions(target: JSONObject, source: JSONObject, names: List<String>) {
        names.forEach { name ->
            if (source.has(name)) target.put(name, source.opt(name))
        }
    }

    private fun executeOptionsCompatible(host: String, optionNames: List<String>): JSONObject {
        var firstError: Throwable? = null
        val variants = listOf(
            RequestVariant(nestedInput = true, outerParametersAsString = false, version = "5.0.0"),
            RequestVariant(nestedInput = true, outerParametersAsString = true, version = "5.0.0"),
            RequestVariant(nestedInput = false, outerParametersAsString = false, version = "5.0.0"),
            RequestVariant(nestedInput = false, outerParametersAsString = true, version = "5.0.0"),
            // Some older Pilot service builds compare the header to their implementation version
            // rather than the protocol table's 5.0.0 value. Keep this read-only fallback last.
            RequestVariant(nestedInput = true, outerParametersAsString = true, version = "5.2.0")
        )
        variants.forEach { variant ->
            try {
                val body = executeGetOptions(host, optionNames, variant)
                val options = parseOptionsResponse(body)
                if (optionNames.any { options.has(it) }) return options
                if (firstError == null) firstError = IllegalStateException(
                    "camera.getOptions returned no requested Fragment Storage fields"
                )
            } catch (error: Throwable) {
                if (firstError == null) firstError = error
                if (isHostUnreachable(error)) throw error
                // An official-shape read timeout is not proof that the Camera is unreachable. Try
                // the next documented/generic envelope once; fetch() can then attempt a session.
                if (isReadTimeout(error) && variant.outerParametersAsString) throw error
            }
        }
        throw firstError ?: IllegalStateException("camera.getOptions failed")
    }

    private data class RequestVariant(
        val nestedInput: Boolean,
        val outerParametersAsString: Boolean,
        val version: String
    )

    private fun parseOptionsResponse(text: String): JSONObject {
        val root = jsonObject(text) ?: throw IllegalStateException("Pilot control returned invalid JSON")
        val state = root.optString("state")
        if (state.isNotBlank() && !state.equals("done", ignoreCase = true)) {
            val errorNode = jsonObject(root.opt("error"))
            val message = errorNode?.optString("message").orEmpty()
            throw IllegalStateException(message.ifBlank { "camera.getOptions returned $state" })
        }

        val results = jsonObject(root.opt("results")) ?: JSONObject()
        val candidates = listOf(
            results.opt("Options"),
            results.opt("options"),
            root.opt("Options"),
            root.opt("options"),
            results
        )
        candidates.forEach { candidate ->
            val objectValue = jsonObject(candidate) ?: return@forEach
            return objectValue
        }
        return JSONObject()
    }

    private fun jsonObject(value: Any?): JSONObject? = when (value) {
        null -> null
        is JSONObject -> value
        is String -> {
            val trimmed = value.trim()
            if (!trimmed.startsWith("{")) null else runCatching { JSONObject(trimmed) }.getOrNull()
        }
        else -> null
    }

    private fun executeGetOptions(host: String, optionNames: List<String>, variant: RequestVariant): String {
        val optionInput = JSONObject().put("optionNames", JSONArray(optionNames))
        val interfaceInput = if (variant.nestedInput) {
            JSONObject().put("parameters", optionInput)
        } else {
            optionInput
        }
        val initial = executeCommand(
            host = host,
            name = "camera.getOptions",
            version = variant.version,
            interfaceInput = interfaceInput,
            parametersAsString = variant.outerParametersAsString
        )
        return awaitCommandDone(host, initial)
    }

    private fun awaitCommandDone(host: String, initialBody: String): String {
        var body = initialBody
        val deadlineElapsed = AppProcessClock.nowElapsedRealtime() + COMMAND_STATUS_TIMEOUT_MS
        while (true) {
            val root = jsonObject(body) ?: return body
            val state = root.optString("state")
            if (!state.equals("inProgress", ignoreCase = true)) return body
            val id = root.optString("id").ifBlank { root.optString("name") }
            if (id.isBlank()) throw IllegalStateException("Pilot command returned inProgress without id")
            if (AppProcessClock.nowElapsedRealtime() >= deadlineElapsed) {
                throw IllegalStateException("Pilot command status timed out for $id")
            }
            Thread.sleep(COMMAND_STATUS_POLL_MS)
            body = executeStatus(host, id)
        }
    }

    private fun executeStatus(host: String, id: String): String {
        val request = JSONObject().put("id", id)
        val connection = (URL("http://$host:$CONTROL_PORT/osc/commands/status")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("version", "3.0")
            setRequestProperty("Connection", "close")
        }
        return try {
            connection.outputStream.use { stream ->
                stream.write(request.toString().toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            readHttpBody(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun <T> withTemporarySession(host: String, block: () -> T): T {
        val requestedSessionId = UUID.randomUUID().toString()
        val input = JSONObject().put(
            "parameters",
            JSONObject()
                .put("alone", true)
                .put("sessionId", requestedSessionId)
                .put("timeout", TEMP_SESSION_TIMEOUT_MS)
                .put("language", "en")
        )
        val response = awaitCommandDone(
            host,
            executeCommand(
                host = host,
                name = "camera.startSession",
                version = "3.1",
                interfaceInput = input,
                parametersAsString = true
            )
        )
        val root = JSONObject(response)
        val state = root.optString("state")
        if (!state.equals("done", ignoreCase = true)) {
            val message = root.optJSONObject("error")?.optString("message").orEmpty()
            throw IllegalStateException(message.ifBlank { "camera.startSession returned $state" })
        }
        val sessionId = jsonObject(root.opt("results"))?.optString("sessionId")
            ?.takeIf { it.isNotBlank() }
            ?: requestedSessionId

        // The protocol recommends heartbeats after a session is established. One best-effort heart
        // request is enough for this short-lived read transaction and avoids racing a very short
        // firmware-side session watchdog. Failure is ignored because getOptions may still work.
        runCatching {
            executeCommand(
                host = host,
                name = "camera._getHeart",
                version = "4.7",
                interfaceInput = JSONObject(),
                parametersAsString = true
            )
        }

        return try {
            block()
        } finally {
            runCatching {
                val closeInput = JSONObject().put(
                    "parameters",
                    JSONObject().put("sessionId", sessionId)
                )
                executeCommand(
                    host = host,
                    name = "camera.closeSession",
                    version = "3.0",
                    interfaceInput = closeInput,
                    parametersAsString = true
                )
            }.onFailure { AppLog.warn("Cannot close temporary Pilot control session: ${it.message}") }
        }
    }

    private fun executeCommand(
        host: String,
        name: String,
        version: String,
        interfaceInput: JSONObject,
        parametersAsString: Boolean
    ): String {
        val request = JSONObject().put("name", name)
        request.put("parameters", if (parametersAsString) interfaceInput.toString() else interfaceInput)

        val connection = (URL("http://$host:$CONTROL_PORT/osc/commands/execute")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("version", version)
            setRequestProperty("Connection", "close")
        }

        return try {
            connection.outputStream.use { stream ->
                stream.write(request.toString().toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            readHttpBody(connection)
        } finally {
            connection.disconnect()
        }
    }


    private fun isHostUnreachable(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is ConnectException || current is NoRouteToHostException) return true
            current = current.cause
        }
        return false
    }

    private fun isReadTimeout(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is SocketTimeoutException) return true
            current = current.cause
        }
        return false
    }

    private fun readHttpBody(connection: HttpURLConnection): String {
        val code = connection.responseCode
        val input = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException(
                "Pilot control HTTP $code" + body.takeIf { it.isNotBlank() }?.let { ": ${it.take(512)}" }.orEmpty()
            )
        }
        if (body.isBlank()) throw IllegalStateException("Pilot control returned an empty response")
        return body
    }

    private fun cameraHosts(): List<String> {
        // The Pilot control service is documented on the Camera's LAN address. Try real IPv4
        // interfaces before loopback; some Pilot OS builds do not bind port 8080 to 127.0.0.1.
        val hosts = linkedSetOf<String>()
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .mapNotNull { it.hostAddress }
                .forEach(hosts::add)
        }
        hosts.add("127.0.0.1")
        hosts.add("localhost")
        return hosts.toList()
    }

    private const val CONTROL_PORT = 8080
    private const val CONNECT_TIMEOUT_MS = 2_500
    private const val READ_TIMEOUT_MS = 8_000
    private const val COMMAND_STATUS_TIMEOUT_MS = 15_000L
    private const val COMMAND_STATUS_POLL_MS = 250L
    private const val TEMP_SESSION_TIMEOUT_MS = 15_000
    private const val REFRESH_INTERVAL_MS = 10_000L
    private val DISABLED_VALUES = setOf("0", "false", "no", "off", "disabled")
    private const val OFF_DISPLAY = "Off (Unlimited)"
    private const val GIB = 1024L * 1024L * 1024L
    private const val SIZE_INFERENCE_TOLERANCE = 0.10
    private const val TIME_INFERENCE_TOLERANCE = 0.12
}
