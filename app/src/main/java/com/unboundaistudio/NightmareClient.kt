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
    fun generate(prompt: String, token: String, input: android.graphics.Bitmap?, strength: Float, highQuality: Boolean, resolution: Int = 1024, editFlow: String = "current"): android.graphics.Bitmap {
        require(token.isNotBlank()) { "Enter your Nightmare API token in Connection settings" }
        require(!token.contains('\n') && !token.contains('\r')) { "Invalid Nightmare API token" }
        // Check authentication before starting a potentially long image generation request.
        authenticatedGet("/info", token)
        require(editFlow == "current" || editFlow == "img2img" || editFlow.startsWith("saved:")) { "Invalid edit workflow" }
        val payload = JSONObject()
            .put("flow", if (input == null) "saved:FreedomAI RealVis" else editFlow)
            .put("prompt", prompt)
            .put("steps", if (input != null) 60 else if (highQuality) 35 else 22)
            .put("cfg", if (input != null) 11.4 else 6.0)
            .put("width", if (input != null) 512 else resolution)
            .put("height", if (input != null) 512 else resolution)
        if (input != null) {
            val stream = java.io.ByteArrayOutputStream()
            input.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            payload.put("images", JSONObject().put("image", android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)))
            payload.put("denoise", strength.toDouble())
        }
        val connection = URL("$BASE_URL/run").openConnection() as HttpURLConnection
        val reply = try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 120000
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
            check(code in 200..299) { "Nightmare HTTP $code: ${body.take(350)}" }
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
