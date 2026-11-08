package com.unboundaistudio

import org.junit.Assert.*
import org.junit.Test

class ImageEngineSelectionTest {
    @Test fun standaloneModeRequiresNoSecondImageApp() {
        val selection = ImageEngineSelection.withoutNightmare()
        assertFalse(selection.nightmare)
        assertFalse(selection.localDream)
        assertTrue(selection.offlineVideo)
    }
    @Test fun namesActualSelectedGenerator() {
        assertEquals("Nightmare (optional)", ImageEngineSelection.imageLabel(true, true, true))
        assertEquals("Local Dream (optional external app)", ImageEngineSelection.imageLabel(false, true, true))
        assertEquals("Built-in Qwen Turbo (offline)", ImageEngineSelection.imageLabel(false, false, true))
        assertEquals("HTTPS image server (optional)", ImageEngineSelection.imageLabel(false, false, false))
    }
}
