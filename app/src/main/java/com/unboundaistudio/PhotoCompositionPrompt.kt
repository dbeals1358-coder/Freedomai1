package com.unboundaistudio

/**
 * Keep photo generation distinct from cartoon and image-edit workflows.
 * Improve framing for multi-subject scenes without replacing user wording.
 */
internal object PhotoCompositionPrompt {
    private val portrait = Regex("""(?i)\\b(close[ -]?up|headshot|portrait|face[ -]?only|macro|tight crop)\\b""")
    private val scene = Regex("""(?i)\\b(with|beside|next to|together|couple|group|two people|three people|standing|sitting|walking|full body|full length|meeting|greeting|hugging|holding hands|talking|fighting|dancing)\\b""")
    private val namedCharacters = Regex("""(?i)\\b(batman|superman|supergirl|batgirl|catwoman|wonder woman|spider[ -]?man|iron man|captain america)\\b""")
    private val twoSubjects = Regex("""(?i)\\b(two|both|pair|couple|duo|man and woman|woman and man|men and women|people|characters)\\b""")

    fun prepare(raw: String): String {
        val prompt = raw.trim()
        if (prompt.isEmpty() || portrait.containsMatchIn(prompt)) return prompt
        val namedCount = namedCharacters.findAll(prompt).map { it.value.lowercase() }.distinct().count()
        if (!scene.containsMatchIn(prompt) && !twoSubjects.containsMatchIn(prompt) && namedCount < 2) return prompt
        return prompt + ". Photo composition: preserve all explicitly named subjects and their distinct appearances in one coherent scene. Show each requested subject clearly, with enough framing to see their relative positions and the surrounding environment. Follow the described non-explicit actions and spatial relationships rather than substituting an unrelated static portrait. Use natural photographic lighting, consistent perspective, realistic anatomy and believable proportions. Do not add extra people or duplicate faces."
    }
}
