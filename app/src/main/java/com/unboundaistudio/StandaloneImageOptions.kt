package com.unboundaistudio

/** Small, testable profile mapping for the real on-device Qwen Turbo pipeline. */
internal object StandaloneImageOptions {
    fun tier(speed: String): String = when (speed.uppercase()) {
        "FAST" -> "TINY"         // 320px; lower memory
        "MAX QUALITY" -> "STANDARD" // 512px; higher memory
        else -> "FAST"           // 384px, default Balanced
    }

    fun ratio(aspect: String): String = when (aspect) {
        "Portrait" -> "PORTRAIT_3_4"
        "Landscape" -> "LANDSCAPE_4_3"
        else -> "SQUARE"
    }
}
