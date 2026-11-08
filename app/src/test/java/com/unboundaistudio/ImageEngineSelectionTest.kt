package com.unboundaistudio

import org.junit.Assert.*
import org.junit.Test

class ImageEngineSelectionTest {
    @Test fun selectingWithoutNightmareChoosesTheLocalEngines() {
        val selection = ImageEngineSelection.withoutNightmare()
        assertFalse(selection.nightmare)
        assertTrue(selection.localDream)
        assertTrue(selection.offlineVideo)
    }

    @Test fun labelsExactlyWhichImageGeneratorWillBeUsed() {
        assertEquals("Nightmare", ImageEngineSelection.imageLabel(true, true))
        assertEquals("Local Dream (no Nightmare)", ImageEngineSelection.imageLabel(false, true))
        assertEquals("HTTPS image server (no Nightmare)", ImageEngineSelection.imageLabel(false, false))
    }
}
