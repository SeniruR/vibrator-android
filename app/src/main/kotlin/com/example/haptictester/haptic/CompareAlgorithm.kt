package com.example.haptictester.haptic

enum class CompareAlgorithm(val id: String, val shortLabel: String, val description: String) {
    A("a", "A", "Perception"),
    B("b", "B", "Freq shift"),
    C("c", "C", "Pitch match"),
    D("d", "D", "HapticGen"),
    ;

    companion object {
        val all = entries.toList()
    }
}

data class CompareSlotState(
    val algorithm: CompareAlgorithm,
    val fileName: String? = null,
    val map: HapticMapData? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val isReady: Boolean get() = map != null && !loading
}
