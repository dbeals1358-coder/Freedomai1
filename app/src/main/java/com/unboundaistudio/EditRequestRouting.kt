package com.unboundaistudio

/**
 * Decide between a genuine instruction-following edit and an ordinary img2img restyle.
 * Nightmares's img2img workflow is meant to re-imagine a photograph; it cannot be
 * relied on for a localized change to an existing garment or object.
 */
internal object EditRequestRouting {
    private val clothing = Regex(
        "\\b(bikini|swim\\s*suit|swimsuit|swimwear|bathing\\s*suit|bra|top|shirt|jacket|coat|sweater|hoodie|socks|hat|shorts|pants|dress|skirt|outfit|clothing|clothes|garment|underwear|sleeves?)\\b",
        RegexOption.IGNORE_CASE
    )
    private val restyle = Regex(
        "\\b(enhance|enhancement|upscale|sharpen|restore|realistic|photorealistic|high\\s+resolution|improve\\s+quality|stylize|restyle|oil\\s+painting|cartoon)\\b",
        RegexOption.IGNORE_CASE
    )
    private val change = Regex(
        "\\b(change|alter|turn|replace|remove|add|swap|recolor|resize|shrink|enlarge|shorten|lengthen|different|color|colour|red|blue|green|yellow|pink|white|black|purple|orange|nude|naked)\\b",
        RegexOption.IGNORE_CASE
    )
    fun clothingRequested(prompt: String): Boolean = clothing.containsMatchIn(prompt)

    /** Preserve identity for ordinary edits, even when the prompt says 'keep face same'. */
    fun shouldPreserveFace(prompt: String): Boolean {
        val actionToFace = Regex(
            """(?i)\b(change|edit|fix|retouch|enhance|improve|replace|remove|add|alter|sharpen|recolor|make|give)\b.{0,28}\b(face|facial|eyes?|eyebrows?|eyelashes?|nose|mouth|lips?|smil(?:e|ing)|hair|beard|wrinkles?|expression)\b"""
        )
        val faceToAction = Regex(
            """(?i)\b(face|eyes?|eyebrows?|eyelashes?|nose|mouth|lips?|hair|beard|expression)\b.{0,28}\b(blue|green|brown|red|blonde|black|smaller|larger|different|color|colour|smiling|smile|change|fix)\b"""
        )
        return !actionToFace.containsMatchIn(prompt) && !faceToAction.containsMatchIn(prompt)
    }

    /**
     * 'auto' defaults to Clothes auto-masked inpainting for garment modifications,
     * and flux_edit for all other instructions. Only a PURE style / quality request
     * selects img2img. Explicit flow selections are respected.
     */
    fun chooseFlow(selected: String, prompt: String): String {
        if (selected != "auto") return selected
        if (clothingRequested(prompt)) return "inpaint"
        if (restyle.containsMatchIn(prompt) && !change.containsMatchIn(prompt)) return "img2img"
        return "flux_edit"
    }

    /** Keep the requested change first: SD/SDXL models have a short CLIP prompt budget. */
    fun promptFor(flow: String, userInstruction: String): String = when (flow) {
        "inpaint" -> userInstruction.trim() +
            ". Photorealistic garment. The same adult subject, face, proportions, pose and lighting."
        "flux_edit" -> userInstruction.trim() +
            ". Make only this requested change. Keep the same subject, face, body, background and camera angle."
        else -> userInstruction.trim()
    }
}
