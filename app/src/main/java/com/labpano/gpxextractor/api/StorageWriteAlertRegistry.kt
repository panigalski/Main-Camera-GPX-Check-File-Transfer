package com.labpano.gpxextractor.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persistent, bounded history of MP4 destination-write failures.
 *
 * The camera app can fail while creating, copying, finalizing or verifying an MP4 on either the
 * Pilot One internal storage or a removable external volume.  These events are retained long
 * enough for the companion client to observe them even when a failure happens between dashboard
 * polls or while the client is temporarily reconnecting.
 */
object StorageWriteAlertRegistry {
    data class Alert(
        val id: String,
        val occurredAt: Long,
        val storageType: String,
        val videoName: String,
        val destination: String,
        val operation: String,
        val message: String
    )

    @Synchronized
    fun recordFailure(
        context: Context,
        storageType: String,
        videoName: String,
        destination: String,
        operation: String,
        error: Throwable
    ) {
        val message = error.message?.trim().takeUnless { it.isNullOrBlank() }
            ?: error.javaClass.simpleName.ifBlank { "Unknown MP4 write error" }
        recordFailure(context, storageType, videoName, destination, operation, message)
    }

    @Synchronized
    fun recordFailure(
        context: Context,
        storageType: String,
        videoName: String,
        destination: String,
        operation: String,
        message: String
    ) {
        val now = System.currentTimeMillis()
        val normalized = Alert(
            id = UUID.randomUUID().toString(),
            occurredAt = now,
            storageType = normalizeStorageType(storageType),
            videoName = videoName.ifBlank { "Unknown MP4" }.take(MAX_FIELD_LENGTH),
            destination = destination.ifBlank { "Selected output storage" }.take(MAX_FIELD_LENGTH),
            operation = operation.ifBlank { "WRITE_MP4" }.take(MAX_FIELD_LENGTH),
            message = message.ifBlank { "Unknown MP4 write error" }.take(MAX_MESSAGE_LENGTH)
        )

        val alerts = load(context, now).toMutableList()
        val duplicate = alerts.firstOrNull { existing ->
            now - existing.occurredAt in 0..DEDUPE_WINDOW_MS &&
                existing.storageType == normalized.storageType &&
                existing.videoName == normalized.videoName &&
                existing.destination == normalized.destination &&
                existing.operation == normalized.operation &&
                existing.message == normalized.message
        }
        if (duplicate != null) return

        alerts.add(0, normalized)
        persist(context, alerts.take(MAX_ALERTS))
    }

    @Synchronized
    fun toJson(context: Context): JSONArray {
        val now = System.currentTimeMillis()
        val alerts = load(context, now)
        return JSONArray().apply {
            alerts.forEach { alert ->
                put(JSONObject().apply {
                    put("id", alert.id)
                    put("occurredAt", alert.occurredAt)
                    put("storageType", alert.storageType)
                    put("videoName", alert.videoName)
                    put("destination", alert.destination)
                    put("operation", alert.operation)
                    put("message", alert.message)
                })
            }
        }
    }

    private fun load(context: Context, now: Long): List<Alert> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ALERTS_JSON, "[]")
            .orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val cutoff = now - RETENTION_MS
        val result = ArrayList<Alert>(array.length().coerceAtMost(MAX_ALERTS))
        for (index in 0 until array.length()) {
            val value = array.optJSONObject(index) ?: continue
            val occurredAt = value.optLong("occurredAt", 0L)
            if (occurredAt <= 0L || occurredAt < cutoff) continue
            val id = value.optString("id").trim()
            if (id.isBlank()) continue
            result += Alert(
                id = id,
                occurredAt = occurredAt,
                storageType = normalizeStorageType(value.optString("storageType")),
                videoName = value.optString("videoName", "Unknown MP4").take(MAX_FIELD_LENGTH),
                destination = value.optString("destination", "Selected output storage").take(MAX_FIELD_LENGTH),
                operation = value.optString("operation", "WRITE_MP4").take(MAX_FIELD_LENGTH),
                message = value.optString("message", "Unknown MP4 write error").take(MAX_MESSAGE_LENGTH)
            )
            if (result.size >= MAX_ALERTS) break
        }
        return result.sortedByDescending { it.occurredAt }
    }

    private fun persist(context: Context, alerts: List<Alert>) {
        val array = JSONArray()
        alerts.take(MAX_ALERTS).forEach { alert ->
            array.put(JSONObject().apply {
                put("id", alert.id)
                put("occurredAt", alert.occurredAt)
                put("storageType", alert.storageType)
                put("videoName", alert.videoName)
                put("destination", alert.destination)
                put("operation", alert.operation)
                put("message", alert.message)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ALERTS_JSON, array.toString())
            .apply()
    }

    private fun normalizeStorageType(value: String): String = when (value.trim().uppercase()) {
        "EXTERNAL" -> "EXTERNAL"
        "INTERNAL" -> "INTERNAL"
        else -> "UNKNOWN"
    }

    private const val PREFS = "storage_write_alert_registry"
    private const val KEY_ALERTS_JSON = "alerts_json_v1"
    private const val MAX_ALERTS = 50
    private const val MAX_FIELD_LENGTH = 512
    private const val MAX_MESSAGE_LENGTH = 1_024
    private const val DEDUPE_WINDOW_MS = 5L * 60L * 1_000L
    private const val RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
}
