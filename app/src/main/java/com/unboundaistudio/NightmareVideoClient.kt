package com.unboundaistudio

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** Drives Nightmare's real on-device image-to-video model through its documented local API. */
internal object NightmareVideoClient {
 private const val BASE = "http://127.0.0.1:8820"

 data class RenderedVideo(
  val bytes: ByteArray,
  val seconds: Int,
  val durationLabel: String,
  val width: Int,
  val height: Int,
  val reducedForMemory: Boolean
 )

 fun generate(token: String, flow: String, image: Bitmap, prompt: String, seconds: Int): RenderedVideo {
  require(token.isNotBlank() && !token.contains('\n') && !token.contains('\r')) {
   "Enter your Nightmare API token in connection settings"
  }
  require(flow == "current" || flow.startsWith("saved:")) {
   "Select a saved Nightmare image-to-video workflow or current"
  }
  require(seconds in 1..10) { "Choose 1–10 seconds for AI video" }
  NightmareClient.authenticatedGet("/info", token)

  // Unlike top-level width/height (which affect image samplers only),
  // params.<video node id> changes the actual Wan video generator.
  val encodedFlow = URLEncoder.encode(flow, "UTF-8")
  val schema = JSONObject(NightmareClient.authenticatedGet("/flows/$encodedFlow", token))

  val scale = minOf(1f, 480f / maxOf(image.width, image.height))
  val resized = if (scale < 1f) Bitmap.createScaledBitmap(
   image, maxOf(2, (image.width * scale).toInt()),
   maxOf(2, (image.height * scale).toInt()), true
  ) else image
  val png = try {
   ByteArrayOutputStream().use {
    check(resized.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not encode video source photo" }
    it.toByteArray()
   }
  } finally { if (resized !== image) resized.recycle() }
  val base64Image = Base64.encodeToString(png, Base64.NO_WRAP)

  var plan = NightmareVideoFlowPlanner.plan(schema, seconds, targetSide = 480)
  var reduced = false
  val response = try {
   run(token, flow, prompt, base64Image, plan)
  } catch (e: Exception) {
   if (!memoryError(e.message.orEmpty()) || (plan.seconds == 1 && plan.width <= 384 && plan.height <= 384)) throw e
   // A smaller video node and shorter clip substantially reduce NPU memory pressure.
   // Never silently retry with a different person or an image pan/zoom effect.
   plan = NightmareVideoFlowPlanner.plan(schema, 1, targetSide = 384)
   reduced = true
   try {
    run(token, flow, prompt, base64Image, plan)
   } catch (retry: Exception) {
    error("Nightmare ran out of video model memory even at ${plan.width}x${plan.height} / 1s. Use a smaller Nightmare video model. ${retry.message}")
   }
  }

  val results = response.optJSONArray("results") ?: error(
   "Nightmare did not return a video: " + response.optString("error", "no result")
  )
  val videoPath = (0 until results.length()).mapNotNull { results.optJSONObject(it)?.optString("url") }
   .firstOrNull { it.endsWith(".mp4", true) }
   ?: error("The selected Nightmare workflow did not produce an MP4. Choose an image-to-video flow with a video output.")
  require(videoPath.startsWith("/results/") && !videoPath.contains("..")) {
   "Unexpected Nightmare video result path"
  }
  val download = URL("$BASE$videoPath").openConnection() as HttpURLConnection
  try {
   download.connectTimeout = 15000
   download.readTimeout = 120000
   download.instanceFollowRedirects = false
   download.setRequestProperty("Authorization", "Bearer $token")
   check(download.responseCode == 200) { "Video download HTTP ${download.responseCode}" }
   val mp4 = download.inputStream.use { it.readBytes() }
   require(mp4.size >= 12 && String(mp4, 4, 4, Charsets.US_ASCII) == "ftyp") {
    "Nightmare returned an invalid MP4 file"
   }
   return RenderedVideo(mp4, plan.seconds, plan.durationLabel, plan.width, plan.height, reduced)
  } finally { download.disconnect() }
 }

 private fun run(
  token: String,
  flow: String,
  prompt: String,
  source: String,
  plan: NightmareVideoFlowPlanner.Plan
 ): JSONObject {
  val images = JSONObject()
  for (id in plan.imageNodeIds) images.put(id, source)
  val request = JSONObject().put("flow", flow).put("prompt", prompt)
   .put("images", images).put("params", plan.params)
  val connection = URL("$BASE/run").openConnection() as HttpURLConnection
  try {
   connection.requestMethod = "POST"
   connection.connectTimeout = 15000
   connection.readTimeout = 1_500_000
   connection.instanceFollowRedirects = false
   connection.doOutput = true
   connection.setRequestProperty("Authorization", "Bearer $token")
   connection.setRequestProperty("Content-Type", "application/json")
   connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
   val code = connection.responseCode
   val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
    ?.bufferedReader()?.use { it.readText() }.orEmpty()
   val reply = runCatching { JSONObject(body) }.getOrElse {
    error("Nightmare video HTTP $code: ${body.take(350)}")
   }
   if (code !in 200..299 || reply.has("error")) {
    error(reply.optString("error", "Nightmare video HTTP $code").take(600))
   }
   return reply
  } finally { connection.disconnect() }
 }

 private fun memoryError(message: String): Boolean =
  listOf("working memory", "NPU could not hold", "out of memory", "memory allocation", "not enough memory")
   .any { message.contains(it, ignoreCase = true) }
}
