package com.unboundaistudio

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import org.json.JSONObject

internal data class LocalFrame(val width: Int, val height: Int, val pixels: IntArray)

/** Local Dream's /generate returns SSE containing raw RGB, not encoded PNG. */
internal object LocalDreamClient {
 private const val BASE = "http://127.0.0.1:8081"
 private const val LIMIT = 24 * 1024 * 1024

 private fun connect(path: String, payload: JSONObject): HttpURLConnection {
  val c = URL(BASE + path).openConnection() as HttpURLConnection
  try {
   c.requestMethod = "POST"
   c.connectTimeout = 5000
   c.readTimeout = 180000
   c.instanceFollowRedirects = false
   c.doOutput = true
   c.setRequestProperty("Content-Type", "application/json")
   c.setRequestProperty("Accept", "text/event-stream, application/json")
   c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
   check(c.responseCode in 200..299) { "Model server returned HTTP ${c.responseCode}" }
   return c
  } catch (e: Exception) { c.disconnect(); throw e }
 }

 fun checkConnection() {
  val c = connect("/tokenize", JSONObject().put("prompt", "cat"))
  try {
   c.inputStream.use { stream ->
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (true) {
     val n = stream.read(buffer)
     if (n < 0) break
     check(out.size() + n <= 16384) { "Unexpected connection response" }
     out.write(buffer, 0, n)
    }
    check(JSONObject(out.toString("UTF-8")).has("count")) { "Unexpected model server" }
   }
  } finally { c.disconnect() }
 }

 fun generate(prompt: String, sourceImage: String?, progress: (Int, Int) -> Unit): LocalFrame {
  val payload = JSONObject().put("prompt", prompt).put("negative_prompt", "blurry, watermark, jpeg artifacts")
   .put("steps", 20).put("cfg", 7.5).put("size", 1024).put("aspect_ratio", "1:1")
   .put("scheduler", "dpm").put("show_diffusion_process", false)
  if (sourceImage != null) payload.put("image", sourceImage).put("denoise_strength", 0.55)
  val c = connect("/generate", payload)
  try { return c.inputStream.use { readStream(it, progress) } }
  finally { c.disconnect() }
 }

 internal fun readStream(stream: InputStream, progress: (Int, Int) -> Unit): LocalFrame {
  // Bound a line before allocating its JSON/base64 content, including a malformed stream.
  val reader = stream.bufferedReader(Charsets.UTF_8)
  val event = StringBuilder()
  fun consume(): LocalFrame? {
   if (event.isEmpty()) return null
   val text = event.toString()
   event.setLength(0)
   if (text.trim() == "[DONE]") return null
   val json = JSONObject(text)
   return when (json.optString("type")) {
    "progress" -> { progress(json.optInt("step"), json.optInt("total_steps")); null }
    "error" -> error(json.optString("message", "Generation failed"))
    "complete" -> decodeFrame(json)
    else -> null
   }
  }
  val line = StringBuilder()
  while (true) {
   val char = reader.read()
   if (char == -1) {
    if (line.startsWith("data:")) event.append(line.substring(5).trimStart())
    return consume() ?: error("Connection ended before the image arrived")
   }
   if (char == 10) {
    val value = line.toString().removeSuffix("\r")
    line.setLength(0)
    if (value.isEmpty()) {
     consume()?.let { return it }
    } else if (value.startsWith("data:")) {
     if (event.isNotEmpty()) event.append('\n')
     check(event.length.toLong() + value.length <= LIMIT) { "Image response too large" }
     event.append(value.substring(5).trimStart())
    }
   } else {
    check(line.length < LIMIT) { "Image response too large" }
    line.append(char.toChar())
   }
  }
 }

 private fun decodeFrame(json: JSONObject): LocalFrame {
  val width = json.getInt("width")
  val height = json.getInt("height")
  val channels = json.getInt("channels")
  require(width in 1..2048 && height in 1..2048 && channels in 3..4) { "Unsupported image dimensions" }
  val bytes = Base64.getDecoder().decode(json.getString("image"))
  require(bytes.size.toLong() == width.toLong() * height * channels) { "Incomplete image pixels" }
  val pixels = IntArray(width * height) { i ->
   val offset = i * channels
   val alpha = if (channels == 4) bytes[offset + 3].toInt() and 255 else 255
   (alpha shl 24) or ((bytes[offset].toInt() and 255) shl 16) or
    ((bytes[offset + 1].toInt() and 255) shl 8) or (bytes[offset + 2].toInt() and 255)
  }
  return LocalFrame(width, height, pixels)
 }
}
