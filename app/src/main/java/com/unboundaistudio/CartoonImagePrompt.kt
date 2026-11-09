package com.unboundaistudio

/** Cartoon generation has its own prompt and negative prompt so photo defaults do not fight it. */
internal object CartoonImagePrompt {
    const val DEFAULT_STYLE = "Cartoon illustration"

    fun build(description: String, style: String): String {
        require(description.isNotBlank()) { "Describe the cartoon you want to create." }
        val chosenStyle = style.trim().ifBlank { DEFAULT_STYLE }
        return "${description.trim()}. Visual style: ${chosenStyle}. " +
            "Create original, high-quality cartoon artwork. Respect the requested characters, " +
            "composition, colors and details; do not force photographic realism. " +
            "Keep each specified adult character's sex and anatomical features consistent. " +
            "Treat clothing and body-coverage instructions equally for adult men and adult women. " +
            "Do not add or remove clothing or substitute male and female anatomical traits unless requested."
    }

    fun negativePrompt(cartoonMode: Boolean): String =
        if (cartoonMode) {
            "blurry, low quality, watermark, unwanted text, visual artifacts, distorted anatomy, duplicate limbs"
        } else {
            "cartoon, anime, illustration, CGI, plastic skin, waxy skin, blurry, distorted anatomy, extra fingers, deformed face, watermark, text"
        }
}
