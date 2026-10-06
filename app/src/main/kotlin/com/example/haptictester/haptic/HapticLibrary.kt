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
            }
            array.put(obj)
        }
        prefs.edit { putString("saved_groups", array.toString()) }
    }
}