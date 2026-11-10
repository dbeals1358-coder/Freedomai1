package com.unboundaistudio

/**
 * Preserve the user's full text and avoid the portrait-closeup bias for prompts
 * describing scenes with multiple subjects. Never modify edit or cartoon prompts.
 */
internal object PhotoCompositionPrompt {
    private val portrait = Regex("""(?i)\\b(close[ -]?up|headshot|portrait|face[ -]?only|macro|tight crop)\\b""")
    private val scene = Regex("""(?i)\\b(with|beside|next to|together|couple|group|two people|three people|standing|sitting|walking|full body|full length)\\b""")

    fun prepare(raw: String): String {
        val prompt = raw.trim()
        if (prompt.isEmpty() || portrait.containsMatchIn(prompt) || !scene.containsMatchIn(prompt)) return prompt
        return prompt + ". Composition: show the subjects and their described interaction in the same frame; use a medium-wide or wider camera view rather than an isolated close-up of faces. Preserve the requested number of subjects, their relative positions, and the scene context. Photorealistic detail and natural anatomy."
    }
}
