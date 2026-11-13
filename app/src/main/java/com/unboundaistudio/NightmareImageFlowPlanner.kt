package com.unboundaistudio

import org.json.JSONObject

/**
 * The Nightmare API indexes uploaded photos by the ID of a connected core.image
 * node. Custom and current flows need not use the built-in ID "image".
 * Never fill reference/control slots or guess a node that isn't in the graph.
 */
internal object NightmareImageFlowPlanner {
    fun connectedImageNodeIds(schema: JSONObject): Set<String> {
        val nodes = schema.optJSONArray("nodes")
            ?: schema.optJSONObject("graph")?.optJSONArray("nodes")
            ?: error("Nightmare did not provide the selected workflow's nodes.")
        val all = (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }
        val sources = all.filter { it.optString("type") == "core.image" }
            .mapNotNull { it.optString("id").takeIf(String::isNotBlank) }.toSet()
        check(sources.isNotEmpty()) {
            "This Nightmare workflow has no Image node. It cannot edit an uploaded photo."
        }
        val connected = all.mapNotNull { node ->
            node.optJSONObject("inputs")?.optString("image")
                ?.takeIf { it.isNotBlank() && it in sources }
        }.toSet()
        check(connected.isNotEmpty()) {
            "This Nightmare workflow's Image node is not connected to an image input."
        }
        return connected
    }

    /** Matches the exact class of HTTP 400 shown when an image node is absent. */
    fun missingImageNode(status: Int, body: String): Boolean =
        status == 400 && body.contains("images: no node", ignoreCase = true)
}
