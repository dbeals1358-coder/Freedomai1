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
    fun generate(prompt: String, token: String, input: android.graphics.Bitmap?, strength: Float, highQuality: Boolean): android.graphics.Bitmap {
        require(token.isNotBlank()) { "Enter your Nightmare API token in Connection settings" }
        val payload = JSONObject()
            .put("flow", if (input == null) "saved:FreedomAI RealVis" else "img2img")
            .put("prompt", prompt)
            .put("steps", if (highQuality) 35 else 22)
            .put("cfg", 6.0)
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
            connection.readTimeout = 600000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "Nightmare HTTP $code: ${body.take(250)}" }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
        val results = reply.optJSONArray("results") ?: error("Nightmare returned no results")
        val result = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .firstOrNull { it.optString("url").endsWith(".png", ignoreCase = true) }
            ?: error("Nightmare did not return a PNG image")
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
