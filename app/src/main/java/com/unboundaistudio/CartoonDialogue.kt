package com.unboundaistudio

/**
 * Extract exactly supplied English text for deterministic cartoon lettering.
 * AI diffusion checkpoints are not reliable spelling engines.
 * This deliberately avoids inventing dialogue the user didn't provide.
 */
internal object CartoonDialogue {
    private val quoted = Regex(""""([^"\r\n]{1,140})"|“([^”\r\n]{1,140})”""")

    fun extract(description: String): List<String> {
        if (!CartoonImagePrompt.letteringRequested(description)) return emptyList()
        return quoted.findAll(description)
            .map { match -> (match.groups[1]?.value ?: match.groups[2]?.value).orEmpty().trim() }
            .filter { phrase ->
                phrase.any { it in 'A'..'Z' || it in 'a'..'z' } &&
                    phrase.all { it.code < 128 && (it.isLetterOrDigit() || it.isWhitespace() ||
                        it in ".,!?;:'()-/&+%$#@") }
            }
            .take(3)
            .toList()
    }
}
