package com.unboundaistudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CartoonImagePromptTest {

    @Test fun runTogetherSuperheroNamesBecomeFiveDistinctAdultsInSingleScene() {
        val scene = "70's hanna barbera style cartoon characters nude catwoman batgirlsupergirl batman superman nude"
        val prompt = CartoonImagePrompt.build(scene, CartoonImagePrompt.DEFAULT_STYLE)
        assertTrue(prompt.startsWith("70's hanna barbera style cartoon characters nude catwoman batgirl supergirl"))
        assertTrue(prompt.contains("exactly 5 separate, recognizable ADULT characters"))
        assertTrue(prompt.contains("Catwoman, Batgirl, Supergirl, Batman, Superman"))
        assertTrue(prompt.contains("no collage, no montage"))
        assertTrue(prompt.contains("Every original adult male character is fully unclothed"))
        assertTrue(prompt.contains("Every original adult female character is fully unclothed"))
        assertEquals(Pair(1024, 768), CartoonImagePrompt.canvasFor(scene, 1024))
        val negative = CartoonImagePrompt.negativePrompt(true, prompt, scene)
        assertTrue(negative.contains("collage"))
        assertTrue(negative.contains("superhero uniforms"))
    }

    @Test fun namesWithSpacesRemainUnchangedAndSoloCharactersAreNotGrouped() {
        val solo = "Catwoman on a balcony"
        assertEquals(solo, CartoonImagePrompt.normalizeCharacterSpacing(solo))
        assertFalse(CartoonImagePrompt.build(solo, "2D").contains("exactly 2 separate"))
        assertEquals("Batgirl Supergirl", CartoonImagePrompt.normalizeCharacterSpacing("BatgirlSupergirl"))
    }

    @Test fun userQuotedWordsAreInsertedByAppNotGeneratedAsGibberish() {
        val description = "A fox says \"Run now!\" in a speech bubble"
        val prompt = CartoonImagePrompt.build(description, "Comic book")
        assertTrue(prompt.contains("app prints the correctly spelled English dialogue"))
        assertTrue(prompt.contains("Do not draw ANY letters"))
        assertTrue(CartoonImagePrompt.negativePrompt(true, prompt, description).contains("unwanted text"))
    }


    @Test fun heroGroupNudityKeepsEveryAdultUnclothedWithoutCostumes() {
        val scene = "All adult cartoon heroes are nude in a city street"
        val prompt = CartoonImagePrompt.build(scene, "1970s cartoon", "From description")
        assertTrue(prompt.contains("Every original adult male character is fully unclothed"))
        assertTrue(prompt.contains("Every original adult female character is fully unclothed"))
        assertTrue(CartoonImagePrompt.negativePrompt(true, prompt, scene).contains("superhero uniforms"))
    }

    @Test fun mixedCoverageDoesNotExcludeRequestedClothing() {
        val scene = "A nude man and a clothed woman as comic heroes"
        val prompt = CartoonImagePrompt.build(scene, "comic book", "Man and woman")
        assertTrue(prompt.contains("Every original adult male character is fully unclothed"))
        assertTrue(prompt.contains("Every adult female character wears clothing"))
        assertFalse(CartoonImagePrompt.negativePrompt(true, prompt, scene).contains("superhero uniforms"))
    }

    @Test fun promptOverridesPreviousClothedSelectionWhenItRequestsNude() {
        val prompt = CartoonImagePrompt.build("Both adult heroes are naked", "comic", "Man and woman",
            maleClothing = "Clothed", femaleClothing = "Clothed")
        assertTrue(prompt.contains("Every original adult male character is fully unclothed"))
        assertTrue(prompt.contains("Every original adult female character is fully unclothed"))
        assertFalse(prompt.contains("wears clothing as described"))
    }

    @Test fun individualNegationDoesNotBlockOtherPersonsNudity() {
        val prompt = CartoonImagePrompt.build("The adult man is nude and the woman is not nude", "2D", "Man and woman")
        assertTrue(prompt.contains("Every original adult male character is fully unclothed"))
        assertTrue(prompt.contains("Every adult female character wears clothing"))
    }

    @Test fun pluralGenderRequestsKeepBothGroupsSeparate() {
        val prompt = CartoonImagePrompt.build("The men are nude and the women are clothed", "2D")
        assertTrue(prompt.contains("Every original adult male character is fully unclothed"))
        assertTrue(prompt.contains("Every adult female character wears clothing"))
    }

    @Test fun retroStyleDetectionIgnoresNegativeCgiLanguage() {
        val prompt = CartoonImagePrompt.build("A 1970s hand-drawn hero rescuing a kitten", "")
        assertTrue(prompt.contains("not CGI"))
        assertTrue(CartoonImagePrompt.wantsRetroTwoD(prompt))
        assertTrue(CartoonImagePrompt.negativePrompt(true, prompt).contains("photographic realism"))
    }


    @Test fun sceneAnimeStyleOverridesDefaultRetroStyle() {
        val prompt = CartoonImagePrompt.build("An anime fox flying over Tokyo", CartoonImagePrompt.DEFAULT_STYLE)
        assertTrue(prompt.startsWith("An anime fox flying over Tokyo"))
        assertTrue(prompt.contains("Visual style: 2D anime"))
        assertFalse(prompt.contains("Visual style: 1970s"))
        assertFalse(CartoonImagePrompt.wantsRetroTwoD(prompt))
    }

    @Test fun explicitTwoDDoesNotForceRetro() {
        val prompt = CartoonImagePrompt.build("A simple 2D watercolor cartoon of a whale", CartoonImagePrompt.DEFAULT_STYLE)
        assertTrue(prompt.contains("Visual style: watercolor"))
        assertFalse(prompt.contains("Visual style: 1970s"))
    }

    @Test fun exactDialogueIsNotNegativelyPromptedAway() {
        val scene = "A cartoon fox says \"Run!\" in a speech bubble"
        val prompt = CartoonImagePrompt.build(scene, "Comic book")
        val negative = CartoonImagePrompt.negativePrompt(true, prompt, scene)
        assertTrue(negative.contains("unwanted text"))
        assertTrue(prompt.contains("Speech bubbles must be ENGLISH ONLY"))
        assertTrue(prompt.contains("correctly spelled English dialogue"))
        val without = CartoonImagePrompt.build("A cartoon fox running in a forest", "")
        assertTrue(CartoonImagePrompt.negativePrompt(true, without).contains("unwanted text"))
    }

    @Test fun mixedClothingInTextDoesNotUndressBothPeople() {
        val prompt = CartoonImagePrompt.build("A nude man beside a clothed woman on the beach", "comic book", "Man and woman")
        assertTrue(prompt.contains("Every original adult male character is fully unclothed"))
        assertTrue(prompt.contains("Every adult female character wears clothing as described"))
        assertFalse(prompt.contains("Every original adult female character is fully unclothed"))
    }

    @Test fun generatedCartoonsRequestOnlyEnglishLettering() {
        val description = "A fox says \"Run now!\" in a speech bubble"
        val prompt = CartoonImagePrompt.build(description, "Comic book")
        assertTrue(prompt.contains(description))
        assertTrue(prompt.contains("ENGLISH ONLY"))
        assertTrue(prompt.contains("Speech bubbles"))
        assertTrue(prompt.contains("correctly spelled"))
        assertTrue(prompt.contains("Otherwise leave the illustration free of text"))
        val negative = CartoonImagePrompt.negativePrompt(true, prompt)
        assertTrue(negative.contains("non-English lettering"))
        assertTrue(negative.contains("gibberish text"))
    }

    @Test fun editingCartoonsTranslatesExistingTextButPreservesArtwork() {
        val prompt = CartoonImagePrompt.withEnglishTextForEditing("Change the background to green")
        assertTrue(prompt.startsWith("Change the background to green"))
        assertTrue(prompt.contains("speech bubbles"))
        assertTrue(prompt.contains("English only"))
        assertTrue(prompt.contains("Translate any existing non-English text"))
        assertTrue(prompt.contains("Preserve the original bubble positions"))
        assertTrue(prompt.contains("none was requested, keep it free of text"))
    }

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

    @Test fun nudeDescriptionSetsBothAutoSexesAndOverridesStaleClothingChoices() {
        val prompt = CartoonImagePrompt.build("An adult man and woman naked in a cartoon", "2D", "Man and woman")
        assertTrue(prompt.contains("Every original adult male character is fully unclothed"))
        assertTrue(prompt.contains("Every original adult female character is fully unclothed"))
        // A stale Clothed selector from a previous picture must not contradict
        // a new description that explicitly requests both adults unclothed.
        val oneClothed = CartoonImagePrompt.build("An adult man and woman naked in a cartoon", "2D", "Man and woman", "Clothed", "Auto")
        assertTrue(oneClothed.contains("Every original adult male character is fully unclothed"))
        assertFalse(oneClothed.contains("Every adult male character wears clothing"))
    }

    @Test fun shortCrimeFightingPromptRequestsOneIllustratedActionScene() {
        val scene = "crime fighting cartoon characters naked remove clothing"
        val prompt = CartoonImagePrompt.build(scene, CartoonImagePrompt.DEFAULT_STYLE)
        assertTrue(prompt.contains("ADULT cartoon crime fighter"))
        assertTrue(prompt.contains("actively stopping a crime"))
        assertTrue(prompt.contains("ONE continuous illustrated action scene"))
        assertTrue(prompt.contains("Every original adult male character is fully unclothed"))
        assertTrue(prompt.contains("Every original adult female character is fully unclothed"))
        assertEquals(Pair(1024, 768), CartoonImagePrompt.canvasFor(scene, 1024))
        val negative = CartoonImagePrompt.negativePrompt(true, prompt, scene)
        assertTrue(negative.contains("split-screen comparison"))
        assertTrue(negative.contains("photographic realism"))
    }

    @Test fun retroCartoonPrefersPonyIllustrationOverLustifyPhotoModel() {
        val catalog = org.json.JSONArray("""[
            {"id":"lustify_apex_v8_dmd2","name":"Lustify Apex V8 DMD2"},
            {"id":"pony_diffusion","name":"Pony Diffusion"}
        ]""")
        val prompt = CartoonImagePrompt.build("crime fighting cartoon characters", "")
        assertEquals("pony_diffusion", CartoonModelSelector.chooseForCartoon(catalog, prompt)?.modelId)
    }

    @Test fun retroCartoonNeverFallsBackToPhotoOnlyModel() {
        val catalog = org.json.JSONArray("""[
            {"id":"realvisxl","name":"RealVis XL"},
            {"id":"lustify_apex_v8_dmd2","name":"Lustify Apex V8 DMD2"}
        ]""")
        val prompt = CartoonImagePrompt.build("crime fighting cartoon characters", "")
        assertEquals(null, CartoonModelSelector.chooseForCartoon(catalog, prompt))
    }

    @Test fun dedicatedCelModelBeatsGenericIllustrationModel() {
        val catalog = org.json.JSONArray("""[
            {"id":"pony_diffusion","name":"Pony Diffusion"},
            {"id":"retro_cel_2d","name":"Retro 2D cel animation"}
        ]""")
        val prompt = CartoonImagePrompt.build("crime fighting cartoon characters", "")
        assertEquals("retro_cel_2d", CartoonModelSelector.chooseForCartoon(catalog, prompt)?.modelId)
    }

    @Test fun exactLustifyApexDmd2IsChosenOverPonyAndPlainV8() {
        val catalog = org.json.JSONArray("""[
            {"id":"pony_diffusion","name":"Pony Diffusion"},
            {"id":"lustify_apex_v8","name":"Lustify Apex V8"},
            {"id":"lustify_apex_v8_dmd2","name":"Lustify Apex V8 DMD2"}
        ]""")
        val selected = CartoonModelSelector.chooseLustifyApexDmd2(catalog)
        assertEquals("lustify_apex_v8_dmd2", selected?.modelId)
        assertEquals("Lustify Apex V8 DMD2", selected?.label)
    }

    @Test fun absentDmd2NeverFallsBackToWrongModel() {
        val others = org.json.JSONArray("""["pony_diffusion","lustify_apex_v8"]""")
        assertEquals(null, CartoonModelSelector.chooseLustifyApexDmd2(others))
        assertEquals(null, CartoonModelSelector.chooseLustifyApexDmd2(null))
    }

    @Test fun stringModelCatalogAlsoFindsLustify() {
        val choices = org.json.JSONArray("""["realvisxl","lustify-apex-v8-dmd2"]""")
        assertEquals("lustify-apex-v8-dmd2",
            CartoonModelSelector.chooseLustifyApexDmd2(choices)?.modelId)
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

    @Test fun implicitSpeechInSeventiesCartoonGetsEnglishLettering() {
        val scene = "70's Hanna Barbera style heroes \"HELP ME HERO\""
        val prompt = CartoonImagePrompt.build(scene, CartoonImagePrompt.DEFAULT_STYLE)
        assertTrue(CartoonImagePrompt.letteringRequested(scene))
        assertTrue(prompt.contains("1970s flat hand-drawn 2D"))
        assertTrue(prompt.contains("correctly spelled English dialogue"))
        assertEquals(listOf("HELP ME HERO"), CartoonDialogue.extract(scene))
    }

    @Test fun dedicatedTwoDModelTakesPrecedenceOverLustifyFallback() {
        val installed = org.json.JSONArray("""[
            {"id":"lustify-apex-v8-dmd2", "name":"Lustify Apex V8 DMD2"},
            {"id":"retro_cel_2d", "name":"Retro Cel 2D"}
        ]""")
        val chosen = CartoonModelSelector.chooseForCartoon(installed, "70's Hanna Barbera cartoon")
        assertEquals("retro_cel_2d", chosen?.modelId)
        assertEquals("lustify-apex-v8-dmd2", CartoonModelSelector.chooseForCartoon(installed, "3D cartoon")?.modelId)
    }

    @Test fun retroCartoonRefusesRealisticOnlyFallback() {
        val installed = org.json.JSONArray("""["lustify-apex-v8-dmd2"]""")
        val selected = CartoonModelSelector.chooseForCartoon(installed, "1970s 2D cartoon")
        assertEquals(null, selected?.modelId)
    }

}
