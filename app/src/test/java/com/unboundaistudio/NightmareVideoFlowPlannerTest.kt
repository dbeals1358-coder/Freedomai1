package com.unboundaistudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NightmareVideoFlowPlannerTest {
 private fun schema(imageConnected: Boolean = true, options: String = "null") = JSONObject(
  """
  {
   "nodes": [
    {"id":"source_photo","type":"core.image","inputs":{},"widgets":[]},
    {"id":"video123","type":"wan.sample",
     "inputs":${if (imageConnected) """{"image":"source_photo"}""" else "{}"},
     "widgets":[
       {"name":"width","type":"int","min":320,"max":1280},
       {"name":"height","type":"int","min":320,"max":1280},
       {"name":"seconds","type":"int","min":0,"max":5,"options":$options},
       {"name":"parts","type":"int","min":1,"max":10}
     ]}
   ]
  }
  """.trimIndent()
 )

 @Test fun overridesActualVideoNodeAndCorrectImageId() {
  val plan = NightmareVideoFlowPlanner.plan(schema(), 2, 480)
  val node = plan.params.getJSONObject("video123")
  assertEquals("480", node.getString("width"))
  assertEquals("480", node.getString("height"))
  assertEquals("2", node.getString("seconds"))
  assertEquals(setOf("source_photo"), plan.imageNodeIds)
  assertEquals("2", plan.durationLabel)
 }

 @Test fun preservesImageOrientationForVideo() {
  val wide = NightmareVideoFlowPlanner.plan(schema(), 2, 480, 1600, 900)
  assertEquals("480", wide.params.getJSONObject("video123").getString("width"))
  assertEquals("320", wide.params.getJSONObject("video123").getString("height"))
  val tall = NightmareVideoFlowPlanner.plan(schema(), 2, 480, 900, 1600)
  assertEquals("320", tall.params.getJSONObject("video123").getString("width"))
  assertEquals("480", tall.params.getJSONObject("video123").getString("height"))
 }
 @Test fun reduceMemoryAndChainTenSeconds() {
  val low = NightmareVideoFlowPlanner.plan(schema(), 1, 384)
  assertEquals("384", low.params.getJSONObject("video123").getString("width"))
  assertEquals("1", low.params.getJSONObject("video123").getString("seconds"))
  val long = NightmareVideoFlowPlanner.plan(schema(), 10, 480)
  assertEquals("5", long.params.getJSONObject("video123").getString("seconds"))
  assertEquals("2", long.params.getJSONObject("video123").getString("parts"))
 }

 @Test fun fixedDurationModelsUseTheirSupportedChoice() {
  val p = NightmareVideoFlowPlanner.plan(schema(options = "[\"0.9\",\"1.6\",\"2.3\"]"), 2)
  assertEquals("2.3", p.params.getJSONObject("video123").getString("seconds"))
  assertEquals("2.3", p.durationLabel)
 }

 @Test fun refusesPhotoUnconnectedToVideoInsteadOfGeneratingAnotherPerson() {
  try {
   NightmareVideoFlowPlanner.plan(schema(imageConnected = false), 1)
   fail("Expected a photo wiring error")
  } catch (e: IllegalStateException) {
   assertTrue(e.message.orEmpty().contains("Image to video"))
  }
 }
}
