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

    @Test fun keepsDescribedAdultCharacterFeaturesConsistent() {
        val prompt = CartoonImagePrompt.build("An original adult male cartoon character", "Ink")
        assertTrue(prompt.contains("requested identity, body features, and anatomy consistent"))
        assertTrue(prompt.contains("An original adult male cartoon character"))
    }

    @Test fun mixedAdultCharactersKeepTheirOwnAppearanceAndClothing() {
        val prompt = CartoonImagePrompt.build("A fictional adult man and woman in a comic-book scene", "Comic art", "Man and woman")
        assertTrue(prompt.contains("adult man and adult woman as separate characters"))
        assertTrue(prompt.contains("clothing and body-coverage details exactly as requested"))
        assertTrue(prompt.contains("without inventing extra garments"))
    }

    @Test fun defaultDoesNotAssumeCharacterSex() {
        val prompt = CartoonImagePrompt.build("A cartoon dragon", "Ink")
        assertTrue(prompt.contains("Follow the character identities"))
        assertFalse(prompt.contains("as separate characters"))
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

    @Test fun requiresSubject() {
        assertThrows(IllegalArgumentException::class.java) {
            CartoonImagePrompt.build("   ", "2D")
        }
    }
}
