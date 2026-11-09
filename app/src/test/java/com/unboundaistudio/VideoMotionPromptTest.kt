package com.unboundaistudio

import org.junit.Assert.*
import org.junit.Test

class VideoMotionPromptTest {
 @Test fun keepsActualRequestedActionFirst() {
  val p = VideoMotionPrompt.build("The dancer raises both arms and spins.")
  assertTrue(p.startsWith("The dancer raises both arms and spins."))
  assertTrue(p.contains("real articulated"))
  assertTrue(p.contains("not merely moving the camera"))
  assertTrue(p.contains("horizontal bands"))
 }
 @Test fun emptyMotionIsRejected() {
  try { VideoMotionPrompt.build(" "); fail("no action") }
  catch (_: IllegalArgumentException) {}
 }
}
