package com.unboundaistudio

/** Cartoon generation has its own prompt and negative prompt so photo defaults do not fight it. */
internal object CartoonImagePrompt {
    const val DEFAULT_STYLE = "Cartoon illustration"

    private val retroStyle = Regex("""(?i)(hanna[\s-]?barbera|1970'?s|70'?s|seventies|retro|vintage|hand[\s-]?drawn|classic animation|saturday morning|2d|cel[\s-]?animation)""")
    private val threeD = Regex("""(?i)\b(3d|cgi|photorealistic|three[\s-]?dimensional)\b""")
    private val action = Regex("""(?i)\b(fighting|fight|chasing|chase|rescuing|rescue|battling|battle|running|jumping|flying|escaping|pursuing|saving)\b""")
    private val nudeWords = Regex("""(?i)\b(nude|naked|unclothed|undressed)\b|remove (all )?cloth(?:es|ing)|without (any )?cloth(?:es|ing)""")
    private val noNudeWords = Regex("""(?i)\b(no|not|never)\s+(nude|naked|nudity|undressed)\b""")

    fun wantsRetroTwoD(text: String): Boolean =
        retroStyle.containsMatchIn(text) && !threeD.containsMatchIn(text)

    /** Never reuse a current or saved image workflow that might have a gallery photo attached. */
    fun generationFlow(): String = "txt2img"

    fun build(
        description: String,
        style: String,
        characters: String = "From description",
        maleClothing: String = "Auto",
        femaleClothing: String = "Auto"
    ): String {
        require(description.isNotBlank()) { "Describe the cartoon you want to create." }
        val enteredStyle = style.trim().ifBlank { DEFAULT_STYLE }
        // The new written prompt wins over an old saved UI style. For example,
        // "70's Hanna-Barbera" must not render in 3D just because 3D was selected earlier.
        val descriptionRequestsRetro = retroStyle.containsMatchIn(description) &&
            !threeD.containsMatchIn(description)
        val chosenStyle = if (descriptionRequestsRetro && threeD.containsMatchIn(enteredStyle))
            "1970s flat hand-drawn 2D animation cels" else enteredStyle
        val retro2D = wantsRetroTwoD(description + " " + chosenStyle)
        val autoNude = nudeWords.containsMatchIn(description) && !noNudeWords.containsMatchIn(description)
        val effectiveMaleClothing = if (maleClothing == "Auto" && autoNude) "Nude" else maleClothing
        val effectiveFemaleClothing = if (femaleClothing == "Auto" && autoNude) "Nude" else femaleClothing
        val styleGuidance = if (retro2D) {
            "Required look: authentic flat hand-drawn 2D animation cels from a 1970s Saturday-morning adventure cartoon, thick clean ink outlines, simple shapes, flat painted colors, expressive faces, illustrated background, an actual frame of traditional television animation."
        } else {
            "Create original, high-quality cartoon artwork. Respect the requested characters, composition, colors and details; do not force photographic realism."
        }
        val actionGuidance = if (action.containsMatchIn(description)) {
            "Show a single action frame from the story, with visible movement and characters actively interacting: illustrate the described crime-fighting or other action actually happening. No static poses, studio portraits, character lineup, or costume showcase."
        } else ""
        val subjectGuidance = when (characters) {
            "Man" -> "For the human subject, preserve the described adult man's identity and anatomy."
            "Woman" -> "For the human subject, preserve the described adult woman's identity and anatomy."
            "Man and woman" -> "Show the described adult man and adult woman as separate characters; preserve each character's distinct anatomy and appearance."
            else -> "Follow the character identities and anatomy stated in the description."
        }
        // Apply clothing state separately. The saved RealVis model may still ignore instructions;
        // these directives make the request explicit, not a post-processing promise.
        val maleDirective = when (effectiveMaleClothing) {
            "Nude" -> "Every original adult male character is fully unclothed, with visible anatomically male external body features, without shorts, pants, underwear, or other clothing."
            "Clothed" -> "Every adult male character wears clothing as described and has no accidental nudity."
            else -> ""
        }
        val femaleDirective = when (effectiveFemaleClothing) {
            "Nude" -> "Every original adult female character is fully unclothed, with anatomically female body features, without dresses, underwear, or other clothing."
            "Clothed" -> "Every adult female character wears clothing as described and has no accidental nudity."
            else -> ""
        }
        return listOf(
            "${description.trim()}. Visual style: ${chosenStyle}.",
            styleGuidance,
            actionGuidance,
            subjectGuidance,
            maleDirective,
            femaleDirective,
            "Keep clothing and body-coverage details consistent for each person, without adding unwanted garments or swapping features between characters."
        ).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun negativePrompt(cartoonMode: Boolean, prompt: String): String {
        val base = negativePrompt(cartoonMode)
        if (!cartoonMode) return base
        val exclusions = if (wantsRetroTwoD(prompt)) {
            ", photograph, photographic realism, live action, 3D model, CGI render, realistic skin, plastic figures, modern 3D animation"
        } else ""
        val actionExclusions = if (action.containsMatchIn(prompt)) ", static portrait, posed group photo, standing in a row, police lineup" else ""
        return base + exclusions + actionExclusions
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
        if (!CartoonImagePrompt.wantsRetroTwoD(prompt)) return null
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
                Regex("""(2d|cel.?anim|flat.?toon|hand.?drawn|saturday.?morning|retro.?cartoon)""").containsMatchIn(label) -> 140
                Regex("""(cartoon|comic|toon|illustrat)""").containsMatchIn(label) -> 110
                label.contains("dreamshaper") -> 85
                label.contains("pony") -> 75
                label.contains("illustrious") -> 65
                label.contains("anything") -> 60
                label.contains("animagine") -> 55
                else -> 0
            }
            if (score > topScore) { topScore = score; best = id }
        }
        return best
    }
}
