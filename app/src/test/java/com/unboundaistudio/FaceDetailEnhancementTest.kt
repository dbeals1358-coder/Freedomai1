package com.unboundaistudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceDetailEnhancementTest {
    @Test fun addsNaturalDetailsToPortraits() {
        val result = FaceDetailEnhancement.generationPrompt("A photograph of four adult women by a pool")
        assertTrue(result.contains("aligned pupils"))
        assertTrue(result.contains("skin pores"))
    }

    @Test fun respectsDeliberateStyling() {
        val prompt = "An anime portrait of a woman"
        assertEquals(prompt, FaceDetailEnhancement.generationPrompt(prompt))
    }

    @Test fun leavesUnrelatedPhotosUnchanged() {
        val prompt = "A landscape photo of a mountain"
        assertEquals(prompt, FaceDetailEnhancement.generationPrompt(prompt))
    }

    @Test fun doesNotDuplicateEnhancement() {
        val once = FaceDetailEnhancement.generationPrompt("A man in a studio")
        assertEquals(once, FaceDetailEnhancement.generationPrompt(once))
    }

    @Test fun faceRefinePromptNamesAllVisibleFaces() {
        assertTrue(FaceDetailEnhancement.REFINE_PROMPT.contains("every visible adult face"))
        assertTrue(FaceDetailEnhancement.REFINE_PROMPT.contains("Change only facial realism"))
    }
}
