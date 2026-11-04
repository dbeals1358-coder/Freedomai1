package com.unboundaistudio

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Runs a genuine local image-to-video workflow using Nightmare's on-device inference backend. */
internal object NightmareVideoClient {
 private const val BASE = "http://127.0.0.1:8820"
 fun generate(token: String, flow: String, image: Bitmap, prompt: String, seconds: Int): ByteArray {
  require(token.isNotBlank()) { "Enter Nightmare API token in connection settings" }
  require(!token.contains('\n') && !token.contains('\r')) { "Invalid token" }
  require(flow == "current" || flow.startsWith("saved:")) { "Select a saved Nightmare video workflow or current" }
  require(seconds in 1..10) { "Choose 1–10 seconds for AI video" }
  NightmareClient.authenticatedGet("/info", token)
  val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
  val request = JSONObject().put("flow", flow).put("prompt", prompt)
   .put("images", JSONObject().put("image", Base64.encodeToString(bytes, Base64.NO_WRAP)))
   .put("duration", seconds).put("duration_seconds", seconds)
  val connection = URL("$BASE/run").openConnection() as HttpURLConnection
  val response = try {
   connection.requestMethod = "POST"
   connection.connectTimeout = 15000
   connection.readTimeout = 600000
   connection.doOutput = true
   connection.setRequestProperty("Authorization", "Bearer $token")
   connection.setRequestProperty("Content-Type", "application/json")
   connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
   val code = connection.responseCode
   val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
    ?.bufferedReader()?.use { it.readText() }.orEmpty()
   check(code in 200..299) { "Nightmare video HTTP $code: ${body.take(350)}" }
   JSONObject(body)
  } finally { connection.disconnect() }
  val results = response.optJSONArray("results") ?: error("Nightmare returned no video results")
  val videoPath = (0 until results.length()).mapNotNull { results.optJSONObject(it)?.optString("url") }
   .firstOrNull { it.endsWith(".mp4", true) }
   ?: error("Selected Nightmare workflow did not output MP4. Select an image-to-video workflow with an MP4 output node.")
  require(videoPath.startsWith("/results/") && !videoPath.contains("..")) { "Unexpected video result path" }
  val download = URL("$BASE$videoPath").openConnection() as HttpURLConnection
  try {
   download.connectTimeout = 15000
   download.readTimeout = 120000
   download.setRequestProperty("Authorization", "Bearer $token")
   check(download.responseCode == 200) { "Video download HTTP ${download.responseCode}" }
   val mp4 = download.inputStream.use { it.readBytes() }
   require(mp4.size >= 12 && String(mp4, 4, 4, Charsets.US_ASCII) == "ftyp") { "Invalid MP4 returned" }
   return mp4
  } finally { download.disconnect() }
 }
}
