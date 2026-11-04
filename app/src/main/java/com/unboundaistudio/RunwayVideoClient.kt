package com.unboundaistudio

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Runway image-to-video. Uses user's own API key; no keys are bundled in the APK. */
object RunwayVideoClient {
 private const val ROOT = "https://api.dev.runwayml.com"
 private fun request(url: String, key: String, method: String = "GET", body: JSONObject? = null): JSONObject {
  val conn = URL(url).openConnection() as HttpURLConnection
  try {
   conn.requestMethod = method
   conn.connectTimeout = 20000
   conn.readTimeout = 45000
   conn.setRequestProperty("Authorization", "Bearer $key")
   conn.setRequestProperty("X-Runway-Version", "2024-11-06")
   conn.setRequestProperty("Accept", "application/json")
   if (body != null) {
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
   }
   val code = conn.responseCode
   val response = (if (code in 200..299) conn.inputStream else conn.errorStream)
     ?.bufferedReader()?.use { it.readText() } ?: ""
   check(code in 200..299) { "Runway HTTP $code: ${response.take(400)}" }
   return JSONObject(response)
  } finally { conn.disconnect() }
 }
 fun generate(key: String, image: Bitmap, prompt: String, seconds: Int, onStatus: (String) -> Unit): ByteArray {
  require(key.isNotBlank()) { "Enter your Runway API key in the video settings." }
  require(seconds == 5 || seconds == 10) { "Runway supports 5 or 10 seconds in this mode." }
  require(prompt.isNotBlank()) { "Describe the movement first." }
  val scaled = if (image.width > 1024 || image.height > 1024) {
   val scale = 1024f / maxOf(image.width, image.height)
   Bitmap.createScaledBitmap(image, (image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1), true)
  } else image
  val jpg = ByteArrayOutputStream().also { check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, it)) }.toByteArray()
  if (scaled !== image) scaled.recycle()
  val data = "data:image/jpeg;base64," + Base64.encodeToString(jpg, Base64.NO_WRAP)
  val ratio = if (image.width >= image.height) "1280:720" else "720:1280"
  val task = request("$ROOT/v1/image_to_video", key, "POST", JSONObject()
   .put("model", "gen4_turbo")
   .put("promptImage", data)
   .put("promptText", prompt)
   .put("ratio", ratio)
   .put("duration", seconds))
  val id = task.getString("id")
  repeat(120) {
   Thread.sleep(5000)
   val state = request("$ROOT/v1/tasks/$id", key)
   when (state.optString("status")) {
    "SUCCEEDED" -> {
     val output = state.optJSONArray("output") ?: error("Runway returned no video.")
     val videoUrl = output.getString(0)
     val url = URL(videoUrl)
     require(url.protocol == "https") { "Unsafe video URL." }
     val conn = url.openConnection() as HttpURLConnection
     try {
      conn.connectTimeout = 20000
      conn.readTimeout = 120000
      check(conn.responseCode in 200..299) { "Video download HTTP ${conn.responseCode}" }
      val out = ByteArrayOutputStream()
      conn.inputStream.use { input ->
       val buf = ByteArray(8192)
       while (true) {
        val n = input.read(buf)
        if (n < 0) break
        check(out.size() + n <= 200 * 1024 * 1024) { "Video exceeds 200 MB." }
        out.write(buf, 0, n)
       }
      }
      return out.toByteArray()
     } finally { conn.disconnect() }
    }
    "FAILED", "CANCELLED" -> error("Runway task ${state.optString("status")}: ${state.optString("failure")}")
   }
   onStatus("Runway generating video… ${(it + 1) * 5}s elapsed")
  }
  error("Runway generation timed out after 10 minutes.")
 }
}
