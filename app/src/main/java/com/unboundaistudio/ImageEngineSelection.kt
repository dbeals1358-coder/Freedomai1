package com.unboundaistudio

/** User-selected engines; installed model readiness is checked separately. */
internal data class ImageEngineState(val nightmare: Boolean, val localDream: Boolean, val offlineVideo: Boolean)

internal object ImageEngineSelection {
    /** Explicit standalone mode never depends on another Android app or image server. */
    fun withoutNightmare(): ImageEngineState =
        ImageEngineState(nightmare = false, localDream = false, offlineVideo = true)

    fun imageLabel(nightmare: Boolean, localDream: Boolean, nativeQwen: Boolean = true): String = when {
        nightmare -> "Nightmare (optional)"
        localDream -> "Local Dream (optional external app)"
        nativeQwen -> "Built-in Qwen Turbo (offline)"
        else -> "HTTPS image server (optional)"
    }
}
