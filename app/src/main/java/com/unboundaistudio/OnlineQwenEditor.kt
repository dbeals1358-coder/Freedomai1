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
  val boundary = "FreedomAI-" + UUID.randomUUID().toString()
  val conn = url.openConnection() as HttpURLConnection
  try {
   conn.requestMethod = "POST"
   conn.connectTimeout = 20000
   conn.readTimeout = 180000
   conn.instanceFollowRedirects = false
   conn.doOutput = true
   conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
   if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
   val png = ByteArrayOutputStream().also { input.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
   conn.outputStream.use { out ->
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
   val image = JSONObject(response).getJSONArray("data").getJSONObject(0)
   val encoded = image.optString("b64_json")
   if (encoded.isNotBlank()) {
    val bytes = Base64.decode(encoded, Base64.DEFAULT)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid edited image returned.")
   }
   val imageUrl = image.optString("url")
   require(imageUrl.isNotBlank()) { "Provider returned no image data. Check image edits API compatibility." }
   val remote = URL(imageUrl)
   require(remote.protocol == "https") { "Provider returned an insecure image URL." }
   val imageConn = remote.openConnection() as HttpURLConnection
   try {
    imageConn.connectTimeout = 20000
    imageConn.readTimeout = 60000
    return imageConn.inputStream.use { BitmapFactory.decodeStream(it) } ?: error("Unable to decode edited image.")
   } finally { imageConn.disconnect() }
  } finally { conn.disconnect() }
 }
}
