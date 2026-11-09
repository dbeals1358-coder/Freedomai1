package com.unboundaistudio

import org.junit.Assert.*
import org.junit.Test

class NightmareEditGeometryTest {
 @Test fun landscapeInputKeepsAspectWithoutBands() {
  val p = NightmareEditGeometry.plan(1920, 1080, 1024)
  assertEquals(1024, p.width)
  assertEquals(576, p.height)
  assertEquals(0, p.left)
  assertEquals(0, p.top)
  assertEquals(NightmareEditGeometry.Bounds(0, 0, 1024, 576), p.boundsIn(1024, 576))
 }
 @Test fun fastPortraitFrameRemovesPaddingAtOutput() {
  val p = NightmareEditGeometry.plan(720, 1280, 512)
  assertEquals(288, p.contentWidth)
  assertEquals(512, p.contentHeight)
  assertEquals(512, p.width)
  assertEquals(112, p.left)
  assertEquals(NightmareEditGeometry.Bounds(112, 0, 288, 512), p.boundsIn(512, 512))
 }
 @Test fun outputBoundsScaleWhenModelReturnsOtherResolution() {
  val p = NightmareEditGeometry.plan(1280, 720, 512)
  assertEquals(NightmareEditGeometry.Bounds(0, 224, 1024, 576), p.boundsIn(1024, 1024))
 }
 @Test fun invalidDimensionsRejected() {
  try { NightmareEditGeometry.plan(0, 900, 512); fail("invalid") }
  catch (_: IllegalArgumentException) {}
 }
}
