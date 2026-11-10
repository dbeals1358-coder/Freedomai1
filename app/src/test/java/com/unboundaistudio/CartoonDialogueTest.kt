package com.unboundaistudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CartoonDialogueTest {
    @Test fun copiesEnglishWordsExactlyIncludingPunctuation() {
        assertEquals(listOf("Run now!"), CartoonDialogue.extract("A fox says \"Run now!\" in a speech bubble"))
    }

    @Test fun readsMultipleSpeakersAndCurlyQuotes() {
        assertEquals(
            listOf("Hello!", "Let's go."),
            CartoonDialogue.extract("The woman says “Hello!” and the man says \"Let's go.\"")
        )
    }

    @Test fun noInventedLetteringWhenNotQuoted() {
        assertTrue(CartoonDialogue.extract("A cartoon hero standing by a tree").isEmpty())
        assertTrue(CartoonDialogue.extract("A fox with a speech bubble").isEmpty())
    }

    @Test fun doesNotTreatOrdinaryQuotedCharacterAsDialogue() {
        assertTrue(CartoonDialogue.extract("Draw a hero called \"Eagle\" in a forest").isEmpty())
    }

    @Test fun ignoresNonLatinTextInsteadOfPrintingNonEnglish() {
        assertTrue(CartoonDialogue.extract("A fox says \"こんにちは\" in a speech bubble").isEmpty())
    }

    @Test fun limitBubbleCountToFitFrame() {
        assertEquals(3, CartoonDialogue.extract("A says \"One\", B says \"Two\", C says \"Three\", D says \"Four\"").size)
    }
}
