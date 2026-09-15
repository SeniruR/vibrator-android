package com.example.haptictester.haptic

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import org.json.JSONObject

data class PipelineEvent(
    val eventId: String,
    val category: String,
    val label: String,
    val startMs: Long,
    val peakMs: Long,
    val endMs: Long,
    val confidence: Float,
    val includedInGate: Boolean,
) {
    fun contains(positionMs: Long): Boolean = positionMs in startMs..endMs
}

object PipelineEventsParser {
    fun parse(context: Context, uri: Uri): List<PipelineEvent> {
        val jsonText = openJson(context, uri)
            ?: throw IllegalArgumentException("Unable to open events.json")
        return parseJson(jsonText)
    }

    fun parseJson(jsonText: String): List<PipelineEvent> {
        val root = JSONObject(jsonText)
        val eventsArray = root.optJSONArray("events") ?: return emptyList()

        val events = mutableListOf<PipelineEvent>()
        for (i in 0 until eventsArray.length()) {
            val obj = eventsArray.optJSONObject(i) ?: continue
            val included = obj.optBoolean("included_in_gate", true)
            if (!included) continue

            val peakSec = obj.optDouble("peak_sec", Double.NaN)
            if (peakSec.isNaN()) continue

            val peakMs = (peakSec * 1000.0).toLong()
            val startSec = obj.optDouble("start_sec", Double.NaN)
            val endSec = obj.optDouble("end_sec", Double.NaN)
            val startMs = if (startSec.isNaN()) {
                (peakMs - 80L).coerceAtLeast(0L)
            } else {
                (startSec * 1000.0).toLong().coerceAtLeast(0L)
            }
            val endMs = if (endSec.isNaN()) {
                peakMs + 450L
            } else {
                (endSec * 1000.0).toLong().coerceAtLeast(peakMs)
            }

            events += PipelineEvent(
                eventId = obj.optString("event_id", "event_${i + 1}"),
                category = obj.optString("category", "unknown"),
                label = obj.optString("label", obj.optString("category", "event")),
                startMs = startMs,
                peakMs = peakMs,
                endMs = endMs,
                confidence = obj.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f),
                includedInGate = included,
            )
        }

        return events.sortedBy { it.peakMs }
    }

    private fun openJson(context: Context, uri: Uri): String? {
        val stream: InputStream? = when (uri.scheme) {
            "file" -> {
                val path = uri.path ?: return null
                val file = File(path)
                if (file.isFile) file.inputStream() else null
            }
            else -> context.contentResolver.openInputStream(uri)
        }
        return stream?.bufferedReader()?.use { it.readText() }
    }
}
