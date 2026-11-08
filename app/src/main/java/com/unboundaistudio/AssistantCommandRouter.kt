package com.unboundaistudio

/** Routes explicit creative requests; ordinary questions stay in chat. */
internal enum class AssistantCommand { CHAT, SEARCH_WEB, SEARCH_IMAGES, CREATE_IMAGE, EDIT_IMAGE, CREATE_VIDEO }

internal object AssistantCommandRouter {
    private val imageWords = Regex("""\b(image|picture|photo|photos|pic|cartoon|drawing|artwork|illustration|portrait)\b""", RegexOption.IGNORE_CASE)
    private val searchWords = Regex("""\b(search|find|look\s+up|browse|google)\b""", RegexOption.IGNORE_CASE)
    private val imageSearchWords = Regex("""\b(images?|photos?|pictures?|pics?)\b""", RegexOption.IGNORE_CASE)
    private val webWords = Regex("""\b(web|internet|online|websites?|news|articles?)\b""", RegexOption.IGNORE_CASE)
    private val videoWords = Regex("""\b(video|clip|animation|animate|footage)\b""", RegexOption.IGNORE_CASE)
    private val createWords = Regex("""\b(make|create|generate|draw|render|produce|turn|animate)\b""", RegexOption.IGNORE_CASE)
    private val editWords = Regex("""\b(edit|retouch|modify|replace|remove|change|enhance|upscale|fix)\b""", RegexOption.IGNORE_CASE)
    private val explanation = Regex("""^\s*(how|why|what|when|where|which|can you explain|could you explain)\b""", RegexOption.IGNORE_CASE)

    fun classify(input: String): AssistantCommand {
        val request = input.trim()
        if (request.isEmpty()) return AssistantCommand.CHAT
        if (searchWords.containsMatchIn(request) && imageSearchWords.containsMatchIn(request))
            return AssistantCommand.SEARCH_IMAGES
        if (request.startsWith("search ", ignoreCase = true) ||
            request.startsWith("google ", ignoreCase = true) ||
            (searchWords.containsMatchIn(request) && webWords.containsMatchIn(request)))
            return AssistantCommand.SEARCH_WEB
        if (explanation.containsMatchIn(request)) return AssistantCommand.CHAT
        if (videoWords.containsMatchIn(request) && createWords.containsMatchIn(request) &&
            !Regex("""\bvideo\s+game\b""", RegexOption.IGNORE_CASE).containsMatchIn(request))
            return AssistantCommand.CREATE_VIDEO
        if (editWords.containsMatchIn(request) &&
            (imageWords.containsMatchIn(request) ||
             Regex("""^\s*(edit|retouch|upscale|enhance|remove\s+background|change\s+(the\s+)?(shirt|clothes|clothing|hair))\b""", RegexOption.IGNORE_CASE).containsMatchIn(request)))
            return AssistantCommand.EDIT_IMAGE
        if (createWords.containsMatchIn(request) &&
            (imageWords.containsMatchIn(request) || Regex("""\bdraw\b""", RegexOption.IGNORE_CASE).containsMatchIn(request)))
            return AssistantCommand.CREATE_IMAGE
        return AssistantCommand.CHAT
    }
}
