package com.unboundaistudio

import org.json.JSONArray
import org.json.JSONObject

/**
 * Select a model that is ALREADY installed in Nightmare. Never downloads a
 * different model, changes a saved flow, or silently uses image-to-image restyling.
 */
internal object NightmareEditModelPicker {
    private val families = setOf("FLUX2", "FLUX3", "QWEN21", "FIBO2")
    private val fourB = Regex("(?i)(^|[^0-9])4b([^0-9]|$)")

    fun select(installed: JSONArray, speed: String): String? {
        val available = (0 until installed.length()).mapNotNull { installed.optJSONObject(it) }
            .filter { it.optString("family").uppercase() in families && it.optString("id").isNotBlank() }
        fun isFamily(model: JSONObject, name: String) = model.optString("family").equals(name, ignoreCase = true)
        fun is4B(model: JSONObject) =
            fourB.containsMatchIn(model.optString("id")) || fourB.containsMatchIn(model.optString("name"))
        val chosen = if (speed == "FAST") {
            // Klein 4B is much faster for instruction edits than a 1024px Qwen pass.
            available.firstOrNull { isFamily(it, "FLUX2") && is4B(it) }
                ?: available.firstOrNull { isFamily(it, "FLUX2") }
                ?: available.firstOrNull { isFamily(it, "FLUX3") }
                ?: available.firstOrNull { isFamily(it, "QWEN21") }
                ?: available.firstOrNull { isFamily(it, "FIBO2") }
        } else {
            // Preserve Qwen as the detailed-instruction option, if installed.
            available.firstOrNull { isFamily(it, "QWEN21") } ?: available.firstOrNull()
        }
        return chosen?.optString("id")
    }
}
