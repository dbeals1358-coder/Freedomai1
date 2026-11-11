package com.unboundaistudio

/**
 * Extract exactly supplied English text for deterministic cartoon lettering.
 * AI diffusion checkpoints are not reliable spelling engines.
 * This deliberately avoids inventing dialogue the user didn't provide.
 */
internal object CartoonDialogue {
    private val quoted = Regex(""""([^"\r\n]{1,140})"|“([^”\r\n]{1,140})”""")
    private val nameBeforeQuote = Regex("""(?i)\b(?:called|named|titled|known as)\s*$""")

    private fun phrase(match: MatchResult): String =
        (match.groups[1]?.value ?: match.groups[2]?.value).orEmpty().trim()

    /**
     * Treat a full uppercase quoted sentence as intended cartoon dialogue,
     * even when the person did not type "says" or "speech bubble".
     * Do not mistake quoted character names for speech.
     */
    fun hasImplicitQuotedDialogue(description: String): Boolean =
        quoted.findAll(description).any { match ->
            val words = Regex("""[A-Za-z]+""").findAll(phrase(match))
                .map { it.value }.toList()
            val preceding = description.substring(0, match.range.first).takeLast(40)
            words.size >= 2 && words.any { it.length >= 2 } &&
                words.all { it == it.uppercase() } &&
                !nameBeforeQuote.containsMatchIn(preceding)
        }

    fun extract(description: String): List<String> {
        if (!CartoonImagePrompt.letteringRequested(description)) return emptyList()
        return quoted.findAll(description)
            .map(::phrase)
            .filter { phrase ->
                phrase.any { it in 'A'..'Z' || it in 'a'..'z' } &&
                    phrase.all { it.code < 128 && (it.isLetterOrDigit() || it.isWhitespace() ||
                        it in ".,!?;:'()-/&+%$#@") }
            }
            .take(3)
            .toList()
    }
}
