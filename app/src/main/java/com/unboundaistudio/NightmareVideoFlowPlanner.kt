package com.unboundaistudio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.abs

/**
 * Uses Nightmare's GET /flows/<id> schema to override the ACTUAL video node.
 * Top-level width/height knobs only apply to image samplers in Nightmare.
 */
internal object NightmareVideoFlowPlanner {
 data class Plan(
  val params: JSONObject,
  val imageNodeIds: Set<String>,
  val seconds: Int,
  val durationLabel: String,
  val width: Int,
  val height: Int
 )

 fun plan(schema: JSONObject, requestedSeconds: Int, targetSide: Int = 480): Plan {
  require(requestedSeconds in 1..10) { "Select 1–10 seconds" }
  val nodes = schema.optJSONArray("nodes") ?: error("Nightmare returned no workflow nodes")
  val allNodes = (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }
  val sourceImageNodes = allNodes.filter { it.optString("type") == "core.image" }
   .map { it.optString("id") }.toSet()
  val videoNodes = allNodes.filter {
   widget(it, "width") != null && widget(it, "height") != null && widget(it, "seconds") != null
  }
  check(videoNodes.isNotEmpty()) {
   "Selected Nightmare flow has no adjustable AI video node. Choose an Image to video Wan workflow in Nightmare."
  }

  val imageNodes = videoNodes.mapNotNull { n ->
   n.optJSONObject("inputs")?.optString("image")?.takeIf { it.isNotBlank() }
  }.filter { it in sourceImageNodes }.toSet()
  check(imageNodes.isNotEmpty()) {
   "Selected flow does not connect a photo to its video generator. Choose an Image to video flow, not Text to video."
  }

  val params = JSONObject()
  var duration = 0
  var durationLabel = ""
  var width = targetSide
  var height = targetSide
  for (node in videoNodes) {
   val w = safeSide(targetSide, widget(node, "width")!!)
   val h = safeSide(targetSide, widget(node, "height")!!)
   val secondsWidget = widget(node, "seconds")!!
   val maxSeconds = secondsWidget.optInt("max", requestedSeconds).takeIf { it > 0 } ?: requestedSeconds
   val partsWidget = widget(node, "parts")
   var parts = 1
   var perPart = requestedSeconds
   if (requestedSeconds > maxSeconds && partsWidget != null) {
    parts = ceil(requestedSeconds.toDouble() / maxSeconds).toInt()
    perPart = ceil(requestedSeconds.toDouble() / parts).toInt()
    val maxParts = partsWidget.optInt("max", 1)
    check(parts <= maxParts && perPart * parts == requestedSeconds) {
     "This workflow cannot create exactly ${requestedSeconds}s. Try 1, 2 or 5 seconds."
    }
   } else if (requestedSeconds > maxSeconds) {
    error("This workflow supports at most ${maxSeconds}s per video. Choose a shorter duration.")
   }
   val secondValue = lengthValue(secondsWidget, perPart)
   durationLabel = if (parts > 1) "${secondValue.toDoubleOrNull()?.times(parts) ?: requestedSeconds.toDouble()}" else secondValue
   val overrides = JSONObject().put("width", w.toString()).put("height", h.toString())
    .put("seconds", secondValue)
   if (parts > 1) overrides.put("parts", parts.toString())
   params.put(node.getString("id"), overrides)
   width = w
   height = h
   duration = requestedSeconds
  }
  return Plan(params, imageNodes, duration, durationLabel, width, height)
 }

 private fun widget(node: JSONObject, name: String): JSONObject? {
  val widgets: JSONArray = node.optJSONArray("widgets") ?: return null
  for (i in 0 until widgets.length()) {
   val w = widgets.optJSONObject(i) ?: continue
   if (w.optString("name") == name) return w
  }
  return null
 }

 private fun safeSide(target: Int, widget: JSONObject): Int {
  val min = widget.optInt("min", 320)
  val max = widget.optInt("max", 1280)
  val snapped = (target / 32) * 32
  return snapped.coerceIn(min, max)
 }

 private fun lengthValue(widget: JSONObject, requested: Int): String {
  val options = widget.optJSONArray("options")
  if (options != null && options.length() > 0) {
   val values = (0 until options.length()).map { options.optString(it) }
   // A model with a fixed menu (e.g. MiniMax) must receive its literal option,
   // rather than an invented duration the API will reject.
   val choice = values.mapNotNull { value -> value.toDoubleOrNull()?.let { value to it } }
    .minByOrNull { (_, n) -> abs(n - requested.toDouble()) }
   return choice?.first ?: error("Unsupported video duration choices: ${values.joinToString()}")
  }
  val min = widget.optInt("min", 0)
  val max = widget.optInt("max", requested)
  check(requested in min..max) { "Nightmare video duration must be within $min–$max seconds." }
  return requested.toString()
 }
}
