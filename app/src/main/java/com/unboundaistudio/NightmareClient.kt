package com.unboundaistudio

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Nightmare v1 API on the same Android device.
 * The /info endpoint is public; flow execution requires an API token.
 * Do not log or embed the token in the APK.
 */
internal object NightmareClient {
    private const val BASE_URL = "http://127.0.0.1:8820"

    fun checkConnection(): String {
        val connection = URL("$BASE_URL/info").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            check(connection.responseCode == 200) {
                "Nightmare API returned HTTP ${connection.responseCode}"
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val info = JSONObject(body)
            check(info.optString("app") == "nightmare") {
                "Port 8820 is not running Nightmare"
            }
            check(info.optInt("api", -1) == 1) {
                "Unsupported Nightmare API version"
            }
            return info.optString("version", "unknown")
        } finally {
            connection.disconnect()
        }
    }

    fun authenticatedGet(path: String, token: String): String {
        require(path.startsWith("/") && !path.startsWith("//")) { "Invalid API path" }
        require(token.isNotBlank() && !token.contains('\n') && !token.contains('\r')) {
            "Enter a valid Nightmare API token"
        }
        val connection = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            val status = connection.responseCode
            check(status in 200..299) { "Nightmare API returned HTTP $status" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
    fun generate(prompt: String, token: String, input: android.graphics.Bitmap?, strength: Float, highQuality: Boolean, resolution: Int = 1024, editFlow: String = "auto"): android.graphics.Bitmap {
        require(token.isNotBlank()) { "Enter your Nightmare API token in Connection settings" }
        require(!token.contains('\n') && !token.contains('\r')) { "Invalid Nightmare API token" }
        authenticatedGet("/info", token)
        require(editFlow == "auto" || editFlow == "current" || editFlow == "img2img" ||
                editFlow == "inpaint" || editFlow == "flux_edit" || editFlow.startsWith("saved:")) {
            "Invalid Nightmare edit workflow: $editFlow"
        }
        val actualFlow = if (input == null) "saved:FreedomAI RealVis" else EditRequestRouting.chooseFlow(editFlow, prompt)
        val payload = JSONObject().put("flow", actualFlow)
            .put("prompt", if (input == null) prompt else EditRequestRouting.promptFor(actualFlow, prompt))

        if (input == null) {
            payload.put("steps", if (highQuality) 35 else 22)
                .put("cfg", 6.0).put("width", resolution).put("height", resolution)
        } else {
            val stream = java.io.ByteArrayOutputStream()
            check(input.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) {
                "Failed to encode original image for editing"
            }
            payload.put("images", JSONObject().put("image",
                android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)))

            when (actualFlow) {
                "inpaint" -> {
                    // Auto-mask clothing using Nightmare's 29 MB Clothes parser. No hand-painting.
                    // Its inpaint node composites the masked change onto the original photo.
                    // If the parser isn't installed, Nightmare refuses instead of silently
                    // running a full-frame img2img variation.
                    payload.put("denoise", strength.coerceAtLeast(0.60f).toDouble())
                        .put("steps", 35).put("cfg", 6.0)
                        .put("width", input.width).put("height", input.height)
                        .put("params", JSONObject().put("inpaint", JSONObject()
                            .put("pick_select", "true")
                            .put("auto_crop", "true")
                            .put("only_masked", "true")
                            .put("stitch", "true")))
                }
                "flux_edit" -> {
                    // Instruction-following edit is a DIFFERENT Nightmare recipe from img2img.
                    // Do not run an SDXL restyle and claim a requested object edit succeeded.
                    val installed = JSONObject(authenticatedGet("/models", token)).optJSONArray("models")
                        ?: error("Nightmare did not report its installed picture models.")
                    val supported = setOf("FLUX2", "FLUX3", "QWEN21", "FIBO2")
                    // Prefer an already-installed Qwen checkpoint; do not download a
                    // duplicate model into FreedomAI's private storage.
                    val candidates = (0 until installed.length()).mapNotNull { installed.optJSONObject(it) }
                        .filter { it.optString("family").uppercase() in supported }
                    val model = candidates.firstOrNull { it.optString("family").equals("QWEN21", true) }
                        ?.optString("id")
                        ?: candidates.firstOrNull()?.optString("id").orEmpty()
                    check(model.isNotBlank()) {
                        "Nightmare has no instruction-edit model installed. Open Nightmare → Models → Edit and install a compatible FLUX Klein or Qwen Image model. Your RealVis/SDXL image-to-image flow only restyles the photo."
                    }
                    // Respect the requested edit resolution instead of forcing 512 px.
                    // Higher sizes require more memory in Nightmare; report backend errors rather than silently downgrading.
                    val editSize = resolution.coerceIn(512, 1024) / 64 * 64
                    payload.put("model", model).put("cfg", 1.0).put("denoise", 1.0)
                        .put("width", editSize).put("height", editSize)
                }
                else -> {
                    // Explicit saved/current/img2img flows remain available for custom workflows.
                    payload.put("steps", 35).put("cfg", 6.0)
                        .put("width", input.width).put("height", input.height)
                        .put("denoise", strength.toDouble())
                }
            }
        }
        val connection = URL("$BASE_URL/run").openConnection() as HttpURLConnection
        val reply = try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 600000
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 500 && body.contains("backend did not start", ignoreCase = true)) {
                error("Nightmare could not start the processing backend for flow '${payload.optString("flow")}'. This is a Nightmare model/runtime startup failure, not a button or image-upload failure. Open that exact flow in Nightmare and run a simple image-to-image edit; check model loading, available RAM and Nightmare logs. Run ID: ${runCatching { JSONObject(body).optString("run") }.getOrDefault("unknown")}")
            }
            if (code !in 200..299 && actualFlow == "inpaint" &&
                (body.contains("parser", true) || body.contains("picked by name", true) ||
                 body.contains("auto mask", true))) {
                return try {
                    generate(prompt, token, input, strength, highQuality, resolution, "flux_edit")
                } catch (fallback: Exception) {
                    error("Clothes Auto Mask is unavailable and instruction-edit fallback failed: ${fallback.message}. Install Clothes Auto Mask in Nightmare Models > Tools, or a compatible instruction-edit model in Models > Edit. Original photo was not changed.")
                }
            }
            check(code in 200..299) { "Nightmare HTTP $code (flow: $actualFlow): ${body.take(350)}" }
            check(body.isNotBlank()) { "Nightmare returned an empty response" }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
        val results = reply.optJSONArray("results") ?: error("Nightmare response has no results: ${reply.toString().take(350)}")
        val result = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .firstOrNull { it.optString("url").endsWith(".png", ignoreCase = true) }
            ?: error("Nightmare did not return a PNG image: ${reply.toString().take(350)}")
        val resultPath = result.getString("url")
        require(resultPath.startsWith("/results/")) { "Unexpected Nightmare result path" }
        val download = URL("$BASE_URL$resultPath").openConnection() as HttpURLConnection
        try {
            download.connectTimeout = 10000
            download.readTimeout = 60000
            download.setRequestProperty("Authorization", "Bearer $token")
            check(download.responseCode == 200) { "Image download failed: HTTP ${download.responseCode}" }
            return download.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                ?: error("Nightmare returned an unreadable image")
        } finally {
            download.disconnect()
        }
    }

}
