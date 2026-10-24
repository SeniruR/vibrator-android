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

/** One row of the events tab: a single event, or several saved to play together. */
data class EventItem(
    val eventIds: List<String>,
    val label: String,
    val category: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * A saved playlist is an ordered list of entries, each a list of event ids that play
 * together as one span (one id for a single event). Disabled events are not in it.
 */
object EventPlaylist {
    /** Rows for the events tab. A null [playlist] lists every event on its own row. */
    fun items(events: List<DetectedEvent>, playlist: List<List<String>>?): List<EventItem> {
        val byId = events.associateBy { it.id }
        val entries = playlist ?: events.map { listOf(it.id) }
        return entries.mapNotNull { ids ->
            val members = ids.mapNotNull(byId::get)
            if (members.isEmpty()) return@mapNotNull null
            EventItem(
                eventIds = members.map { it.id },
                label = if (members.size == 1) members[0].label else "${members.size} events together",
                category = members.map { it.category }.filter { it.isNotBlank() }.distinct().joinToString(", "),
                startMs = members.minOf { it.startMs },
                endMs = members.maxOf { it.endMs },
            )
        }
    }

    /** [ids] become one play-together group and leave any other group. */
    fun join(groups: List<Set<String>>, ids: Set<String>): List<Set<String>> {
        val rest = groups.map { it - ids }.filter { it.size >= 2 }
        return if (ids.size >= 2) rest + listOf(ids) else rest
    }

    /** Every group holding one of [ids] goes back to single events. */
    fun split(groups: List<Set<String>>, ids: Set<String>): List<Set<String>> =
        groups.filterNot { group -> group.any { it in ids } }

    /** The disabled ids and groups that rebuild a saved [playlist] for editing. */
    fun editsFor(
        events: List<DetectedEvent>,
        playlist: List<List<String>>,
    ): Pair<Set<String>, List<Set<String>>> {
        val listed = playlist.flatten().toSet()
        val disabled = events.map { it.id }.filterNot { it in listed }.toSet()
        return disabled to playlist.filter { it.size > 1 }.map { it.toSet() }
    }

    /** Entries in start-time order; a play-together group sits where its first event does. */
    fun build(
        events: List<DetectedEvent>,
        disabled: Set<String>,
        groups: List<Set<String>>,
    ): List<List<String>> {
        val ordered = events.sortedBy { it.startMs }
        val placed = mutableSetOf<Int>()
        val out = mutableListOf<List<String>>()
        for (event in ordered) {
            if (event.id in disabled) continue
            val group = groups.indexOfFirst { event.id in it }
            if (group < 0) {
                out += listOf(event.id)
            } else if (placed.add(group)) {
                out += ordered.filter { it.id in groups[group] && it.id !in disabled }.map { it.id }
            }
        }
        return out
    }
}
