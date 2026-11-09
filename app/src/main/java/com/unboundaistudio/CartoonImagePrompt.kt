package com.unboundaistudio

/** Cartoon generation has its own prompt and negative prompt so photo defaults do not fight it. */
internal object CartoonImagePrompt {
    const val DEFAULT_STYLE = "Cartoon illustration"

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
        val chosenStyle = style.trim().ifBlank { DEFAULT_STYLE }
        val subjectGuidance = when (characters) {
            "Man" -> "For the human subject, preserve the described adult man's identity and anatomy."
            "Woman" -> "For the human subject, preserve the described adult woman's identity and anatomy."
            "Man and woman" -> "Show the described adult man and adult woman as separate characters; preserve each character's distinct anatomy and appearance."
            else -> "Follow the character identities and anatomy stated in the description."
        }
        // Apply clothing state separately. The saved RealVis model may still ignore instructions;
        // these directives make the request explicit, not a post-processing promise.
        val maleDirective = when (maleClothing) {
            "Nude" -> "Every original adult male character is fully unclothed, with visible anatomically male external body features, without shorts, pants, underwear, or other clothing."
            "Clothed" -> "Every adult male character wears clothing as described and has no accidental nudity."
            else -> ""
        }
        val femaleDirective = when (femaleClothing) {
            "Nude" -> "Every original adult female character is fully unclothed, with anatomically female body features, without dresses, underwear, or other clothing."
            "Clothed" -> "Every adult female character wears clothing as described and has no accidental nudity."
            else -> ""
        }
        return listOf(
            "${description.trim()}. Visual style: ${chosenStyle}.",
            "Create original, high-quality cartoon artwork. Respect the requested characters, composition, colors and details; do not force photographic realism.",
            subjectGuidance,
            maleDirective,
            femaleDirective,
            "Keep clothing and body-coverage details consistent for each person, without adding unwanted garments or swapping features between characters."
        ).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun negativePrompt(cartoonMode: Boolean): String =
        if (cartoonMode) {
            "blurry, low quality, watermark, unwanted text, visual artifacts, distorted anatomy, duplicate limbs"
        } else {
            "cartoon, anime, illustration, CGI, plastic skin, waxy skin, blurry, distorted anatomy, extra fingers, deformed face, watermark, text"
        }
}
