package com.unboundaistudio

/** Cartoon generation has its own prompt and negative prompt so photo defaults do not fight it. */
internal object CartoonImagePrompt {
    const val DEFAULT_STYLE = "1970s Hanna-Barbera style hand-drawn 2D animation"

    private val retroStyle = Regex("""(?i)(hanna[\s-]?barbera|1970'?s|70'?s|seventies|retro|vintage|classic animation|saturday morning)""")
    private val threeD = Regex("""(?i)\b(3d|cgi|photorealistic|three[\s-]?dimensional)\b""")
    private val action = Regex("""(?i)\b(fighting|fight|chasing|chase|rescuing|rescue|battling|battle|running|jumping|flying|escaping|pursuing|saving)\b""")
    private val nudeWords = Regex("""(?i)\b(nude|naked|unclothed|undressed)\b|remove (all )?cloth(?:es|ing)|without (any )?cloth(?:es|ing)""")
    private val noNudeWords = Regex("""(?i)\b(?:no|without)\s+(?:any\s+)?(?:nudity|nude|naked|unclothed)\b|\b(?:everyone|everybody|all\s+characters?)\s+(?:must\s+be\s+)?(?:clothed|dressed)\b""")


    // A style explicitly typed in the scene takes priority over the default
    // 1970s style in the separate style field. "2D" does not mean "retro".
    private fun styleInDescription(description: String): String? = when {
        threeD.containsMatchIn(description) -> "3D animated cartoon render"
        retroStyle.containsMatchIn(description) -> "1970s flat hand-drawn 2D animation cels in a Hanna-Barbera-inspired style"
        Regex("""(?i)\b(anime|manga)\b""").containsMatchIn(description) -> "2D anime / manga drawing"
        Regex("""(?i)\b(comic[- ]book|comic strip|graphic novel)\b""").containsMatchIn(description) -> "hand-inked 2D comic-book illustration"
        Regex("""(?i)\b(watercolou?r)\b""").containsMatchIn(description) -> "watercolor cartoon painting"
        Regex("""(?i)\b(pixel art|pixelated)\b""").containsMatchIn(description) -> "pixel-art cartoon"
        Regex("""(?i)\b(claymation|stop[- ]motion)\b""").containsMatchIn(description) -> "clay stop-motion cartoon"
        Regex("""(?i)\b(pencil|sketch|charcoal)\b""").containsMatchIn(description) -> "hand-drawn pencil cartoon"
        Regex("""(?i)\b(2d|cel[- ]animation|hand[- ]drawn)\b""").containsMatchIn(description) -> "flat hand-drawn 2D animation"
        else -> null
    }

    private val requestedLettering = Regex("""(?i)\b(says?|speech bubbles?|thought bubbles?|captions?|dialogue|dialog|sign reads|text reads|lettering|labels?|words?|title reads)\b""")
    private val noLettering = Regex("""(?i)\b(no|without|never|not)\s+(?:any\s+)?(text|words?|lettering|speech bubbles?|captions?|labels?|dialogue)\b""")
    fun letteringRequested(description: String): Boolean =
        requestedLettering.containsMatchIn(description) && !noLettering.containsMatchIn(description)

    private fun explicitNude(description: String, gender: String): Boolean =
        Regex("""(?i)\b(nude|naked|unclothed)\s+(?:adult\s+)?(?:$gender)\b|\b(?:$gender)\b\s+(?:(?:is|are)\s+)?(nude|naked|unclothed)\b""")
            .containsMatchIn(description)

    private fun explicitClothed(description: String, gender: String): Boolean =
        Regex("""(?i)\b(clothed|dressed)\s+(?:adult\s+)?(?:$gender)\b|\b(?:$gender)\b\s+(?:(?:is|are)\s+)?(?:clothed|dressed|wearing|not\s+(?:nude|naked|unclothed))\b""")
            .containsMatchIn(description)

    fun wantsRetroTwoD(text: String): Boolean {
        // Evaluate the selected visual style, not later negative language
        // such as "not CGI" that must not turn the 2D cartoon style off.
        val visualStyle = if (text.contains("Visual style: ")) {
            text.substringAfter("Visual style: ").substringBefore(". ")
        } else text
        return retroStyle.containsMatchIn(visualStyle) && !threeD.containsMatchIn(visualStyle)
    }

    /** Never reuse a current or saved image workflow that might have a gallery photo attached. */
    fun generationFlow(): String = "txt2img"

    /** Keep wide action/group scenes in-frame instead of cropping into a square portrait. */
    fun canvasFor(description: String, requestedSide: Int): Pair<Int, Int> {
        val side = (requestedSide.coerceIn(512, 1024) / 64) * 64
        val wide = action.containsMatchIn(description) ||
            Regex("""(?i)\b(group|couple|both|together|landscape|wide|panorama|crowd|several|team|two characters|three characters)\b""")
                .containsMatchIn(description)
        return if (wide) side to (side * 3 / 4 / 64 * 64) else side to side
    }

    /** Apply English lettering guidance conditionally when the input is cartoon artwork. */
    fun withEnglishTextForEditing(instruction: String): String =
        instruction.trim() +
            ". If the source image is a cartoon, comic panel, or illustration, " +
            "all visible words in speech bubbles, thought bubbles, captions, signs, " +
            "sound effects and labels must be legible, correctly spelled English only. " +
            "Translate any existing non-English text into natural English while keeping its meaning. " +
            "Preserve the original bubble positions, characters, setting and drawing style. " +
            "Do not add extra dialogue, gibberish, fake letters or captions that were not requested. " +
            "If the artwork contains no text and none was requested, keep it free of text."

    fun build(
        description: String,
        style: String,
        characters: String = "From description",
        maleClothing: String = "Auto",
        femaleClothing: String = "Auto"
    ): String {
        require(description.isNotBlank()) { "Describe the cartoon you want to create." }
        // Put the complete user specification FIRST. Diffusion checkpoints often
        // emphasize the first part of a prompt; long boilerplate used to bury it.
        val scene = description.trim()
        val chosenStyle = styleInDescription(scene) ?: style.trim().ifBlank { DEFAULT_STYLE }
        val retro2D = wantsRetroTwoD(chosenStyle)
        val requested3D = threeD.containsMatchIn(chosenStyle) && !retro2D
        val referenceComic = chosenStyle.contains("comic-book", ignoreCase = true)
        val autoNude = nudeWords.containsMatchIn(scene) && !noNudeWords.containsMatchIn(scene)
        val maleNude = explicitNude(scene, "man|men|male|males")
        val femaleNude = explicitNude(scene, "woman|women|female|females")
        val maleClothed = explicitClothed(scene, "man|men|male|males")
        val femaleClothed = explicitClothed(scene, "woman|women|female|females")
        val bothNude = Regex("""(?i)\b(?:man|men|male|males)\s+(?:and|&)\s+(?:woman|women|female|females)\s+(?:are\s+|both\s+)?(?:nude|naked|unclothed)\b|\b(?:woman|women|female|females)\s+(?:and|&)\s+(?:man|men|male|males)\s+(?:are\s+|both\s+)?(?:nude|naked|unclothed)\b|\b(?:everyone|everybody|both|all\s+(?:\d+\s+)?(?:characters?|adults?|people|heroes))\s+(?:are\s+|is\s+)?(?:nude|naked|unclothed)\b""")
            .containsMatchIn(scene)
        // Explicit scene text wins over a stale clothing selector. A request
        // for one nude person must not undress a differently described person.
        val effectiveMaleClothing = when {
            maleClothed -> "Clothed"
            maleNude || (autoNude && (bothNude || !femaleNude)) -> "Nude"
            else -> maleClothing
        }
        val effectiveFemaleClothing = when {
            femaleClothed -> "Clothed"
            femaleNude || (autoNude && (bothNude || !maleNude)) -> "Nude"
            else -> femaleClothing
        }
        val styleGuidance = when {
            referenceComic -> "Required look: bold ink outlines, flat colored 2D comic-book illustration, distinct expressive cartoon faces; not a photograph."
            retro2D -> "Required look: original flat hand-drawn 2D animation cels, strong ink outlines, simple expressive shapes, painted 1970s Saturday-morning background; not CGI."
            requested3D -> "Required look: stylized 3D animated cartoon, not live-action photography."
            else -> "Required look: clear, original illustrated cartoon art matching the visual style above, not a photograph."
        }
        val subjectGuidance = when (characters) {
            "Man" -> "Include the described adult man with consistent anatomy."
            "Woman" -> "Include the described adult woman with consistent anatomy."
            "Man and woman" -> "Include the described adult man and adult woman as separate characters, each with their own appearance."
            else -> "Follow the character identities and anatomy stated in the description."
        }
        val maleDirective = when (effectiveMaleClothing) {
            "Nude" -> "Every original adult male character is fully unclothed, with anatomically male features, without shorts, pants, underwear or other garments."
            "Clothed" -> "Every adult male character wears clothing as described."
            else -> ""
        }
        val femaleDirective = when (effectiveFemaleClothing) {
            "Nude" -> "Every original adult female character is fully unclothed, with anatomically female features and no garments."
            "Clothed" -> "Every adult female character wears clothing as described."
            else -> ""
        }
        val actionGuidance = if (action.containsMatchIn(scene)) {
            "Depict a single action frame with the described action actually happening; show interacting characters, not a static portrait."
        } else ""
        val textGuidance = if (letteringRequested(scene)) {
            "Any requested speech bubbles, thought bubbles, captions, signs or labels must use correctly spelled ENGLISH ONLY. Copy the exact dialogue and words from the description; no invented writing. Otherwise leave the illustration free of text."
        } else {
            "No speech bubbles, captions, labels or lettering. Otherwise leave the illustration free of text."
        }
        return listOf(
            "$scene. Visual style: $chosenStyle.",
            maleDirective,
            femaleDirective,
            subjectGuidance,
            actionGuidance,
            styleGuidance,
            "Include all named characters, setting, actions and objects. Keep clothing and body-coverage details consistent, without adding unwanted garments or swapping features between characters.",
            textGuidance
        ).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun negativePrompt(cartoonMode: Boolean, prompt: String, originalDescription: String = prompt): String {
        // Do not negatively prompt away letters when the user requested dialogue.
        val rawScene = originalDescription.substringBefore(". Visual style: ")
        val base = if (cartoonMode && letteringRequested(rawScene))
            negativePrompt(cartoonMode).replace("unwanted text, ", "") else negativePrompt(cartoonMode)
        if (!cartoonMode) return base
        val exclusions = if (wantsRetroTwoD(prompt)) {
            ", photograph, photographic realism, live action, 3D model, CGI render, realistic skin, plastic figures, modern 3D animation"
        } else ""
        val actionExclusions = if (action.containsMatchIn(prompt)) ", static portrait, posed group photo, standing in a row, police lineup" else ""
        // Costume-associated character names can make models draw uniforms even
        // when ALL described adults should be unclothed. This negative applies
        // only when both adult coverage groups are explicitly nude: it must not
        // suppress requested clothing on a different character.
        val allAdultsNude = prompt.contains("Every original adult male character is fully unclothed") &&
            prompt.contains("Every original adult female character is fully unclothed")
        val clothingExclusions = if (allAdultsNude) {
            ", clothing, superhero uniforms, bodysuits, costumes, boots, gloves, shorts, underwear, fabric coverings, censorship bars"
        } else ""
        return base + exclusions + actionExclusions + clothingExclusions + ", non-English lettering, foreign-language text, pseudo-letters, gibberish text, illegible lettering, misspelled speech bubbles, unrelated subjects, missing characters, duplicate characters, generic headshot, wrong setting"
    }

    fun negativePrompt(cartoonMode: Boolean): String =
        if (cartoonMode) {
            "blurry, low quality, watermark, unwanted text, visual artifacts, distorted anatomy, duplicate limbs"
        } else {
            "cartoon, anime, illustration, CGI, plastic skin, waxy skin, blurry, distorted anatomy, extra fingers, deformed face, watermark, text"
        }
}


/** Choose a drawing checkpoint installed in Nightmare; do not download or change photo mode. */
internal object CartoonModelSelector {
    data class Selection(val modelId: String?, val label: String)

    /** A suitable art model improves quality but is never required to generate. */
    fun chooseOrCurrent(models: org.json.JSONArray?, prompt: String): Selection {
        val match = models?.let { chooseInstalled(it, prompt) }
        return if (match.isNullOrBlank()) {
            Selection(null, "current Nightmare model (cartoon style may vary)")
        } else Selection(match, match)
    }

    fun chooseInstalled(models: org.json.JSONArray, prompt: String): String? {
        val retro = CartoonImagePrompt.wantsRetroTwoD(prompt)
        val requested3d = Regex("""(?i)\b(3d|cgi|three[\s-]?dimensional)\b""").containsMatchIn(prompt) && !retro
        var best: String? = null
        var topScore = 0
        for (i in 0 until models.length()) {
            val item = models.opt(i)
            val id: String
            val label: String
            when (item) {
                is org.json.JSONObject -> {
                    id = listOf("id", "model_id", "model", "path", "filename", "name")
                        .map { item.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
                    label = listOf(id, item.optString("name"), item.optString("title"),
                        item.optString("family"), item.optString("type")).joinToString(" ").lowercase()
                }
                is String -> {
                    id = item.trim()
                    label = id.lowercase()
                }
                else -> continue
            }
            if (id.isBlank()) continue
            if (Regex("""(inpaint|upscal|video|controlnet|vae|lora|flux|qwen|z.image)""").containsMatchIn(label)) continue
            // A photo checkpoint is not a solution to a flat 1970s cel-animation request.
            if (Regex("""(realvis|photoreal|photo.?real|realistic.?vision|cinematic.?photo)""").containsMatchIn(label)) continue
            val score = when {
                requested3d && Regex("""(3d|cgi|render|toon)""").containsMatchIn(label) -> 150
                requested3d -> 0
                !retro && !requested3d && Regex("""(cartoon|comic|toon|illustrat|anime)""").containsMatchIn(label) -> 140
                !requested3d && Regex("""(2d|cel.?anim|flat.?toon|hand.?drawn|saturday.?morning|retro.?cartoon)""").containsMatchIn(label) -> 140
                !requested3d && Regex("""(cartoon|comic|toon|illustrat)""").containsMatchIn(label) -> 110
                !requested3d && label.contains("dreamshaper") -> 85
                !requested3d && label.contains("pony") -> 75
                !requested3d && label.contains("illustrious") -> 65
                !requested3d && label.contains("anything") -> 60
                !requested3d && label.contains("animagine") -> 55
                else -> 0
            }
            if (score > topScore) { topScore = score; best = id }
        }
        return best
    }
}
