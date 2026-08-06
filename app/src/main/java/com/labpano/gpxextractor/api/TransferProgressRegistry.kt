package com.labpano.gpxextractor.api

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Thread-safe live transfer state exposed to the companion client over Wi-Fi. */
object TransferProgressRegistry {
    data class Entry(
        val id: String,
        val sourceName: String,
        val destinationName: String,
        val totalBytes: Long,
        @Volatile var copiedBytes: Long,
        @Volatile var phase: String,
        val startedAt: Long
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun begin(sourceName: String, destinationName: String, totalBytes: Long): String {
        val id = "${System.nanoTime()}-${sourceName.hashCode()}"
        entries[id] = Entry(id, sourceName, destinationName, totalBytes.coerceAtLeast(0L), 0L, "COPYING", System.currentTimeMillis())
        return id
    }

    fun update(id: String, copiedBytes: Long, phase: String = "COPYING") {
        entries[id]?.let {
            it.copiedBytes = copiedBytes.coerceIn(0L, it.totalBytes.coerceAtLeast(copiedBytes))
            it.phase = phase
        }
    }

    fun phase(id: String, phase: String) {
        entries[id]?.phase = phase
    }

    fun finish(id: String) {
        entries.remove(id)
    }

    fun toJson(): JSONArray {
        val array = JSONArray()
        entries.values.sortedBy { it.startedAt }.forEach { entry ->
            val percent = if (entry.totalBytes <= 0L) 0 else ((entry.copiedBytes * 100L) / entry.totalBytes).toInt().coerceIn(0, 100)
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("sourceName", entry.sourceName)
                put("destinationName", entry.destinationName)
                put("copiedBytes", entry.copiedBytes)
                put("totalBytes", entry.totalBytes)
                put("percent", percent)
                put("phase", entry.phase)
                put("startedAt", entry.startedAt)
            })
        }
        return array
    }
}
