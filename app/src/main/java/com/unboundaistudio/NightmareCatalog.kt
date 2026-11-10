package com.unboundaistudio

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Parse the server's own model and flow catalogs, never invent available options. */
internal object NightmareCatalog {
    data class Model(val id: String, val label: String)

    fun models(raw: String): List<Model> {
        val value = JSONTokener(raw).nextValue()
        val source = if (value is JSONObject)
            listOf("models", "items", "checkpoints", "data").asSequence()
                .mapNotNull { value.optJSONArray(it) }.firstOrNull()
                ?: JSONArray()
        else value as? JSONArray ?: JSONArray()
        return modelsFromArray(source)
    }

    fun modelsFromArray(items: JSONArray): List<Model> {
        val results = mutableListOf<Model>()
        for (i in 0 until items.length()) {
            val item = items.opt(i)
            val id: String
            val label: String
            when (item) {
                is String -> { id = item.trim(); label = id }
                is JSONObject -> {
                    id = listOf("id", "model_id", "model", "path", "filename", "name")
                        .map { item.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
                    label = item.optString("name").ifBlank { item.optString("title") }.ifBlank { id }
                }
                else -> continue
            }
            if (id.isNotBlank() && id != "null" && id.length <= 256) {
                results.add(Model(id, label.take(140)))
            }
        }
        return results.distinctBy { it.id }.take(80)
    }

    fun flows(raw: String): List<String> {
        val root = JSONTokener(raw).nextValue()
        val found = linkedSetOf<String>()
        fun add(value: String) {
            val name = value.trim()
            if (name.isNotEmpty() && name != "null" && name.length <= 180) found.add(name)
        }
        fun visit(value: Any?, depth: Int = 0) {
            if (depth > 6) return
            when (value) {
                is String -> add(value)
                is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i), depth + 1)
                is JSONObject -> {
                    val identifier = listOf("id", "flow_id", "flowId", "key", "slug", "name")
                        .map { value.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" }
                    if (identifier != null) add(identifier)
                    for (field in listOf("flows", "saved", "workflows", "items", "data", "results")) {
                        val child = value.opt(field)
                        if (child is JSONArray || child is JSONObject) visit(child, depth + 1)
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key in setOf("flows", "saved", "workflows", "items", "data", "results",
                                "nodes", "widgets", "params", "metadata", "info", "config")) continue
                        val child = value.opt(key)
                        if (child is JSONObject && (child.has("nodes") || child.has("widgets") ||
                                 child.has("flow") || child.has("name") || child.has("id"))) {
                            add(key)
                            // A named flow does not require loading its entire graph here.
                        }
                    }
                }
            }
        }
        visit(root)
        return found.toList().take(60)
    }
}
