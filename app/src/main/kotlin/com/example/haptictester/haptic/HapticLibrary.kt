package com.example.haptictester.haptic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import androidx.core.content.edit

data class SavedFileGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val videoUriString: String? = null,
    val videoName: String? = null,
    val slotAUriString: String? = null,
    val slotBUriString: String? = null,
    val slotCUriString: String? = null,
    val slotDUriString: String? = null,
    val slotEUriString: String? = null,
    val pipelineEventsUriString: String? = null,
    val pipelineEventsName: String? = null,
    val timestampMs: Long = System.currentTimeMillis(), // Keeps newest items at the top
    // Stored here, not as a file link: a folder's events.json is not readable again after restart
    val events: List<DetectedEvent> = emptyList(),
    /** See [EventPlaylist]; null shows every event. */
    val playlist: List<List<String>>? = null,
)

class HapticLibraryRepository(context: Context) {
    private val prefs = context.getSharedPreferences("haptic_library_prefs", Context.MODE_PRIVATE)

    fun getSavedGroups(): List<SavedFileGroup> {
        val json = prefs.getString("saved_groups", null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<SavedFileGroup>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val slotsObj = obj.optJSONObject("compareSlotUris")
                val slots = mutableMapOf<String, String>()
                slotsObj?.keys()?.forEach { key ->
                    slots[key] = slotsObj.getString(key)
                }
                list += SavedFileGroup(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    name = obj.getString("name"),
                    videoUriString = obj.optString("videoUriString").takeIf { !it.isNullOrBlank() },
                    videoName = obj.optString("videoName").takeIf { !it.isNullOrBlank() },
                    slotAUriString = obj.optString("slotAUriString").takeIf { !it.isNullOrBlank() },
                    slotBUriString = obj.optString("slotBUriString").takeIf { !it.isNullOrBlank() },
                    slotCUriString = obj.optString("slotCUriString").takeIf { !it.isNullOrBlank() },
                    slotDUriString = obj.optString("slotDUriString").takeIf { !it.isNullOrBlank() },
                    slotEUriString = obj.optString("slotEUriString").takeIf { !it.isNullOrBlank() },
                    pipelineEventsUriString = obj.optString("pipelineEventsUriString").takeIf { !it.isNullOrBlank() },
                    pipelineEventsName = obj.optString("pipelineEventsName").takeIf { !it.isNullOrBlank() },
                    timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
                    events = readEvents(obj.optJSONArray("events")),
                    playlist = readPlaylist(obj.optJSONArray("playlist")),
                )
            }
            list.sortedByDescending { it.timestampMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveGroup(group: SavedFileGroup) {
        val current = getSavedGroups().filterNot { it.id == group.id }
        persist(listOf(group) + current)
    }

    fun deleteGroup(id: String) {
        persist(getSavedGroups().filterNot { it.id == id })
    }

    private fun persist(list: List<SavedFileGroup>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("videoUriString", item.videoUriString)
                put("videoName", item.videoName)
                put("slotAUriString", item.slotAUriString)
                put("slotBUriString", item.slotBUriString)
                put("slotCUriString", item.slotCUriString)
                put("slotDUriString", item.slotDUriString)
                put("slotEUriString", item.slotEUriString)
                put("pipelineEventsUriString", item.pipelineEventsUriString)
                put("pipelineEventsName", item.pipelineEventsName)
                put("timestampMs", item.timestampMs)
                put("events", writeEvents(item.events))
                item.playlist?.let { put("playlist", writePlaylist(it)) }
            }
            array.put(obj)
        }
        prefs.edit { putString("saved_groups", array.toString()) }
    }

    private fun writeEvents(events: List<DetectedEvent>): JSONArray = JSONArray().apply {
        events.forEach { event ->
            put(
                JSONObject()
                    .put("id", event.id)
                    .put("label", event.label)
                    .put("category", event.category)
                    .put("startMs", event.startMs)
                    .put("endMs", event.endMs),
            )
        }
    }

    private fun readEvents(array: JSONArray?): List<DetectedEvent> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            DetectedEvent(
                id = obj.optString("id"),
                label = obj.optString("label"),
                category = obj.optString("category"),
                startMs = obj.optLong("startMs"),
                endMs = obj.optLong("endMs"),
            )
        }
    }

    private fun writePlaylist(playlist: List<List<String>>): JSONArray = JSONArray().apply {
        playlist.forEach { ids -> put(JSONArray(ids)) }
    }

    private fun readPlaylist(array: JSONArray?): List<List<String>>? {
        if (array == null) return null
        return (0 until array.length()).mapNotNull { i ->
            val ids = array.optJSONArray(i) ?: return@mapNotNull null
            (0 until ids.length()).map { ids.getString(it) }
        }
    }
}
