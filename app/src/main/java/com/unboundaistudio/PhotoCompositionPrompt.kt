package com.unboundaistudio

/**
 * Photo-only prompt guidance. Keep user scene and requested appearance intact.
 * No changes to cartoon generation or image editing.
 */
internal object PhotoCompositionPrompt {
    private val portrait = Regex("""(?i)\b(close[ -]?up|headshot|portrait|face[ -]?only|macro|tight crop)\b""")
    private val scene = Regex("""(?i)\b(with|beside|next to|together|couple|group|standing|sitting|walking|full body|full length|meeting|greeting|hugging|holding hands|talking|fighting|dancing)\b""")
    private val namedCharacters = Regex("""(?i)\b(batman|superman|supergirl|batgirl|catwoman|wonder woman|spider[ -]?man|iron man|captain america)\b""")
    private val multiple = Regex("""(?i)\b(two|three|four|five|six|seven|eight|nine|ten|[2-9]|both|pair|couple|duo|group|people|characters|women|men|girls|guys)\b""")

    fun prepare(raw: String): String {
        val prompt = raw.trim()
        if (prompt.isEmpty()) return prompt
        val realism = "Photographic quality: natural skin texture with subtle pores and fine detail, realistic facial features and eyes, believable individual facial variation, accurate hands and anatomy, physically plausible lighting and shadows, consistent reflections, realistic perspective, natural color and restrained contrast. Avoid waxy or plastic skin, excessive smoothing, artificial glossy highlights, identical faces, oversharpening, distorted bodies and synthetic-looking textures."
        if (portrait.containsMatchIn(prompt)) return "$prompt. $realism"
        val namedCount = namedCharacters.findAll(prompt).map { it.value.lowercase() }.distinct().count()
        val group = scene.containsMatchIn(prompt) || multiple.containsMatchIn(prompt) || namedCount >= 2
        if (!group) return "$prompt. $realism"
        return "$prompt. Photo composition: include every requested subject with distinct, consistent appearances in one coherent scene. Frame the group with enough room to see all requested subjects without cutting off people at the edges, unless the user explicitly asks for a close crop. Maintain the requested setting, action and viewpoint; do not add extra people or duplicate faces. $realism"
    }
}
