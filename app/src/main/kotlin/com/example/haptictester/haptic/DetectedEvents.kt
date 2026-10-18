package com.example.haptictester.haptic

import org.json.JSONObject

data class DetectedEvent(
    val id: String,
    val label: String,
    val category: String,
    val startMs: Long,
    val endMs: Long,
)

object DetectedEvents {
    fun parse(text: String): List<DetectedEvent> {
        val root = JSONObject(text)
        val events = root.optJSONArray("events") ?: return emptyList()
        val parsed = ArrayList<DetectedEvent>(events.length())
        for (index in 0 until events.length()) {
            val row = events.optJSONObject(index) ?: continue
            val startMs = secondsToMs(row.optDouble("start_sec", 0.0))
            val endMs = secondsToMs(row.optDouble("end_sec", row.optDouble("start_sec", 0.0)))
                .coerceAtLeast(startMs)
            val id = row.optString("event_id").ifBlank { "event_%03d".format(index + 1) }
            val category = row.optString("category")
            val label = row.optString("label").ifBlank { category.ifBlank { id } }
            parsed += DetectedEvent(
                id = id,
                label = label,
                category = category,
                startMs = startMs,
                endMs = endMs,
            )
        }
        return parsed
    }

    private fun secondsToMs(seconds: Double): Long {
        if (!seconds.isFinite()) return 0L
        return (seconds * 1000.0).toLong().coerceAtLeast(0L)
    }
}
