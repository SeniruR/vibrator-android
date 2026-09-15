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
    /**
     * Ungated 0..255 loudness per window.
     *
     * [track] is gated for playback, so most windows read zero in Event-Trigger
     * Mode. Accent shaping and the between-hit bed need the algorithm's real
     * envelope instead: that is what makes A-E feel different on the same blast.
     */
    val envelope: Map<Long, Int> = emptyMap(),
)
