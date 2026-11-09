package com.unboundaistudio

/** Cartoon generation has its own prompt and negative prompt so photo defaults do not fight it. */
internal object CartoonImagePrompt {
    const val DEFAULT_STYLE = "Cartoon illustration"

    fun build(description: String, style: String, characters: String = "From description"): String {
        require(description.isNotBlank()) { "Describe the cartoon you want to create." }
        val chosenStyle = style.trim().ifBlank { DEFAULT_STYLE }
        val subjectGuidance = when (characters) {
            "Man" -> "For the human subject, preserve the described adult man's identity and anatomy."
            "Woman" -> "For the human subject, preserve the described adult woman's identity and anatomy."
            "Man and woman" -> "Show the described adult man and adult woman as separate characters; preserve each character's distinct anatomy and appearance."
            else -> "Follow the character identities and anatomy stated in the description."
        }
        return "${description.trim()}. Visual style: ${chosenStyle}. " +
            "Create original, high-quality cartoon artwork. Respect the requested characters, " +
            "composition, colors and details; do not force photographic realism. " +
            subjectGuidance + " Keep all characters' clothing and body-coverage details exactly as requested, " +
            "without inventing extra garments or swapping features between characters."
    }

    fun negativePrompt(cartoonMode: Boolean): String =
        if (cartoonMode) {
            "blurry, low quality, watermark, unwanted text, visual artifacts, distorted anatomy, duplicate limbs"
        } else {
            "cartoon, anime, illustration, CGI, plastic skin, waxy skin, blurry, distorted anatomy, extra fingers, deformed face, watermark, text"
        }
}
