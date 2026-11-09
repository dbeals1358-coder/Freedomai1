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
        assertTrue(prompt.contains("Follow the character identities and anatomy stated in the description."))
        assertTrue(prompt.contains("An original adult male cartoon character"))
        assertTrue(prompt.contains("without adding unwanted garments or swapping features between characters"))
    }

    @Test fun mixedAdultCharactersKeepTheirOwnAppearanceAndClothing() {
        val prompt = CartoonImagePrompt.build("A fictional adult man and woman in a comic-book scene", "Comic art", "Man and woman")
        assertTrue(prompt.contains("adult man and adult woman as separate characters"))
        assertTrue(prompt.contains("clothing and body-coverage details"))
        assertTrue(prompt.contains("without adding unwanted garments"))
    }

    @Test fun defaultDoesNotAssumeCharacterSex() {
        val prompt = CartoonImagePrompt.build("A cartoon dragon", "Ink")
        assertTrue(prompt.contains("Follow the character identities"))
        assertFalse(prompt.contains("as separate characters"))
    }

    @Test fun cartoonAlwaysUsesFreshTextToImageNotCurrentOrSavedPhotoWorkflow() {
        assertEquals("txt2img", CartoonImagePrompt.generationFlow())
        assertFalse(CartoonImagePrompt.generationFlow().startsWith("saved:"))
        assertFalse(CartoonImagePrompt.generationFlow() == "current")
    }

    @Test fun explicitlySelectedMaleAndFemaleCoverageIsNotMixedUp() {
        val prompt = CartoonImagePrompt.build(
            "An adult man and adult woman standing in a comic scene", "Comic art",
            "Man and woman", maleClothing = "Nude", femaleClothing = "Clothed"
        )
        assertTrue(prompt.contains("Every original adult male character is fully unclothed"))
        assertTrue(prompt.contains("without shorts, pants, underwear"))
        assertTrue(prompt.contains("Every adult female character wears clothing"))
        assertFalse(prompt.contains("Every original adult female character is fully unclothed"))
    }

    @Test fun bothAdultCharactersCanHaveSameCoverage() {
        val prompt = CartoonImagePrompt.build(
            "Two original adults in a cartoon", "Comic", "Man and woman",
            maleClothing = "Nude", femaleClothing = "Nude"
        )
        assertTrue(prompt.contains("adult male character is fully unclothed"))
        assertTrue(prompt.contains("adult female character is fully unclothed"))
    }

    @Test fun autoCoverageDoesNotInventNudity() {
        val prompt = CartoonImagePrompt.build("A dragon on a bicycle", "Comic")
        assertFalse(prompt.contains("fully unclothed"))
        assertFalse(prompt.contains("wears clothing as described"))
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
    @Test fun retroSeventiesCrimeFightingIsDrawnAsAction() {
        val prompt = CartoonImagePrompt.build("70's Hanna Barbera cartoon characters fighting crime", "")
        assertTrue(prompt.contains("flat hand-drawn 2D animation"))
        assertTrue(prompt.contains("action actually happening"))
        assertTrue(CartoonImagePrompt.negativePrompt(true, prompt).contains("photographic realism"))
        assertTrue(CartoonImagePrompt.negativePrompt(true, prompt).contains("posed group photo"))
    }

    @Test fun nudeDescriptionSetsBothAutoSexesWithoutOverridingClothed() {
        val prompt = CartoonImagePrompt.build("An adult man and woman naked in a cartoon", "2D", "Man and woman")
        assertTrue(prompt.contains("Every original adult male character is fully unclothed"))
        assertTrue(prompt.contains("Every original adult female character is fully unclothed"))
        val oneClothed = CartoonImagePrompt.build("An adult man and woman naked in a cartoon", "2D", "Man and woman", "Clothed", "Auto")
        assertTrue(oneClothed.contains("Every adult male character wears clothing"))
        assertFalse(oneClothed.contains("Every original adult male character is fully unclothed"))
    }

    @Test fun selectsInstalledArtModelButNotRealvisOr3dFlow() {
        val both = org.json.JSONArray("""[{"id":"realvisxl","name":"RealVis XL","family":"sdxl"},{"id":"pony_diffusion","name":"Pony Diffusion","family":"sdxl"}]""")
        assertEquals("pony_diffusion", CartoonModelSelector.chooseInstalled(both, "1970s cartoon"))
        assertEquals(null, CartoonModelSelector.chooseInstalled(org.json.JSONArray("""[{"id":"realvisxl"}]"""), "1970s cartoon"))
        assertEquals(null, CartoonModelSelector.chooseInstalled(both, "3D cartoon"))
    }


    @Test fun writtenSeventiesDescriptionOverridesOldThreeDStyle() {
        val description = "70's Hanna Barbera cartoon characters fighting crime"
        val prompt = CartoonImagePrompt.build(description, "3D cinematic cartoon")
        assertTrue(prompt.contains("1970s flat hand-drawn 2D"))
        assertTrue(prompt.contains("single action frame"))
        assertFalse(prompt.contains("Visual style: 3D"))
        assertTrue(CartoonImagePrompt.wantsRetroTwoD(prompt))
        assertTrue(CartoonImagePrompt.negativePrompt(true, prompt).contains("photographic realism"))
    }

    @Test fun explicitThreeDRequestIsStillAllowed() {
        val prompt = CartoonImagePrompt.build("1970s cartoon heroes in 3D", "3D cartoon")
        assertFalse(prompt.contains("Required look: authentic flat hand-drawn 2D"))
    }

    @Test fun cartoonModelPickerAcceptsAlternateApiKeysAndStringList() {
        val alternatives = org.json.JSONArray("""[{"id":"realvisxl","name":"RealVis XL"},{"model_id":"retro_cel_2d","name":"Retro Cartoon 2D"}]""")
        assertEquals("retro_cel_2d", CartoonModelSelector.chooseInstalled(alternatives, "70's hand drawn cartoon"))
        val strings = org.json.JSONArray("""["realvisxl","pony_diffusion"]""")
        assertEquals("pony_diffusion", CartoonModelSelector.chooseInstalled(strings, "1970s cartoon"))
    }

    @Test fun onlyRealvisInstalledDoesNotBlockCartoonGeneration() {
        val realvisOnly = org.json.JSONArray("""[{"id":"realvisxl","name":"RealVis XL"}]""")
        val choice = CartoonModelSelector.chooseOrCurrent(realvisOnly, "70's Hanna Barbera fighting crime")
        assertEquals(null, choice.modelId)
        assertTrue(choice.label.contains("current Nightmare model"))
        assertEquals("txt2img", CartoonImagePrompt.generationFlow())
    }

    @Test fun missingModelsListStillUsesCurrentNightmareCheckpoint() {
        val choice = CartoonModelSelector.chooseOrCurrent(null, "1970s hand-drawn cartoon")
        assertEquals(null, choice.modelId)
        assertTrue(choice.label.contains("style may vary"))
    }

    @Test fun installedDrawingModelStillGetsPreferred() {
        val choices = org.json.JSONArray("""[{"id":"realvisxl"},{"id":"pony_diffusion"}]""")
        val choice = CartoonModelSelector.chooseOrCurrent(choices, "70's cartoon")
        assertEquals("pony_diffusion", choice.modelId)
        assertEquals("pony_diffusion", choice.label)
    }

    @Test fun actionCartoonsUseWiderCanvasForFullScene() {
        assertEquals(Pair(1024, 768), CartoonImagePrompt.canvasFor("two heroes fighting crime", 1024))
        assertEquals(Pair(768, 576), CartoonImagePrompt.canvasFor("a team rescuing a cat", 768))
    }

    @Test fun simpleSoloCartoonsCanStaySquare() {
        assertEquals(Pair(1024, 1024), CartoonImagePrompt.canvasFor("one cartoon cat portrait", 1024))
    }
}
