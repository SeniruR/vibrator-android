package com.example.haptictester.haptic

enum class HapticTrackFormat {
    JSON,
    WAV,
}

data class HapticMapData(
    val windowSizeMs: Long,
    val track: Map<Long, Int>,
    val durationMs: Long,
    val format: HapticTrackFormat,
    val useSustainedPlayback: Boolean = false,
)
