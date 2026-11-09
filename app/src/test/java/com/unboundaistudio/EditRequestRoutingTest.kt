package com.unboundaistudio

import org.junit.Assert.*
import org.junit.Test

class EditRequestRoutingTest {
    @Test fun autoSendsClothesToAutoMaskedInpaint() {
        assertEquals("inpaint", EditRequestRouting.chooseFlow("auto", "make her swimsuit red"))
        assertEquals("inpaint", EditRequestRouting.chooseFlow("auto", "make the bikini top smaller"))
        assertEquals("inpaint", EditRequestRouting.chooseFlow("auto", "change the jacket to green"))
    }

    @Test fun imageToImageIsOnlyForPureRestyling() {
        assertEquals("img2img", EditRequestRouting.chooseFlow("auto", "sharpen this photo"))
        assertEquals("img2img", EditRequestRouting.chooseFlow("auto", "make the image photorealistic"))
    }

    @Test fun objectEditUsesRealInstructionEdit() {
        assertEquals("flux_edit", EditRequestRouting.chooseFlow("auto", "turn the umbrella red"))
        assertEquals("flux_edit", EditRequestRouting.chooseFlow("auto", "add a table to the beach"))
    }

    @Test fun explicitChoiceIsRespected() {
        assertEquals("saved:My Flow", EditRequestRouting.chooseFlow("saved:My Flow", "change jacket color"))
        assertEquals("img2img", EditRequestRouting.chooseFlow("img2img", "make her swimsuit red"))
    }

    @Test fun changeIsFirstInPrompt() {
        val s = EditRequestRouting.promptFor("inpaint", "red bikini")
        assertTrue(s.startsWith("red bikini"))
        assertFalse(s.startsWith("Edit the supplied"))
    }

    @Test fun ordinaryClothingEditKeepsOriginalFace() {
        assertTrue(EditRequestRouting.shouldPreserveFace("turn the shirt blue"))
    }

    @Test fun requestedFaceAndEyeEditsAreNotUndone() {
        assertFalse(EditRequestRouting.shouldPreserveFace("fix her eyes"))
        assertFalse(EditRequestRouting.shouldPreserveFace("change hair color to brown"))
        assertFalse(EditRequestRouting.shouldPreserveFace("make him smile"))
    }
}
