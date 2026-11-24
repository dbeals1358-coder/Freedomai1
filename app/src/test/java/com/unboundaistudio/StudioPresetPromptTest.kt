package com.unboundaistudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioPresetPromptTest {
    @Test fun imageCardPreservesTitleAndAppendsUserWords() {
        val scene = "Ballet Dancer. An anime ballet dancer practicing in a studio."
        val added = "wearing a red dress, on a theater stage"
        val result = studioPresetPrompt(scene, added)
        assertTrue(result.startsWith(scene))
        assertTrue(result.contains(added))
        assertTrue(result.indexOf(added) > result.indexOf("Ballet Dancer"))
    }

    @Test fun videoCardCombinesMotionWithInstructions() {
        val result = studioPresetPrompt("Ballet in Motion. Graceful steps and turns.",
            "camera from the left, slow motion")
        assertTrue(result.contains("Ballet in Motion"))
        assertTrue(result.contains("camera from the left, slow motion"))
    }

    @Test fun noAdditionalInstructionsStillUsesScene() {
        assertEquals("Robot Comes Alive. Animate the android.",
            studioPresetPrompt(" Robot Comes Alive. Animate the android. ", " "))
    }

    @Test fun noScenePreservesOrdinaryPrompt() {
        assertEquals("a soft-lit portrait", studioPresetPrompt("", " a soft-lit portrait "))
    }
}
