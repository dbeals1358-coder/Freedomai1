package com.unboundaistudio

/** Cartoon generation has its own prompt and negative prompt so photo defaults do not fight it. */
internal object CartoonImagePrompt {
    const val DEFAULT_STYLE = "Cartoon illustration"

    fun build(description: String, style: String): String {
        require(description.isNotBlank()) { "Describe the cartoon you want to create." }
        val chosenStyle = style.trim().ifBlank { DEFAULT_STYLE }
        return "${description.trim()}. Visual style: ${chosenStyle}. " +
            "Create original, high-quality cartoon artwork. Respect the requested characters, " +
            "composition, colors and details; do not force photographic realism."
    }

    fun negativePrompt(cartoonMode: Boolean): String =
        if (cartoonMode) {
            "blurry, low quality, watermark, unwanted text, visual artifacts, distorted anatomy, duplicate limbs"
        } else {
            "cartoon, anime, illustration, CGI, plastic skin, waxy skin, blurry, distorted anatomy, extra fingers, deformed face, watermark, text"
        }
}
