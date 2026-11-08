package com.unboundaistudio

/** User-selected engines; installed model readiness is checked separately. */
internal data class ImageEngineState(val nightmare: Boolean, val localDream: Boolean, val offlineVideo: Boolean)

internal object ImageEngineSelection {
    fun withoutNightmare(): ImageEngineState =
        ImageEngineState(nightmare = false, localDream = true, offlineVideo = true)

    fun imageLabel(nightmare: Boolean, localDream: Boolean): String = when {
        nightmare -> "Nightmare"
        localDream -> "Local Dream (no Nightmare)"
        else -> "HTTPS image server (no Nightmare)"
    }
}
