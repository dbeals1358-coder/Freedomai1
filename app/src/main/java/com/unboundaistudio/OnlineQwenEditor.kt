package com.unboundaistudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Optional provider-backed image editing. Does not run Qwen model weights on the phone. */
object OnlineQwenEditor {
 fun edit(input: Bitmap, prompt: String, endpoint: String, model: String, key: String): Bitmap {
  val url = URL(endpoint.trim())
  require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) { "Use a valid HTTPS image edits API URL." }
  require(model.isNotBlank()) { "Enter an online image-edit model name." }
  val isAlibaba = url.host.endsWith(".aliyuncs.com") || url.host.endsWith(".aliyun.com")
  require(key.isNotBlank()) { "Enter your provider API key." }
  val boundary = "FreedomAI-" + UUID.randomUUID().toString()
  val conn = url.openConnection() as HttpURLConnection
  try {
   conn.requestMethod = "POST"
   conn.connectTimeout = 20000
   conn.readTimeout = 180000
   conn.instanceFollowRedirects = false
   conn.doOutput = true
   conn.setRequestProperty("Content-Type", if (isAlibaba) "application/json; charset=utf-8" else "multipart/form-data; boundary=$boundary")
   if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
   val png = ByteArrayOutputStream().also { input.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
   if (isAlibaba) {
    val image = "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
    val body = JSONObject().put("model", model.trim())
     .put("input", JSONObject().put("messages", org.json.JSONArray().put(
      JSONObject().put("role", "user").put("content", org.json.JSONArray()
       .put(JSONObject().put("image", image))
       .put(JSONObject().put("text", prompt))
      )
     )))
    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
   } else conn.outputStream.use { out ->
    fun part(name: String, value: String) {
     out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n").toByteArray())
    }
    part("model", model.trim())
    part("prompt", prompt)
    part("response_format", "b64_json")
    out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"photo.png\"\r\nContent-Type: image/png\r\n\r\n").toByteArray())
    out.write(png)
    out.write("\r\n--$boundary--\r\n".toByteArray())
   }
   val status = conn.responseCode
   if (status !in 200..299) {
    val message = conn.errorStream?.bufferedReader()?.use { it.readText().take(400) }.orEmpty()
    error("Provider HTTP $status: $message")
   }
   val response = conn.inputStream.bufferedReader().use { it.readText().take(25_000_000) }
   val json = JSONObject(response)
   if (isAlibaba) {
    val content = json.getJSONObject("output").getJSONArray("choices").getJSONObject(0)
     .getJSONObject("message").getJSONArray("content")
    var imageUrl = ""
    for (i in 0 until content.length()) {
     val item = content.optJSONObject(i) ?: continue
     if (item.optString("image").isNotBlank()) { imageUrl = item.getString("image"); break }
    }
    require(imageUrl.isNotBlank()) { "Alibaba returned no edited image." }
    return downloadImage(imageUrl)
   }
   val image = json.getJSONArray("data").getJSONObject(0)
   val encoded = image.optString("b64_json")
   if (encoded.isNotBlank()) {
    val bytes = Base64.decode(encoded, Base64.DEFAULT)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid edited image returned.")
   }
   val imageUrl = image.optString("url")
   require(imageUrl.isNotBlank()) { "Provider returned no image data. Check image edits API compatibility." }
   return downloadImage(imageUrl)
  } finally { conn.disconnect() }
 }
 private fun downloadImage(imageUrl: String): Bitmap {
  if (imageUrl.startsWith("data:image/")) {
   val bytes = Base64.decode(imageUrl.substringAfter(","), Base64.DEFAULT)
   return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid image data.")
  }
  val remote = URL(imageUrl)
  require(remote.protocol == "https" && remote.userInfo == null) { "Provider returned an insecure image URL." }
  val imageConn = remote.openConnection() as HttpURLConnection
  try {
   imageConn.connectTimeout = 20000
   imageConn.readTimeout = 60000
   imageConn.instanceFollowRedirects = false
   check(imageConn.responseCode in 200..299) { "Edited image download failed." }
   return imageConn.inputStream.use { BitmapFactory.decodeStream(it) } ?: error("Unable to decode edited image.")
  } finally { imageConn.disconnect() }
 }
}
