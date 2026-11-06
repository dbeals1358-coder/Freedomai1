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
  require(flow == "auto" || flow == "current" || flow.startsWith("saved:")) {
   "Select a saved Nightmare image-to-video workflow or current"
  }
  require(seconds in 1..10) { "Choose 1–10 seconds for AI video" }
  NightmareClient.authenticatedGet("/info", token)

  // Unlike top-level width/height (which affect image samplers only),
  // params.<video node id> changes the actual Wan video generator.
  val selected = if (flow == "auto" || flow == "current") findVideoFlow(token, flow) else flow
  val encodedFlow = URLEncoder.encode(selected, "UTF-8")
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
   run(token, selected, prompt, base64Image, plan)
  } catch (e: Exception) {
   if (!memoryError(e.message.orEmpty()) || plan.width <= 384 && plan.height <= 384) throw e
   // A smaller video node and shorter clip substantially reduce NPU memory pressure.
   // Never silently retry with a different person or an image pan/zoom effect.
   plan = NightmareVideoFlowPlanner.plan(schema, seconds, targetSide = 384)
   reduced = true
   try {
    run(token, selected, prompt, base64Image, plan)
   } catch (retry: Exception) {
    error("Nightmare ran out of video model memory even at ${plan.width}x${plan.height} / ${seconds}s. Use a smaller Nightmare video model. ${retry.message}")
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
   val mp4 = download.inputStream.use { input ->
    ByteArrayOutputStream().use { output ->
     val buffer = ByteArray(65536)
     while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      check(output.size() + count <= 200 * 1024 * 1024) { "Video exceeds 200MB" }
      output.write(buffer, 0, count)
     }
     output.toByteArray()
    }
   }
   require(mp4.size >= 12 && String(mp4, 4, 4, Charsets.US_ASCII) == "ftyp") {
    "Nightmare returned an invalid MP4 file"
   }
   return RenderedVideo(mp4, plan.seconds, plan.durationLabel, plan.width, plan.height, reduced)
  } finally { download.disconnect() }
 }

 private fun findVideoFlow(token: String, preferred: String): String {
  val raw = NightmareClient.authenticatedGet("/flows", token)
  val candidates = mutableListOf<String>()
  if (preferred == "current") candidates.add("current")
  // Nightmare releases vary in their catalog shape: root arrays, nested lists,
  // or objects keyed by workflow ID. Collect server-provided identifiers only.
  fun addCandidate(value: String) {
   val name = value.trim()
   if (name.isBlank() || name == "null") return
   candidates.add(name)
   if (name != "current" && !name.startsWith("saved:")) candidates.add("saved:$name")
  }
  fun visit(value: Any?, depth: Int = 0) {
   if (depth > 6) return
   when (value) {
    is org.json.JSONArray -> for (i in 0 until value.length()) visit(value.opt(i), depth + 1)
    is JSONObject -> {
     for (field in listOf("id", "key", "slug", "flow_id", "flowId", "name")) {
      val candidate = value.optString(field)
      if (candidate.isNotBlank()) { addCandidate(candidate); break }
     }
     for (field in listOf("flows", "saved", "workflows", "recommended", "items", "data", "results")) {
      val child = value.opt(field)
      if (child is JSONObject || child is org.json.JSONArray) visit(child, depth + 1)
     }
     // A keyed catalog maps workflow IDs to schemas or metadata.
     if (value.has("nodes") || value.has("widgets")) return
     val keys = value.keys()
     while (keys.hasNext()) {
      val key = keys.next()
      if (key in listOf("flows", "saved", "workflows", "recommended", "items", "data", "results")) continue
      val child = value.opt(key)
      if (child is JSONObject && (child.has("nodes") || child.has("widgets") || child.has("name") || child.has("id"))) {
       addCandidate(key)
      }
     }
    }
    is String -> addCandidate(value)
   }
  }
  val catalog = org.json.JSONTokener(raw).nextValue()
  visit(catalog)
  candidates.add("current")
  // Video workflows first; image-only RealVis must not be used as a video fallback.
  val ordered = candidates.distinct().filterNot {
   it.contains("FreedomAI RealVis", ignoreCase = true)
  }.sortedWith(compareByDescending<String> {
   it.contains("image to video", true) || it.contains("image-to-video", true) ||
    it.contains("i2v", true) || it.contains("wan", true) || it.contains("video", true)
  })
  val diagnostics = mutableListOf<String>()
  for (candidate in ordered) {
   try {
    val encoded = URLEncoder.encode(candidate, "UTF-8")
    val schema = JSONObject(NightmareClient.authenticatedGet("/flows/$encoded", token))
    NightmareVideoFlowPlanner.plan(schema, 2)
    return candidate
   } catch (e: Exception) { diagnostics.add("$candidate: ${e.message.orEmpty().take(120)}") }
  }
  error("No compatible image-to-video workflow found. Checked ${ordered.size} candidate flows. ${diagnostics.take(3).joinToString(" | ")}. No compatible installed Nightmare video workflow was detected. Select/install an image-to-video workflow in Nightmare; RealVis is image-only. MobileI2V is not available until its inference pipeline is implemented.")
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
