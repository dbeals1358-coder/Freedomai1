package com.unboundaistudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CartoonImagePromptTest {
    @Test fun supportsFreelyWrittenCartoonStyle() {
        val prompt = CartoonImagePrompt.build("A dragon riding a bicycle", "Vintage comic strip in ink")
        assertTrue(prompt.contains("A dragon riding a bicycle"))
        assertTrue(prompt.contains("Vintage comic strip in ink"))
        assertFalse(prompt.contains("Natural photographic human facial detail"))
    }

    @Test fun blankStyleFallsBackToCartoonArtwork() {
        assertTrue(CartoonImagePrompt.build("Dancing cats", "").contains(CartoonImagePrompt.DEFAULT_STYLE))
    }

    @Test fun cartoonNegativeDoesNotExcludeArtStyles() {
        val prompt = CartoonImagePrompt.negativePrompt(true).lowercase()
        assertFalse(prompt.contains("anime"))
        assertFalse(prompt.contains("illustration"))
        assertFalse(prompt.contains("cartoon"))
        assertTrue(CartoonImagePrompt.negativePrompt(false).contains("cartoon"))
    }

    @Test fun maleAndFemaleCharactersReceiveConsistentInstructions() {
        val man = CartoonImagePrompt.build("An adult man standing in a studio", "Comic art")
        val woman = CartoonImagePrompt.build("An adult woman standing in a studio", "Comic art")
        for (result in listOf(man, woman)) {
            assertTrue(result.contains("anatomical features consistent"))
            assertTrue(result.contains("equally for adult men and adult women"))
            assertTrue(result.contains("Do not add or remove clothing"))
        }
        assertTrue(man.contains("An adult man"))
        assertTrue(woman.contains("An adult woman"))
    }

    @Test fun requiresSubject() {
        assertThrows(IllegalArgumentException::class.java) {
            CartoonImagePrompt.build("   ", "2D")
        }
    }
}
