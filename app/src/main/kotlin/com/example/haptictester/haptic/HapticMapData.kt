package com.example.haptictester.haptic

enum class HapticTrackFormat {
    WAV,
}

data class HapticMapData(
    val windowSizeMs: Long,
    val track: Map<Long, Int>,
    val durationMs: Long,
    val format: HapticTrackFormat,
    val useSustainedPlayback: Boolean = false,
    /** Ungated 0..255 loudness per window, retained for track inspection. */
    val envelope: Map<Long, Int> = emptyMap(),
)
