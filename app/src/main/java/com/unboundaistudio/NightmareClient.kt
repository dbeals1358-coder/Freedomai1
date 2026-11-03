package com.unboundaistudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Nightmare v1.6 local API. Never sends the token to an external server. */
internal object NightmareClient {
 private const val BASE = "http://127.0.0.1:8820"
 private fun call(path: String, token: String, method: String = "GET", body: JSONObject? = null): ByteArray {
  require(token.isNotBlank()) { "Enter the Nightmare API token in connection settings" }
  val connection = URL(BASE + path).openConnection() as HttpURLConnection
  try {
   connection.requestMethod = method
   connection.connectTimeout = 8000
   connection.readTimeout = 300000
   connection.setRequestProperty("Authorization", "Bearer " + token.trim())
   if (body != null) {
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/json")
    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
   }
   val code = connection.responseCode
   if (code !in 200..299) {
    val message = connection.errorStream?.bufferedReader()?.use { it.readText().take(300) } ?: ""
    error("Nightmare HTTP $code: $message")
   }
   return connection.inputStream.use { it.readBytes() }
  } finally { connection.disconnect() }
 }
 fun check(token: String): String {
  val info = JSONObject(String(call("/info", token), Charsets.UTF_8))
  call("/models", token)
  return "Nightmare ${info.optString("version")} connected"
 }
 fun generate(token: String, prompt: String, source: String?, strength: Float): Bitmap {
  if (source == null) {
   val body = JSONObject().put("prompt", prompt).put("negative_prompt", "blurry, bad anatomy, watermark")
    .put("steps", 35).put("cfg_scale", 5.5).put("width", 1024).put("height", 1024)
   val response = JSONObject(String(call("/sdapi/v1/txt2img", token, "POST", body), Charsets.UTF_8))
   val images = response.getJSONArray("images")
   require(images.length() > 0) { "Nightmare returned no image" }
   val bytes = Base64.decode(images.getString(0), Base64.DEFAULT)
   return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Could not decode Nightmare image")
  }
  val body = JSONObject().put("flow", "img2img").put("prompt", prompt)
   .put("steps", 35).put("cfg", 5.5).put("denoise", strength.toDouble())
   .put("images", JSONObject().put("image", source))
  val response = JSONObject(String(call("/run", token, "POST", body), Charsets.UTF_8))
  val results = response.getJSONArray("results")
  require(results.length() > 0) { "Nightmare returned no edited image" }
  val path = results.getJSONObject(0).getString("url")
  require(path.startsWith("/results/") && !path.contains("..")) { "Unexpected result path" }
  val bytes = call(path, token)
  return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Could not decode edited image")
 }
}
