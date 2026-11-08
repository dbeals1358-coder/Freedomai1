package com.unboundaistudio

/**
 * Keeps photo-generation facial detail realistic without changing deliberately stylized prompts.
 * Super-resolution can upscale pixels, but it cannot fix facial anatomy at generation time.
 */
internal object FaceDetailEnhancement {
    const val REFINE_PROMPT =
        "Refine every visible adult face and eye in this photo to natural photographic realism. " +
        "Correct artificial-looking pupils, irises, eyelids, eye alignment and facial anatomy. " +
        "Keep believable catchlights, skin pores, subtle asymmetry, natural hairlines and expression. " +
        "Preserve the same people, identity, ages, pose, clothing, framing, lighting and background. " +
        "Change only facial realism, with no beauty filter, plastic skin or cartoon styling."

    private val human = Regex(
        """(?i)\b(person|people|woman|women|man|men|adult|portrait|faces?|selfie|couple|group of people|girl|boy)\b"""
    )
    private val stylized = Regex(
        """(?i)\b(anime|cartoon|illustration|drawing|sketch|oil painting|watercolor|3d render|cgi|stylized|doll|statue)\b"""
    )
    private const val DETAILS =
        "Natural photographic human facial detail: anatomically believable eyes, aligned pupils, " +
        "detailed irises, realistic eyelids and sclera, gentle catchlights, skin pores, natural " +
        "facial proportions, subtle asymmetry, realistic hairlines and unretouched skin texture; " +
        "avoid waxy skin, doll-like faces and artificial eyes."

    /** Applies only to realistic human generations; never forces photorealism onto artwork. */
    fun generationPrompt(prompt: String): String {
        val clean = prompt.trim()
        if (clean.isEmpty() || !human.containsMatchIn(clean) || stylized.containsMatchIn(clean)) return prompt
        if (clean.contains("Natural photographic human facial detail:", ignoreCase = true)) return clean
        return "$clean. $DETAILS"
    }
}
