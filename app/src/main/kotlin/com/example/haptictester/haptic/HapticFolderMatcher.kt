package com.example.haptictester.haptic

data class HapticFolderMatch(
    val videoName: String? = null,
    val slotNames: Map<CompareAlgorithm, String> = emptyMap(),
    val eventsName: String? = null,
    val hapticJsonName: String? = null,
) {
    val isEmpty: Boolean
        get() = videoName == null && slotNames.isEmpty() && eventsName == null && hapticJsonName == null

    fun summary(): String {
        val parts = mutableListOf<String>()
        if (videoName != null) parts += "video"
        parts += slotNames.keys.sortedBy { it.id }.map { it.shortLabel }
        if (eventsName != null) parts += "events.json"
        if (hapticJsonName != null) parts += hapticJsonName
        return if (parts.isEmpty()) "no matching files" else parts.joinToString(", ")
    }
}

object HapticFolderMatcher {
    private val exactSlots = mapOf(
        CompareAlgorithm.A to "algorithm_a_perception_mapping.wav",
        CompareAlgorithm.B to "algorithm_b_frequency_shifting.wav",
        CompareAlgorithm.C to "algorithm_c_pitch_matching.wav",
        CompareAlgorithm.D to "algorithm_d_haptic_gen.wav",
        CompareAlgorithm.E to "algorithm_e_rule_based.wav",
    )

    fun match(fileNames: List<String>): HapticFolderMatch {
        val names = fileNames.filter { it.isNotBlank() }
        val byLower = names.associateBy { it.lowercase() }

        val video = byLower["video.mp4"]
            ?: names.firstOrNull { name ->
                val lower = name.lowercase()
                lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
            }

        val slots = linkedMapOf<CompareAlgorithm, String>()
        for (algorithm in CompareAlgorithm.all) {
            val exact = exactSlots[algorithm]?.let { byLower[it] }
            val fallback = names.firstOrNull { name ->
                val lower = name.lowercase()
                lower.endsWith(".wav") &&
                    lower.contains("algorithm_${algorithm.id}") &&
                    !lower.contains("gated")
            }
            val picked = exact ?: fallback
            if (picked != null) slots[algorithm] = picked
        }

        val events = byLower["events.json"]
        val hapticJson = byLower["algorithm_e_rule_based.json"]
            ?: byLower["output_haptic_map.json"]
            ?: names.firstOrNull { name ->
                val lower = name.lowercase()
                lower.endsWith(".json") &&
                    lower != "events.json" &&
                    (lower.contains("haptic_map") || lower.contains("rule_based"))
            }

        return HapticFolderMatch(
            videoName = video,
            slotNames = slots,
            eventsName = events,
            hapticJsonName = hapticJson,
        )
    }
}
