package com.unboundaistudio

import org.junit.Assert.*
import org.junit.Test

class AutomaticClothingEditTest {
    @Test fun simpleBikiniColorRequest() {
        val req = AutomaticClothingEdit.parse("red bikini")
        assertNotNull(req)
        assertEquals("red", req!!.color)
        assertTrue(req.top)
        assertTrue(req.bottom)
    }

    @Test fun lastColorIsRequestedTarget() {
        val req = AutomaticClothingEdit.parse("Change the blue bikini to red; keep the same face and background")
        assertNotNull(req)
        assertEquals("red", req!!.color)
    }

    @Test fun backgroundColorDoesNotOverrideGarmentTarget() {
        val req = AutomaticClothingEdit.parse("red bikini; keep the blue water and beach unchanged")
        assertNotNull(req)
        assertEquals("red", req!!.color)
    }

    @Test fun topOnlyIsRecognized() {
        val req = AutomaticClothingEdit.parse("Make the bikini top purple")
        assertNotNull(req)
        assertEquals("purple", req!!.color)
        assertTrue(req.top)
        assertFalse(req.bottom)
    }

    @Test fun bottomOnlyIsRecognized() {
        val req = AutomaticClothingEdit.parse("Change the bikini bottoms to green")
        assertNotNull(req)
        assertFalse(req!!.top)
        assertTrue(req.bottom)
    }

    @Test fun rejectsCompoundOutfitEdit() {
        assertNull(AutomaticClothingEdit.parse("Change the outfit to a red bikini top and denim shorts"))
    }

    @Test fun rejectsStructuralOrNudeEdits() {
        assertNull(AutomaticClothingEdit.parse("Make the bikini top smaller"))
        assertNull(AutomaticClothingEdit.parse("Remove the blue bikini"))
        assertNull(AutomaticClothingEdit.parse("Make the beach red"))
    }
}
